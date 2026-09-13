package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.BuildConfig
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.ArchiveScanner
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.FileItemType
import com.kamneko88.comicveil.data.FormatDetector
import com.kamneko88.comicveil.data.GrowingFileInputStream
import com.kamneko88.comicveil.data.ImageFolderScanner
import com.kamneko88.comicveil.data.ThumbnailRepository
import com.kamneko88.comicveil.data.ZipStreamSupport
import com.kamneko88.comicveil.data.db.Bookmark
import com.kamneko88.comicveil.data.db.BookmarkRepository
import com.kamneko88.comicveil.data.db.ComicFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import com.kamneko88.comicveil.data.nas.NasStreamCache
import com.kamneko88.comicveil.data.nas.TransferManager
import com.kamneko88.comicveil.ui.home.HomeViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** filePath内でアーカイブパスと巻フォルダ名を区切るマーカー（実際のパスに出現しない文字列） */
private const val VOLUME_MARKER = "##vol##"

/**
 * filePath内で「実ファイルパス」と「進捗/状態の保存に使う正規キー」を区切るマーカー。
 * NAS STR/DLモードでは実ファイルがローカルキャッシュパスになるが、HOME一覧・ブックマーク
 * 一覧はFileItem.path（NASならsmb://...）で状態を読み書きしているため、両者を一致させたい
 * ときにこのマーカーで正規キーを付加して受け渡す（VOLUME_MARKERと同じ考え方）。
 */
private const val KEY_MARKER = "##key##"

/**
 * filePath内で「非圧縮の画像フォルダを開く」ことと「開始ページのインデックス」を表すマーカー。
 * ユーザーがフォルダ内の画像ファイルを直接タップして開いた場合に、そのフォルダの絶対パス
 * （＝実ファイルパス兼進捗保存キー）に続けて付加する（例："/path/to/folder##page##3"）。
 *
 * 【なぜファイルシステムのisDirectory判定と分離するか】既存の
 * 「if (file.isDirectory) { loadFromPageDirectory(file) }」は、アーカイブ展開後の内部
 * キャッシュ（cacheDir/archive_pages/arc_xxx、.jpg固定・completeマーカー前提）を読むための
 * 経路であり、ユーザーの生の画像フォルダとは中身の前提が異なる。isDirectoryだけで判定すると
 * 両者が衝突するため、このマーカーの有無だけで「画像フォルダオープンかどうか」を判定する。
 */
private const val PAGE_MARKER = "##page##"

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
    /** trueなら、アーカイブが見つからなかった（キャッシュから削除された等）ことが原因。
     *  「非対応のファイル形式」ダイアログと区別して専用メッセージを出す。 */
    val fileMissing: Boolean = false,
    /** ストリーミング中のダウンロード進捗（0.0〜1.0）。通常の開き方のときは null */
    val downloadFraction: Float? = null,
    /** 画面に出すファイル名。リモートの本はキャッシュ名ではなく元の作品名を出す */
    val displayName: String = "",
    /** 横長（見開きの1枚絵）と判定済みのページ番号（0始まり）。見開き分割機能で使う */
    val widePages: Set<Int> = emptySet()
)

enum class PageLimitEvent { FIRST, LAST }

