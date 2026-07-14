package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.BuildConfig
import com.kamneko88.comicveil.data.ArchiveScanner
import com.kamneko88.comicveil.data.GrowingFileInputStream
import com.kamneko88.comicveil.data.ZipStreamSupport
import com.kamneko88.comicveil.data.db.Bookmark
import com.kamneko88.comicveil.data.db.BookmarkRepository
import com.kamneko88.comicveil.data.db.ComicFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** filePath内でアーカイブパスと巻フォルダ名を区切るマーカー（実際のパスに出現しない文字列） */
private const val VOLUME_MARKER = "##vol##"

/**
 * 開発時のみ出力されるデバッグログ。
 * リリースビルドでは何も出力しない（ログの整理）。
 * エラー・警告（Log.e / Log.w）は常に出力する。
 */
private fun logD(message: String) {
    if (BuildConfig.DEBUG) Log.d("ComicVeil", message)
}

data class ViewerUiState(
    val pages: List<ByteArray> = emptyList(),
    val pageFiles: List<String> = emptyList(),
    val isProgressiveMode: Boolean = false,
    val availablePageCount: Int = 0,
    val totalPageCount: Int = 0,
    val isComplete: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialPage: Int = 0,
    val isSavedPageLoaded: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val isCurrentPageBookmarked: Boolean = false,
    val needsPassword: Boolean = false,
    /** ストリーミング中のダウンロード進捗（0.0〜1.0）。通常の開き方のときは null */
    val downloadFraction: Float? = null,
    /** 画面に出すファイル名。リモートの本はキャッシュ名ではなく元の作品名を出す */
    val displayName: String = ""
)

enum class PageLimitEvent { FIRST, LAST }

class ViewerViewModel(
    application: Application,
    private val filePath: String
) : AndroidViewModel(application) {

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository
    private val bookmarkRepository : BookmarkRepository
    private val fileTitleDao       : com.kamneko88.comicveil.data.db.FileTitleDao

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _pageLimitEvent = MutableSharedFlow<PageLimitEvent>(replay = 0, extraBufferCapacity = 1)
    val pageLimitEvent: SharedFlow<PageLimitEvent> = _pageLimitEvent.asSharedFlow()

    private var lastSavedPage = 0

    override fun onCleared() {
        super.onCleared()
        val total = maxOf(_uiState.value.pages.size, _uiState.value.totalPageCount)
        if (total == 0) return
        val status = when {
            lastSavedPage >= total - 1 -> ReadStatus.READ
            lastSavedPage == 0        -> ReadStatus.UNREAD
            else                      -> ReadStatus.READING
        }
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            comicFileRepository.updateStatus(filePath, status)
        }
    }

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())
        bookmarkRepository  = BookmarkRepository(db.bookmarkDao())
        fileTitleDao        = db.fileTitleDao()

        // 表示用のファイル名を決める。
        // リモートの本はキャッシュ上で nas_-409694946.zip のような名前になっているので、
        // Home側で控えておいた元の作品名を優先する（一時ファイル名は内部でだけ使う）。
        viewModelScope.launch {
            val markerIndex = filePath.indexOf(VOLUME_MARKER)
            val archivePath = if (markerIndex >= 0) filePath.substring(0, markerIndex) else filePath
            val volumeName  = if (markerIndex >= 0) filePath.substring(markerIndex + VOLUME_MARKER.length) else null

            val original = withContext(Dispatchers.IO) {
                runCatching { fileTitleDao.getName(archivePath) }.getOrNull()
            } ?: File(archivePath).name

            val shown = if (volumeName != null) "$original ／ $volumeName" else original
            _uiState.update { it.copy(displayName = shown) }
        }

        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) { progressRepository.getProgress(filePath) }
            val savedPage = progress?.currentPage ?: 0
            lastSavedPage = savedPage
            _uiState.update { it.copy(initialPage = savedPage, isSavedPageLoaded = true) }
        }

        loadFile()
    }

    private fun loadFile(password: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // filePathは "実ファイルパス" または "実ファイルパス##vol##巻フォルダ名" の形式
                    val markerIndex  = filePath.indexOf(VOLUME_MARKER)
                    val archivePath  = if (markerIndex >= 0) filePath.substring(0, markerIndex) else filePath
                    val requestedVolume = if (markerIndex >= 0) filePath.substring(markerIndex + VOLUME_MARKER.length) else null

                    val file = File(archivePath)
                    if (file.isDirectory) {
                        loadFromPageDirectory(file)
                        return@withContext
                    }

                    val ext = file.extension.lowercase()

                    // ZIPの場合：展開前にzip4jでパスワード付きか確認する
                    if (ext in setOf("zip", "cbz") && password == null) {
                        val encrypted = try {
                            Zip4jFile(file).isEncrypted
                        } catch (e: Exception) {
                            logD("zip4j暗号化チェック失敗: ${e.message}")
                            false
                        }
                        if (encrypted) {
                            logD("パスワード付きZIPを検出: ${file.name}")
                            _uiState.update { it.copy(isLoading = false, needsPassword = true) }
                            return@withContext
                        }
                    }

                    when (ext) {
                        "zip", "cbz", "rar", "cbr", "7z" ->
                            loadArchiveProgressive(file, ext, requestedVolume, password)
                        "pdf" -> {
                            logD("展開開始: ${file.name} (${file.length()} bytes)")
                            val pages = extractPdf(file)
                            _uiState.update {
                                it.copy(
                                    pages              = pages,
                                    availablePageCount = pages.size,
                                    totalPageCount     = pages.size,
                                    isComplete         = true,
                                    isLoading          = false,
                                    needsPassword      = false
                                )
                            }
                        }
                        else -> _uiState.update { it.copy(isLoading = false) }
                    }
                } catch (e: Exception) {
                    Log.e("ComicVeil", "展開エラー: ${e::class.simpleName}: ${e.message}", e)
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
    }

    /**
     * アーカイブ（ZIP/RAR/7z）を「先に中身を一覧→ページを1枚ずつ展開してすぐ表示」する方式で読み込む。
     */
    private suspend fun loadArchiveProgressive(
        file: File,
        ext: String,
        requestedVolume: String?,
        password: String?
    ) {
        val pageDir = pageDirFor(file, requestedVolume)

        // 【ストリーミング】ダウンロードしながら読む場合。
        // Home側でZIPの目次（中央ディレクトリ）を先読みしてサイドカーに保存してある。
        // ファイルがまだ完成していなければ、届いた分から順にページを取り出していく。
        val streamInfo = ZipStreamSupport.readSidecar(file)
        if (streamInfo != null) {
            if (file.length() < streamInfo.expectedSize) {
                loadZipStreaming(file, streamInfo, pageDir)
                return
            }
            // ダウンロード完了済みなのでサイドカーは不要
            ZipStreamSupport.deleteSidecar(file)
        }

        // 【高速化】展開済みキャッシュがあれば、アーカイブには一切触らずに即表示する。
        // 以前はキャッシュの有無に関わらず先に ArchiveScanner.scan() を実行していたため、
        // 開くたびにアーカイブ全体を読み直していた（特に中央ディレクトリが壊れたZIPは
        // 先頭から全体を逐次読みするため、数百MBを毎回フルスキャンしていた）。
        // これが「初回以降もモッサリする」「1ページ目が出るまで待たされる」原因だった。
        if (File(pageDir, "complete").exists()) {
            val existingPageCount = pageDir.listFiles { f ->
                f.name.endsWith(".jpg") && !f.name.startsWith("incoming_")
            }?.size ?: 0
            if (existingPageCount > 0) {
                logD("キャッシュから即表示: ${pageDir.name} (${existingPageCount}ページ)")
                loadFromPageDirectory(pageDir)
                return
            }
            // 完了マークはあるが実ページが0枚 = 過去の展開失敗キャッシュ。作り直す。
            logD("空のcompleteキャッシュを検出したため再展開します: ${pageDir.name}")
        }

        val scanStart = System.currentTimeMillis()
        val scan = ArchiveScanner.scan(file)
        logD("アーカイブ走査: ${System.currentTimeMillis() - scanStart}ms (${scan.entries.size}件)")

        val targetEntries = if (requestedVolume != null) {
            scan.entries.filter { it.volumeName == requestedVolume }
        } else {
            scan.entries
        }

        if (targetEntries.isEmpty()) {
            _uiState.update { it.copy(pages = emptyList(), isComplete = true, isLoading = false) }
            return
        }

        pageDir.deleteRecursively()
        pageDir.mkdirs()

        // バックグラウンドで1ページずつ展開（メモリに全ページを溜め込まない）
        val extractStart = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (ext) {
                    "zip", "cbz" -> extractZipProgressive(file, targetEntries, pageDir, password, scan.zipCharset)
                    "rar", "cbr" -> extractRarProgressive(file, targetEntries, pageDir)
                    "7z"         -> extract7zProgressive(file, targetEntries, pageDir)
                }
                logD("展開完了: ${System.currentTimeMillis() - extractStart}ms (${targetEntries.size}ページ)")
            } catch (e: Exception) {
                Log.e("ComicVeil", "段階展開エラー: ${e.message}", e)
                // 失敗時も complete マーカーを置き、無限ローディングにしない
                runCatching { File(pageDir, "complete").writeText("0") }
            }
        }

        loadFromPageDirectory(pageDir, knownTotal = targetEntries.size)
    }

    private fun pageDirFor(file: File, volume: String?): File {
        val key = "${file.absolutePath}::${volume ?: ""}".hashCode()
        return File(File(getApplication<Application>().cacheDir, "archive_pages"), "arc_$key")
    }

    /**
     * 【ZIPストリーミング】ダウンロードしながら読む。
     *
     * ZIPは先頭から順にページが並んでいるので、落ちてきた分から順に展開できる。
     * まだ届いていない位置まで読み進んだら、その場で少し待って再挑戦する（GrowingFileInputStream）。
     *
     * 総ページ数はHome側で先読みした目次から分かっているので、
     * ページ移動バーも最初から正しく作れる。
     */
    private suspend fun loadZipStreaming(
        file: File,
        info: ZipStreamSupport.StreamInfo,
        pageDir: File
    ) {
        logD("ZIPストリーミング開始: ${file.name} (全${info.entryNames.size}ページ / ${info.expectedSize} bytes)")

        pageDir.deleteRecursively()
        pageDir.mkdirs()

        _uiState.update {
            it.copy(
                isProgressiveMode = true,
                totalPageCount    = info.entryNames.size,
                isComplete        = false
            )
        }

        // ダウンロードの進捗を見守る（ページ移動バーの色で表示する）
        viewModelScope.launch {
            while (true) {
                val downloaded = file.length()
                val fraction   = (downloaded.toFloat() / info.expectedSize).coerceIn(0f, 1f)
                _uiState.update { it.copy(downloadFraction = fraction) }
                if (downloaded >= info.expectedSize) break
                kotlinx.coroutines.delay(300)
            }
            _uiState.update { it.copy(downloadFraction = null) }
        }

        // 届いた分から順に展開する
        val extractStart = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                extractZipStreaming(file, info, pageDir)
                logD("ストリーミング展開完了: ${System.currentTimeMillis() - extractStart}ms")
                ZipStreamSupport.deleteSidecar(file)
            } catch (e: Exception) {
                Log.e("ComicVeil", "ストリーミング展開エラー: ${e.message}", e)
                runCatching { File(pageDir, "complete").writeText("0") }
            }
        }

        loadFromPageDirectory(pageDir, knownTotal = info.entryNames.size)
    }

    fun retryWithPassword(password: String) {
        _uiState.update { it.copy(isLoading = true, needsPassword = false) }
        loadFile(password)
    }

    private suspend fun loadFromPageDirectory(pageDir: File, knownTotal: Int? = null) {
        if (knownTotal != null) {
            _uiState.update { it.copy(totalPageCount = knownTotal) }
        }
        var firstPageShownAt = 0L
        val start = System.currentTimeMillis()
        while (true) {
            val files = pageDir.listFiles { f -> f.name.endsWith(".jpg") && !f.name.startsWith("incoming_") }
                ?.sortedBy { it.name } ?: emptyList()
            val isComplete = File(pageDir, "complete").exists()
            val filePaths = files.map { it.absolutePath }

            if (firstPageShownAt == 0L && filePaths.isNotEmpty()) {
                firstPageShownAt = System.currentTimeMillis()
                logD("1ページ目表示まで: ${firstPageShownAt - start}ms")
            }

            _uiState.update {
                it.copy(
                    pageFiles          = filePaths,
                    isProgressiveMode  = true,
                    availablePageCount = filePaths.size,
                    isLoading          = filePaths.isEmpty(),
                    isComplete         = isComplete,
                    totalPageCount     = knownTotal ?: it.totalPageCount
                )
            }
            if (isComplete) {
                val total = knownTotal ?: runCatching {
                    File(pageDir, "complete").readText().toInt()
                }.getOrDefault(filePaths.size)
                _uiState.update { it.copy(totalPageCount = maxOf(total, filePaths.size)) }
                break
            }
            // 展開中は短い間隔で見に行く（以前は300msで、その分だけ初回表示が遅れていた）
            kotlinx.coroutines.delay(100)
        }
    }

    fun savePage(currentPage: Int) {
        val totalPages = maxOf(_uiState.value.pages.size, _uiState.value.totalPageCount)
        if (totalPages == 0) return
        lastSavedPage = currentPage
        val newStatus = when {
            currentPage >= totalPages - 1 -> ReadStatus.READ
            currentPage == 0             -> ReadStatus.UNREAD
            else                         -> ReadStatus.READING
        }
        viewModelScope.launch(Dispatchers.IO) {
            progressRepository.saveProgress(filePath, currentPage, totalPages)
            comicFileRepository.updateStatus(filePath, newStatus)
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, currentPage)
            _uiState.update { it.copy(isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun toggleBookmark(currentPage: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.toggleBookmark(filePath, currentPage)
            val bookmarks    = bookmarkRepository.getBookmarks(filePath)
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, currentPage)
            _uiState.update { it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun loadBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val bookmarks = bookmarkRepository.getBookmarks(filePath)
            _uiState.update { it.copy(bookmarks = bookmarks) }
        }
    }

    fun deleteBookmark(page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = ComicVeilDatabase.getDatabase(getApplication())
            db.bookmarkDao().deleteBookmark(filePath, page)
            val bookmarks    = bookmarkRepository.getBookmarks(filePath)
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, lastSavedPage)
            _uiState.update { it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun deleteAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.deleteAllBookmarks(filePath)
            _uiState.update { it.copy(bookmarks = emptyList(), isCurrentPageBookmarked = false) }
        }
    }

    fun onPageLimitReached(event: PageLimitEvent) {
        _pageLimitEvent.tryEmit(event)
    }

    companion object {
        const val VOLUME_MARKER_PUBLIC = VOLUME_MARKER

        fun Factory(application: Application, filePath: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ViewerViewModel(application, filePath) as T
                }
            }
        }
    }
}