class ViewerViewModel(
    application: Application,
    navKey: String
) : AndroidViewModel(application) {

    // navKeyは以下のいずれかの形式：
    //   "実ファイルパス"
    //   "実ファイルパス##vol##巻フォルダ名"                （複数巻構成。ArchiveVolumeViewModel由来）
    //   "実ファイルパス##key##正規キー"                     （NAS STR/DL。実ファイルはローカルキャッシュだが
    //                                                         進捗・状態はHOME/ブックマーク側のFileItem.pathで保存したい場合）
    //   "実ファイルパス##vol##巻フォルダ名##key##正規キー"   （理論上の組み合わせ。現状のNAS経路では発生しない）
    //   "フォルダの絶対パス##page##開始インデックス"          （非圧縮画像フォルダをタップして開いた場合。
    //                                                         HomeViewModel.confirmOpenImageFolder()由来）
    //
    // 【なぜ分けるか】以前はfilePath 1つで「実ファイルの読み込み」と「進捗/状態の保存キー」を
    // 兼用しており、NAS STRモードでは実ファイルがローカルキャッシュパスになるため、
    // HOME一覧・ブックマーク一覧が使うFileItem.path（NASならsmb://...）と食い違い、
    // 既読状態・評価・カラーラベルがHOME側に反映されない不具合があった。
    private val keyMarkerIndex = navKey.indexOf(KEY_MARKER)
    private val canonicalKeyOverride: String? =
        if (keyMarkerIndex >= 0) navKey.substring(keyMarkerIndex + KEY_MARKER.length) else null
    private val pathAndVolumeAndPage: String =
        if (keyMarkerIndex >= 0) navKey.substring(0, keyMarkerIndex) else navKey

    private val pageMarkerIndex = pathAndVolumeAndPage.indexOf(PAGE_MARKER)
    /** 画像フォルダの開始ページ（タップして明示的に選んだ場合のみ非null） */
    private val imageFolderStartIndex: Int? =
        if (pageMarkerIndex >= 0) {
            pathAndVolumeAndPage.substring(pageMarkerIndex + PAGE_MARKER.length).toIntOrNull()
        } else null
    private val pathAndVolume: String =
        if (pageMarkerIndex >= 0) pathAndVolumeAndPage.substring(0, pageMarkerIndex) else pathAndVolumeAndPage

    private val volumeMarkerIndex = pathAndVolume.indexOf(VOLUME_MARKER)
    /** 実ファイルパス（アーカイブ本体・ページキャッシュディレクトリなど、実際のI/Oに使う） */
    private val filePath: String =
        if (volumeMarkerIndex >= 0) pathAndVolume.substring(0, volumeMarkerIndex) else pathAndVolume
    /** 巻フォルダ名（複数巻構成でなければnull） */
    private val requestedVolume: String? =
        if (volumeMarkerIndex >= 0) pathAndVolume.substring(volumeMarkerIndex + VOLUME_MARKER.length) else null

    /** 複数巻構成（巻フォルダ）を開いているか。「表紙に設定」は複数巻構成では対象外にする */
    val isMultiVolumeView: Boolean = requestedVolume != null
    /**
     * 進捗・既読状態・評価・カラーラベル・栞の保存に使う正規キー。
     * canonicalKeyOverrideが無ければ実ファイルパスをそのまま使う（ローカル・SAF取り込み済み
     * ファイルは従来どおり）。複数巻構成では巻ごとに区別するため、
     * ArchiveVolumeViewModel.keyFor()と同じ形でVOLUME_MARKER+巻名を末尾に付ける。
     */
    private val statusKey: String =
        (canonicalKeyOverride ?: filePath).let { base ->
            if (requestedVolume != null) "$base$VOLUME_MARKER$requestedVolume" else base
        }

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository
    private val bookmarkRepository : BookmarkRepository
    private val fileTitleDao       : com.kamneko88.comicveil.data.db.FileTitleDao

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _pageLimitEvent = MutableSharedFlow<PageLimitEvent>(replay = 0, extraBufferCapacity = 1)
    val pageLimitEvent: SharedFlow<PageLimitEvent> = _pageLimitEvent.asSharedFlow()

    /** 「表紙に設定」の結果（true=成功）。Snackbar表示のためにViewerScreen側で購読する */
    private val _coverSavedEvent = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 1)
    val coverSavedEvent: SharedFlow<Boolean> = _coverSavedEvent.asSharedFlow()

    private var lastSavedPage = 0

    /**
     * 直近の展開失敗の例外（現状はZIPストリーミング展開のみ記録）。
     * 0ページで完了したときに、パスワード付きダイアログを出すべきか判定するために使う。
     * 新しいファイルを開くたび（loadFile呼び出しのたび）に必ずクリアする。
     */
    @Volatile
    private var lastExtractionException: Throwable? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()

        // 本を閉じるたびにページキャッシュの上限チェックを行う（読み進めるほど1セッション中に膨らむため）。
        // viewModelScopeはこの時点で破棄済みのため、GlobalScopeで独立して走らせる（メインスレッドは塞がない）。
        GlobalScope.launch(Dispatchers.IO) {
            HomeViewModel.evictPageCache(getApplication(), AppPrefs(getApplication()).pageCacheLimit.bytes)
        }

        // NASストリーミングキャッシュも同様に、本を閉じるたびに上限チェックを行う。
        GlobalScope.launch(Dispatchers.IO) {
            NasStreamCache.evictIfNeeded(getApplication(), AppPrefs(getApplication()).nasStreamCacheLimit.bytes)
        }

        // 本を閉じたら、その本のストリーミング用ダウンロードを中止して中途半端なキャッシュを破棄する。
        // （「あのシーン何巻だっけ？」と1→2→3巻を開いて閉じるような使い方で、
        //   読んでいない本のDLが裏で走り続け、次に開く本を順番待ちで待たせるのを防ぐ）
        // DLモード（ユーザーが意図的に保存中の転送）は TransferManager 側で対象外にしている。
        TransferManager.cancelStreamingByPath(filePath)
        // 展開途中のページキャッシュも破棄する（完了済みキャッシュには触れない）
        if (_uiState.value.isProgressiveMode && !_uiState.value.isComplete) {
            runCatching { pageDirFor(File(filePath), requestedVolume).deleteRecursively() }
        }

        val total = maxOf(_uiState.value.pages.size, _uiState.value.totalPageCount)
        if (total == 0) return
        val status = when {
            lastSavedPage >= total - 1 -> ReadStatus.READ
            lastSavedPage == 0        -> ReadStatus.UNREAD
            else                      -> ReadStatus.READING
        }
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            comicFileRepository.updateStatus(statusKey, status)
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
            val original = withContext(Dispatchers.IO) {
                runCatching { fileTitleDao.getName(filePath) }.getOrNull()
            } ?: File(filePath).name

            val shown = if (requestedVolume != null) "$original ／ $requestedVolume" else original
            _uiState.update { it.copy(displayName = shown) }
        }

        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) { progressRepository.getProgress(statusKey) }
            val savedPage = progress?.currentPage ?: 0
            lastSavedPage = savedPage
            // 画像フォルダをタップして明示的にページを選んだ場合は、保存済みの読書進捗より
            // タップ操作を優先する（ユーザーが個別の画像を選ぶ操作そのものが「そこから読む」
            // という意思表示のため）。読書進捗・既読状態・栞の保存自体は従来通り行う。
            val initial = imageFolderStartIndex ?: savedPage
            _uiState.update { it.copy(initialPage = initial, isSavedPageLoaded = true) }
        }

        loadFile()
    }

    private fun loadFile(password: String? = null) {
        lastExtractionException = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    if (imageFolderStartIndex != null) {
                        loadFromImageFolder(file)
                        return@withContext
                    }
                    if (file.isDirectory) {
                        loadFromPageDirectory(file)
                        return@withContext
                    }

                    val ext = FormatDetector.effectiveExtension(file)

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
                            val wide = pages.withIndex()
                                .filter { (_, bytes) -> isWideImageBytes(bytes) }
                                .map { it.index }
                                .toSet()
                            _uiState.update {
                                it.copy(
                                    pages              = pages,
                                    availablePageCount = pages.size,
                                    totalPageCount     = pages.size,
                                    isComplete         = true,
                                    isLoading          = false,
                                    needsPassword      = false,
                                    widePages          = wide
                                )
                            }
                        }
                        else -> _uiState.update { it.copy(isLoading = false) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // キャンセルは異常ではない（ViewModel破棄や再読込で普通に起きる）。
                    // 以前はこれをExceptionでまとめて握りつぶし error="Job was cancelled" を出していたが、
                    // CancellationExceptionは握りつぶさず再スローするのが正しい。
                    throw e
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
                // 「最後に読んだ日時」を記録する（古い順の自動削除がこれを見て判定する）。
                // setLastModifiedがfalseを返す端末があるため、失敗時は書き直して更新日時を進める。
                val completeMarker = File(pageDir, "complete")
                val touched = runCatching { completeMarker.setLastModified(System.currentTimeMillis()) }
                    .getOrDefault(false)
                if (!touched) {
                    runCatching { completeMarker.writeText("0") }
                }
                loadFromPageDirectory(pageDir)
                return
            }
            // 完了マークはあるが実ページが0枚 = 過去の展開失敗キャッシュ。作り直す。
            logD("空のcompleteキャッシュを検出したため再展開します: ${pageDir.name}")
        }

        // 【ファイル消失チェック】展開済みページキャッシュが無く、ここから先はアーカイブ本体が
        // 必要になる。NASストリーミングキャッシュがシステム都合で削除された場合など、
        // ファイルが物理的に存在しないことがある。これをArchiveScanner.scan()に渡すと
        // 形式を判定できず「非対応のファイル形式」と誤って表示してしまうため、先に区別する。
        if (!file.exists()) {
            Log.w("ComicVeil", "アーカイブが見つかりません（キャッシュから削除された可能性）: ${file.absolutePath}")
            _uiState.update { it.copy(isLoading = false, fileMissing = true) }
            return
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
                lastExtractionException = e
                runCatching { File(pageDir, "complete").writeText("0") }
            }
        }

        loadFromPageDirectory(pageDir, knownTotal = info.entryNames.size)
    }

    fun retryWithPassword(password: String) {
        _uiState.update { it.copy(isLoading = true, needsPassword = false) }
        loadFile(password)
    }

    /**
     * 非圧縮の画像フォルダ（フォルダ直下に画像ファイルが並んでいる構成）を読み込む。
     * 展開キャッシュは使わず、フォルダ内の画像ファイルの絶対パスをそのまま自然順で渡す。
     * 展開待ちが無いため、アーカイブ用のポーリング（loadFromPageDirectory）は不要で一括セットする。
     *
     * 【指示書との相違点】指示書はisProgressiveMode = falseと指定していたが、ViewerScreen.ktの
     * 実際の描画分岐（"isProgressive && p < pageFiles.size -> pageFiles[p]" /
     * "!isProgressive && p < pages.size -> pages[p]"、pagerCountの算出も同様に分岐）は、
     * isProgressiveMode=falseだとpageFilesを一切参照せずpages（本関数ではセットしない）だけを
     * 見るため、falseのままだとページが1枚も表示されない（pagerCountも0になる）。
     * pageFilesを描画に使わせるにはisProgressiveMode=trueが必須なため、trueを採用した。
     */
    private fun loadFromImageFolder(folder: File) {
        val images = ImageFolderScanner.scan(folder)
        val filePaths = images.map { it.absolutePath }
        _uiState.update {
            it.copy(
                pageFiles          = filePaths,
                isProgressiveMode  = true,
                availablePageCount = filePaths.size,
                totalPageCount     = filePaths.size,
                isComplete         = true,
                isLoading          = false
            )
        }
    }

    private suspend fun loadFromPageDirectory(pageDir: File, knownTotal: Int? = null) {
        if (knownTotal != null) {
            _uiState.update { it.copy(totalPageCount = knownTotal) }
        }
        var firstPageShownAt = 0L
        val start = System.currentTimeMillis()
        // 見開き分割用：展開済みページの横長判定（ヘッダーだけ読むので軽い）。
        // 新しく見えたページだけ判定し、一度判定した分は覚えておいて再判定しない。
        val knownWide = mutableSetOf<Int>()
        var checkedUpTo = 0
        while (true) {
            val files = pageDir.listFiles { f -> f.name.endsWith(".jpg") && !f.name.startsWith("incoming_") }
                ?.sortedBy { it.name } ?: emptyList()
            val isComplete = File(pageDir, "complete").exists()
            val filePaths = files.map { it.absolutePath }

            if (files.size > checkedUpTo) {
                for (i in checkedUpTo until files.size) {
                    if (isWideImageFile(files[i])) knownWide.add(i)
                }
                checkedUpTo = files.size
            }

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
                    totalPageCount     = knownTotal ?: it.totalPageCount,
                    widePages          = knownWide.toSet()
                )
            }
            if (isComplete) {
                val total = knownTotal ?: runCatching {
                    File(pageDir, "complete").readText().toInt()
                }.getOrDefault(filePaths.size)
                _uiState.update { it.copy(totalPageCount = maxOf(total, filePaths.size)) }
                // 0ページで完了した場合、原因を問わず必ずローディングを終わらせる。
                // 直前のupdateでisLoading=filePaths.isEmpty()=trueになっているため、ここで明示的に戻す。
                if (filePaths.isEmpty()) {
                    if (isEncryptionFailure(lastExtractionException)) {
                        _uiState.update { it.copy(isLoading = false, needsPassword = true) }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "ファイルを開けませんでした。ファイルが壊れているか、対応していない形式の可能性があります。"
                            )
                        }
                    }
                }
                break
            }
            // 展開中は短い間隔で見に行く（以前は300msで、その分だけ初回表示が遅れていた）
            kotlinx.coroutines.delay(100)
        }
    }

    /**
     * 展開失敗の原因が暗号化（パスワード付き）によるものか判定する。
     * commons-compressは暗号化エントリを読もうとするとUnsupportedZipFeatureException
     * （feature=ENCRYPTION）を投げる。例外が別の例外に包まれている場合があるため、
     * causeチェーンを辿って探す。
     */
    private fun isEncryptionFailure(e: Throwable?): Boolean {
        var current = e
        var depth = 0
        while (current != null && depth < 10) {
            if (current is UnsupportedZipFeatureException &&
                current.feature == UnsupportedZipFeatureException.Feature.ENCRYPTION
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
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
            progressRepository.saveProgress(statusKey, currentPage, totalPages)
            comicFileRepository.updateStatus(statusKey, newStatus)
            val isBookmarked = bookmarkRepository.isBookmarked(statusKey, currentPage)
            _uiState.update { it.copy(isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun toggleBookmark(currentPage: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.toggleBookmark(statusKey, currentPage)
            val bookmarks    = bookmarkRepository.getBookmarks(statusKey)
            val isBookmarked = bookmarkRepository.isBookmarked(statusKey, currentPage)
            _uiState.update { it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun loadBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val bookmarks = bookmarkRepository.getBookmarks(statusKey)
            _uiState.update { it.copy(bookmarks = bookmarks) }
        }
    }

    fun deleteBookmark(page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = ComicVeilDatabase.getDatabase(getApplication())
            db.bookmarkDao().deleteBookmark(statusKey, page)
            val bookmarks    = bookmarkRepository.getBookmarks(statusKey)
            val isBookmarked = bookmarkRepository.isBookmarked(statusKey, lastSavedPage)
            _uiState.update { it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked) }
        }
    }

    fun deleteAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.deleteAllBookmarks(statusKey)
            _uiState.update { it.copy(bookmarks = emptyList(), isCurrentPageBookmarked = false) }
        }
    }

    fun onPageLimitReached(event: PageLimitEvent) {
        _pageLimitEvent.tryEmit(event)
    }

    /**
     * 現在表示中のページ（と見開き分割状態）を、この本（アーカイブ全体）の表紙として保存する。
     * 複数巻構成（[isMultiVolumeView]）では呼び出し側がUIを出さない想定だが、念のためここでも弾く。
     *
     * ページの画像バイト列は、段階展開（ZIP/RAR/7z）なら[ViewerUiState.pageFiles]の
     * 展開済みJPEGファイルから、PDF（非段階展開）なら[ViewerUiState.pages]から取得する
     * （ui/viewer/ViewerScreen.ktのページ表示分岐と同じ考え方）。
     *
     * 【v1.5.1 追記】画面表示はCoilのメモリキャッシュに乗っているため、実機ではpageFilesの
     * パスに実ファイルが存在しない（OSのストレージ逼迫等で消えた）状態でも表示が継続してしまい、
     * File(path).readBytes()が失敗するケースを確認した。その場合は[reExtractSinglePage]で
     * 書庫から対象ページ1枚だけを直接再抽出するフォールバックを行う。
     */
    fun setCurrentPageAsCover(pageIndex: Int, half: PageHalf) {
        if (isMultiVolumeView) return
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val rawBytes = if (state.isProgressiveMode) {
                val cachedBytes = state.pageFiles.getOrNull(pageIndex)?.let { path ->
                    runCatching { File(path).readBytes() }.getOrNull()
                }
                cachedBytes ?: run {
                    Log.w("ComicVeil", "表紙設定: ページキャッシュが読めないため書庫から再抽出します (pageIndex=$pageIndex)")
                    runCatching { reExtractSinglePage(pageIndex) }
                        .onFailure { Log.w("ComicVeil", "表紙設定: 書庫からの再抽出で例外が発生しました (pageIndex=$pageIndex)", it) }
                        .getOrNull()
                }
            } else {
                state.pages.getOrNull(pageIndex)
            }

            val success = rawBytes?.let { bytes ->
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded == null) {
                    Log.w("ComicVeil", "表紙設定に失敗: 画像のデコードに失敗しました (pageIndex=$pageIndex, bytes=${bytes.size})")
                    false
                } else {
                    val cropped = cropHalf(decoded, half)
                    val repository = ThumbnailRepository(
                        File(getApplication<Application>().cacheDir, "thumbnails"),
                        getApplication()
                    )
                    val fileItem = FileItem(type = FileItemType.COMIC_FILE, path = statusKey)
                    val saved = repository.saveCustomCover(fileItem, cropped)
                    if (!saved) Log.w("ComicVeil", "表紙設定に失敗: ThumbnailRepository.saveCustomCoverがfalseを返しました")
                    if (cropped !== decoded) decoded.recycle()
                    cropped.recycle()
                    saved
                }
            } ?: run {
                Log.w(
                    "ComicVeil",
                    "表紙設定に失敗: ページの画像バイト列を取得できませんでした " +
                        "(pageIndex=$pageIndex, isProgressiveMode=${state.isProgressiveMode})"
                )
                false
            }

            _coverSavedEvent.tryEmit(success)
        }
    }

    /**
     * 表紙設定のフォールバック：ページキャッシュ（[ViewerUiState.pageFiles]）が消えている場合に、
     * 書庫（ZIP/RAR/7z）から対象ページ1枚だけを直接再抽出する。
     *
     * 【設計】新規に専用の抽出ロジックは書かず、既存のprogressive展開関数
     * （extractZipProgressive/extractRarProgressive/extract7zProgressive。いずれも
     * ZIP/RAR5はCommons Compress・libarchive、RAR4はRarSupport(junrar)を使い分ける
     * 既存実装をそのまま流用）に、対象1件だけのtargetEntriesと使い捨ての一時ディレクトリを
     * 渡して呼び出す。書庫全体は再展開されず、指定した1エントリだけが読まれる。
     */
    private fun reExtractSinglePage(pageIndex: Int): ByteArray? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.w("ComicVeil", "表紙設定: 再抽出用の書庫ファイルが見つかりません: $filePath")
            return null
        }
        val ext = FormatDetector.effectiveExtension(file)
        if (ext !in setOf("zip", "cbz", "rar", "cbr", "7z")) {
            Log.w("ComicVeil", "表紙設定: 再抽出未対応の形式です: $ext")
            return null
        }

        val scan = ArchiveScanner.scan(file)
        val targetEntries = if (requestedVolume != null) {
            scan.entries.filter { it.volumeName == requestedVolume }
        } else {
            scan.entries
        }
        val info = targetEntries.getOrNull(pageIndex)
        if (info == null) {
            Log.w("ComicVeil", "表紙設定: 再走査結果にpageIndex=${pageIndex}のエントリがありません（全${targetEntries.size}件）")
            return null
        }

        val tmpDir = File(getApplication<Application>().cacheDir, "cover_reextract_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            when (ext) {
                "zip", "cbz" -> extractZipProgressive(file, listOf(info), tmpDir, null, scan.zipCharset)
                "rar", "cbr" -> extractRarProgressive(file, listOf(info), tmpDir)
                "7z"         -> extract7zProgressive(file, listOf(info), tmpDir)
            }
            val out = File(tmpDir, "%05d.jpg".format(0))
            if (!out.exists()) {
                Log.w("ComicVeil", "表紙設定: 再抽出は完了したがページファイルが見つかりません (entry=${info.name})")
                return null
            }
            return out.readBytes()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    companion object {
        const val VOLUME_MARKER_PUBLIC = VOLUME_MARKER
        const val KEY_MARKER_PUBLIC = KEY_MARKER
        const val PAGE_MARKER_PUBLIC = PAGE_MARKER

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

    // 【対策・v0.29.x】文字コード不一致への救済（2段構え）。
    //
    // ① 7-Zip等は日本語名を「主名欄=CP932 ＋ Unicode拡張フィールド=UTF-8」の形で併記する。
    //    目次側(ZipFile)は既定で拡張フィールドを読むので正しいUTF-8名を得るが、
    //    ストリーミング側はこれを読まない設定だったため主名欄(CP932)をUTF-8として解釈し文字化け→全不一致→0ページだった。
    //    → useUnicodeExtraFields=true にして、ストリーミングでも拡張フィールドのUTF-8名で照合する。
    //
    // ② それでも拡張フィールドすら無い最悪ケース向けの保険：
    //    名前が一致しない画像は「物理的な並び順のN番目＝Nページ目」とみなす。
    //    （マンガのZIPはほぼ確実にページ順に格納されているため、この前提で正しく並ぶ）
    val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    var written = 0
    var imagePos = 0
    var nameMatched = 0
    GrowingFileInputStream(file, isDownloading, info.expectedSize).use { growing ->
        ZipArchiveInputStream(growing.buffered(), info.charset, true, true).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val isImage = entry.name.substringAfterLast(".", "").lowercase() in imageExts
                    val byName  = indexByName[entry.name]
                    if (byName != null) nameMatched++
                    // 名前が一致すればそれを使う。ダメなら物理順のフォールバック。
                    val finalIndex = byName ?: if (isImage) imagePos else null
                    if (isImage) imagePos++
                    if (finalIndex != null && finalIndex < info.entryNames.size) {
                        val bytes = readBoundedBytes(zis, entry.size)
                        if (bytes != null && bytes.isNotEmpty()) {
                            writePage(pageDir, finalIndex, bytes)
                            written++
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    if (nameMatched == 0 && written > 0) {
        logD("名前不一致のため物理順で${written}ページを復元: ${file.name}")
    }
    logD("ストリーミング展開: ${written}ページ / 全${info.entryNames.size}ページ (名前一致=$nameMatched)")
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
                    isLibarchivePathnameWarning(e) -> {
                        // パス名の文字コード変換警告（UTF-16→端末ロケール・code=84/EILSEQ）。
                        // ヘッダは読めており pathnameUtf8() でUTF-8名を取得できるので、中断せず続行する。
                        Log.w("ComicVeil", "${formatLabel}パス名変換警告(code=${e.code}) 続行します")
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

/**
 * libarchiveのパス名変換警告か（UTF-16→端末ロケールへの変換失敗）。
 * Androidはロケール変換が弱いため、UTF-16で名前を持つRARでこの警告が出る。
 * pathnameUtf8()でUTF-8名を取得できるため致命的ではない。code=84 は EILSEQ。
 */
private fun isLibarchivePathnameWarning(e: ArchiveException): Boolean {
    if (e.code == 84) return true
    val msg = e.message?.lowercase() ?: return false
    return msg.contains("pathname") && (msg.contains("convert") || msg.contains("locale"))
}

/** RAR：バージョンに応じて展開する。RAR4はjunrar（日本語名対応）、RAR5はlibarchive。 */
private fun extractRarProgressive(
    file: File,
    targetEntries: List<com.kamneko88.comicveil.data.ArchiveEntryInfo>,
    pageDir: File
) {
    val version = com.kamneko88.comicveil.data.RarSupport.detectVersion(file)
    if (version == com.kamneko88.comicveil.data.RarVersion.RAR4) {
        // RAR4はjunrarで展開（libarchiveはAndroidで日本語名を取得できないため）
        val written = com.kamneko88.comicveil.data.RarSupport.extractPages(
            file, targetEntries, pageDir, MAX_PAGE_BYTES
        )
        File(pageDir, "complete").writeText(written.toString())
        return
    }
    // RAR5（または判定不能）はlibarchiveで展開
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

// ─── 見開き分割：横長ページ判定 ──────────────────────────────────────────────

/**
 * 画像ファイルが横長（幅＞高さ＝見開きの1枚絵）かどうかを判定する。
 * inJustDecodeBounds でヘッダーだけ読むため、ページ全体をデコードするより大幅に軽い。
 */
private fun isWideImageFile(file: File): Boolean {
    return try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outWidth > 0 && opts.outHeight > 0 && opts.outWidth > opts.outHeight
    } catch (e: Exception) {
        false
    }
}

/** バイト列版（PDFページなど、まだファイルに書き出していない画像用） */
private fun isWideImageBytes(bytes: ByteArray): Boolean {
    return try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outWidth > 0 && opts.outHeight > 0 && opts.outWidth > opts.outHeight
    } catch (e: Exception) {
        false
    }
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
                val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