// ─── ページ展開関数（段階展開・メモリに溜め込まない） ────────────────────────

/**
 * 1ページあたりの上限サイズ。通常のマンガ1ページはこれよりはるかに小さいはず。
 * これを超える場合はアーカイブの構造読み取りに問題がある可能性が高いため、
 * OOMクラッシュを防ぐためにここで安全に中断する。
 */
private const val MAX_PAGE_BYTES = 80L * 1024 * 1024 // 80MB

/**
 * サイズ上限付きでInputStreamから読み込む。
 * 上限を超えた場合はnullを返す（異常なエントリとみなして中断する）。
 */
private fun readBoundedBytes(input: InputStream, knownSize: Long): ByteArray? {
    if (knownSize in 1..MAX_PAGE_BYTES) {
        val buf = ByteArray(knownSize.toInt())
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read < 0) break
            offset += read
        }
        return buf.copyOf(offset)
    }
    // サイズ不明（または異常に大きい）場合は上限付きで読む
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (total < MAX_PAGE_BYTES) {
        val read = input.read(chunk)
        if (read < 0) break
        out.write(chunk, 0, read)
        total += read
    }
    if (total >= MAX_PAGE_BYTES) {
        Log.e("ComicVeil", "1ページのサイズが上限(${MAX_PAGE_BYTES}bytes)を超えたため中断しました。アーカイブの構造が非標準な可能性があります")
        return null
    }
    return out.toByteArray()
}

private fun writePage(pageDir: File, index: Int, bytes: ByteArray) {
    File(pageDir, "%05d.jpg".format(index)).writeBytes(bytes)
}

/**
 * ZIP：まず高速なランダムアクセス方式で自然順に直接書き込む。
 * 一部の非標準なZIPでランダムアクセスが失敗する場合は、逐次読み込み方式にフォールバックする。
 * （libarchiveではAndroid上で日本語パス名のUTF-8取得が安定しなかったため、ZIPはCommons Compressに戻している）
 */
private fun extractZipProgressive(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File,
    password: String?,
    zipCharset: String?
) {
    if (password != null) {
        val zipFile = Zip4jFile(file, password.toCharArray())
        val headerMap = zipFile.fileHeaders.associateBy { it.fileName }
        targetEntries.forEachIndexed { index, info ->
            val header = headerMap[info.name] ?: return@forEachIndexed
            zipFile.getInputStream(header).use { input ->
                val bytes = readBoundedBytes(input, header.uncompressedSize)
                    ?: return@forEachIndexed
                writePage(pageDir, index, bytes)
            }
        }
        File(pageDir, "complete").writeText(targetEntries.size.toString())
        return
    }

    val success = try {
        val builder = CommonsZipFile.builder().setFile(file)
        if (zipCharset != null) builder.setCharset(charset(zipCharset))
        builder.get().use { zip ->
            targetEntries.forEachIndexed { index, info ->
                val entry = zip.getEntry(info.name) ?: return@forEachIndexed
                zip.getInputStream(entry).use { input ->
                    val bytes = readBoundedBytes(input, entry.size)
                        ?: throw IllegalStateException("ページサイズが異常です: ${info.name}")
                    writePage(pageDir, index, bytes)
                }
            }
        }
        true
    } catch (e: Exception) {
        logD("ZIPランダムアクセス展開失敗、逐次方式にフォールバック: ${e.message}")
        false
    }

    logD("ZIP展開: ランダムアクセス=$success, 対象=${targetEntries.size}件, charset=$zipCharset")

    if (!success) {
        val ok = extractZipSequentialFallback(file, targetEntries, pageDir)
        logD("ZIP逐次フォールバック結果: ok=$ok")
        if (!ok) {
            File(pageDir, "complete").writeText("0")
            return
        }
    }

    File(pageDir, "complete").writeText(targetEntries.size.toString())
}

/**
 * ZIPの緊急フォールバック：到着順に一時名で書き出し、完了後に自然順の最終位置へリネームする。
 * 1ページでも上限サイズを超えた場合は、このアーカイブは読み込み不可と判断してfalseを返す。
 */
private fun extractZipSequentialFallback(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File
): Boolean {
    val targetNames = targetEntries.map { it.name }.toSet()
    val arrivalNameOrder = mutableListOf<String>()
    var aborted = false

    for (cs in listOf("UTF-8", "Shift_JIS")) {
        arrivalNameOrder.clear()
        aborted = false
        pageDir.listFiles { f -> f.name.startsWith("incoming_") }?.forEach { it.delete() }
        try {
            ZipArchiveInputStream(file.inputStream().buffered(), cs, false, true).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name in targetNames) {
                        val bytes = readBoundedBytes(zis, entry.size)
                        if (bytes == null) {
                            aborted = true
                            return@use
                        }
                        if (bytes.isNotEmpty()) {
                            File(pageDir, "incoming_%05d.jpg".format(arrivalNameOrder.size)).writeBytes(bytes)
                            arrivalNameOrder.add(name)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            logD("ZIP逐次フォールバック失敗（$cs）: ${e.message}")
        }
        if (aborted) break
        if (arrivalNameOrder.isNotEmpty()) break
    }

    if (aborted) {
        pageDir.listFiles { f -> f.name.startsWith("incoming_") }?.forEach { it.delete() }
        return false
    }

    val finalIndexByName = targetEntries.withIndex().associate { (i, info) -> info.name to i }
    logD("逐次フォールバック: ${arrivalNameOrder.size}件取得 / 対象${targetNames.size}件 (aborted=$aborted)")
    arrivalNameOrder.forEachIndexed { arrivalIdx, name ->
        val finalIdx = finalIndexByName[name] ?: return@forEachIndexed
        File(pageDir, "incoming_%05d.jpg".format(arrivalIdx))
            .renameTo(File(pageDir, "%05d.jpg".format(finalIdx)))
    }
    return arrivalNameOrder.isNotEmpty()
}

/**
 * 【ZIPストリーミング展開】ダウンロードしながら、届いた分から順にページを書き出す。
 *
 * ページの並び順（最終的なページ番号）は、先に読んだ目次から分かっている。
 * そのため届いた順に展開しても、正しいページ番号の位置へ直接書ける。
 *
 * ダウンロードが止まってしまった場合（通信切断・キャンセル）は、
 * 一定時間ファイルが伸びなければ打ち切る（無限に待たない）。
 */
private fun extractZipStreaming(
    file: File,
    info: com.kamneko88.comicveil.data.ZipStreamSupport.StreamInfo,
    pageDir: File
) {
    val indexByName = info.entryNames.withIndex().associate { (i, name) -> name to i }

    var lastLength   = file.length()
    var lastGrowthAt = System.currentTimeMillis()

    // まだダウンロードが進んでいるか（伸びなくなったら止まったとみなす）
    val isDownloading = {
        val length = file.length()
        if (length != lastLength) {
            lastLength   = length
            lastGrowthAt = System.currentTimeMillis()
        }
        length < info.expectedSize &&
            (System.currentTimeMillis() - lastGrowthAt) < STREAM_STALL_TIMEOUT_MS
    }

    var written = 0
    GrowingFileInputStream(file, isDownloading, info.expectedSize).use { growing ->
        ZipArchiveInputStream(growing.buffered(), info.charset, false, true).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val finalIndex = if (!entry.isDirectory) indexByName[entry.name] else null
                if (finalIndex != null) {
                    val bytes = readBoundedBytes(zis, entry.size)
                    if (bytes != null && bytes.isNotEmpty()) {
                        writePage(pageDir, finalIndex, bytes)
                        written++
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    logD("ストリーミング展開: ${written}ページ / 全${info.entryNames.size}ページ")
    File(pageDir, "complete").writeText(written.toString())
}

/** ダウンロードがこれだけ伸びなければ、止まったとみなして待つのをやめる */
private const val STREAM_STALL_TIMEOUT_MS = 30_000L

/**
 * libarchiveで逐次読み込みし、目的のページだけ最終位置へ直接書き込む。RAR（RAR5含む）専用。
 */
private fun extractWithLibarchive(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File,
    formatLabel: String,
    configureFormat: (archive: Long) -> Unit
) {
    val finalIndexByName = targetEntries.withIndex().associate { (i, info) -> info.name to i }
    var archive = 0L
    try {
        archive = Archive.readNew()
        configureFormat(archive)
        Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 10240L)

        while (true) {
            val entry = ArchiveEntry.new1()
            var isEof = false
            var isFatal = false
            try {
                val ret = Archive.readNextHeader2(archive, entry)
                if (ret.toInt() == Archive.ERRNO_EOF) isEof = true
            } catch (e: ArchiveException) {
                when {
                    e.code == Archive.ERRNO_WARN -> {
                        Log.w("ComicVeil", "${formatLabel}展開警告: ${e.message}")
                    }
                    e.message?.contains("eof", ignoreCase = true) == true -> {
                        isEof = true
                    }
                    else -> {
                        Log.e("ComicVeil", "${formatLabel}展開中断(code=${e.code}): ${e.message}")
                        isFatal = true
                    }
                }
            }
            if (isEof || isFatal) {
                ArchiveEntry.free(entry)
                break
            }

            val name     = ArchiveEntry.pathnameUtf8(entry)
            val finalIdx = name?.let { finalIndexByName[it] }

            if (finalIdx != null) {
                val sizeKnown = ArchiveEntry.sizeIsSet(entry)
                val size      = if (sizeKnown) ArchiveEntry.size(entry) else -1L
                if (sizeKnown && size > MAX_PAGE_BYTES) {
                    Log.e("ComicVeil", "${formatLabel}ページサイズが上限(${MAX_PAGE_BYTES}bytes)を超えたためスキップ: $name ($size bytes)")
                } else {
                    val outFile = File(pageDir, "%05d.jpg".format(finalIdx))
                    var outPfd: ParcelFileDescriptor? = null
                    try {
                        outPfd = ParcelFileDescriptor.open(
                            outFile,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_READ_WRITE
                        )
                        Archive.readDataIntoFd(archive, outPfd.fd)
                    } catch (e: Exception) {
                        Log.e("ComicVeil", "${formatLabel}ページ展開失敗（libarchive）: $name", e)
                        runCatching { outFile.delete() }
                    } finally {
                        runCatching { outPfd?.close() }
                    }
                }
            }

            ArchiveEntry.free(entry)
        }
    } finally {
        if (archive != 0L) {
            runCatching { Archive.readClose(archive) }
            runCatching { Archive.readFree(archive) }
        }
    }
    File(pageDir, "complete").writeText(targetEntries.size.toString())
}

/** RAR：libarchiveで逐次読み込み（RAR5対応） */
private fun extractRarProgressive(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File
) {
    extractWithLibarchive(file, targetEntries, pageDir, "RAR") { archive ->
        Archive.readSupportFormatRar(archive)
        Archive.readSupportFormatRar5(archive)
    }
}

/**
 * 7z：SevenZFileは前から順にしか読めない制約があるため、
 * アーカイブに現れる順（到着順）で一時ファイルに書き出し、
 * 完了後に自然順の最終位置へ一括リネームする（バイトの再書き込みはしない：高速）
 * （libarchiveではAndroid上で日本語パス名のUTF-8取得が安定しなかったため、7zはCommons Compressに戻している）
 */
private fun extract7zProgressive(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File
) {
    val targetNames = targetEntries.map { it.name }.toSet()
    var arrivalIndex = 0
    val arrivalNameOrder = mutableListOf<String>()

    SevenZFile.builder().setFile(file).get().use { sevenZFile ->
        var entry = sevenZFile.nextEntry
        while (entry != null) {
            val name = entry.name ?: ""
            if (!entry.isDirectory && name in targetNames) {
                val size = entry.size
                val bytes = if (size in 1..MAX_PAGE_BYTES) {
                    val buf = ByteArray(size.toInt())
                    var offset = 0
                    while (offset < buf.size) {
                        val read = sevenZFile.read(buf, offset, buf.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    buf.copyOf(offset)
                } else {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < MAX_PAGE_BYTES) {
                        val read = sevenZFile.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        total += read
                    }
                    if (total >= MAX_PAGE_BYTES) {
                        Log.e("ComicVeil", "7zページサイズが上限を超えたためスキップ: $name")
                        ByteArray(0)
                    } else {
                        out.toByteArray()
                    }
                }
                if (bytes.isNotEmpty()) {
                    File(pageDir, "incoming_%05d.jpg".format(arrivalIndex)).writeBytes(bytes)
                    arrivalNameOrder.add(name)
                    arrivalIndex++
                }
            }
            entry = sevenZFile.nextEntry
        }
    }

    val finalIndexByName = targetEntries.withIndex().associate { (i, info) -> info.name to i }
    arrivalNameOrder.forEachIndexed { arrivalIdx, name ->
        val finalIdx = finalIndexByName[name] ?: return@forEachIndexed
        val src = File(pageDir, "incoming_%05d.jpg".format(arrivalIdx))
        val dst = File(pageDir, "%05d.jpg".format(finalIdx))
        src.renameTo(dst)
    }
    File(pageDir, "complete").writeText(targetEntries.size.toString())
}

// ─── PDF ─────────────────────────────────────────────────────────────────────

private fun extractPdf(file: File): List<ByteArray> {
    val pages = mutableListOf<ByteArray>()
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfRenderer(pfd).use { renderer ->
        for (i in 0 until renderer.pageCount) {
            renderer.openPage(i).use { page ->
                val scale  = minOf(1080f / page.width, 1440f / page.height)
                val width  = (page.width  * scale).toInt()
                val height = (page.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bitmap.recycle()
                pages.add(out.toByteArray())
            }
        }
    }
    return pages
}
