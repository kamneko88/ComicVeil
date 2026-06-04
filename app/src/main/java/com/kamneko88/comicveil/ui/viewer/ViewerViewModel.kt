package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.junrar.Archive
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
import java.io.ByteArrayOutputStream
import java.io.File

data class ViewerUiState(
    val pages: List<ByteArray> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialPage: Int = 0,
    val isSavedPageLoaded: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),      // ブックマーク一覧
    val isCurrentPageBookmarked: Boolean = false      // 現在ページがブックマーク済みか
)

/** 先頭・最終ページ通知イベント */
enum class PageLimitEvent { FIRST, LAST }

class ViewerViewModel(
    application: Application,
    private val filePath: String
) : AndroidViewModel(application) {

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository
    private val bookmarkRepository : BookmarkRepository

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** 先頭・最終ページ通知イベント */
    private val _pageLimitEvent = MutableSharedFlow<PageLimitEvent>(
        replay = 0, extraBufferCapacity = 1
    )
    val pageLimitEvent: SharedFlow<PageLimitEvent> = _pageLimitEvent.asSharedFlow()

    /**
     * 直前のページ番号を記憶する（onCleared で状態を正しく保存するため）
     */
    private var lastSavedPage = 0

    override fun onCleared() {
        super.onCleared()
        val totalPages = _uiState.value.pages.size
        if (totalPages == 0) return

        // 確定仕様に従って閉じたタイミングの状態を保存
        val status = when {
            lastSavedPage >= totalPages - 1 -> ReadStatus.READ     // 最終ページ→既読
            lastSavedPage == 0             -> ReadStatus.UNREAD    // 先頭のまま→未読
            else                           -> ReadStatus.READING   // 途中→読書中
        }
        // onCleared は viewModelScope がキャンセル済みなので runBlocking を使用
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            comicFileRepository.updateStatus(filePath, status)
        }
    }

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())
        bookmarkRepository  = BookmarkRepository(db.bookmarkDao())

        // ① DBから前回ページを読み込む
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                progressRepository.getProgress(filePath)
            }
            val savedPage = progress?.currentPage ?: 0
            lastSavedPage = savedPage
            _uiState.update {
                it.copy(
                    initialPage       = savedPage,
                    isSavedPageLoaded = true
                )
            }
        }

        // ② ファイルからページを読み込む
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    Log.d("ComicVeil", "展開開始: ${file.name} (${file.length()} bytes) exists=${file.exists()}")
                    val pages = when (file.extension.lowercase()) {
                        "zip", "cbz" -> extractZip(file)
                        "rar", "cbr" -> extractRar(file)
                        else         -> emptyList()
                    }
                    Log.d("ComicVeil", "展開完了: ${pages.size} ページ")
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                } catch (e: Exception) {
                    Log.e("ComicVeil", "展開エラー: ${e::class.simpleName}: ${e.message}", e)
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
    }

    /**
     * ページが変わったときに呼ぶ。
     * 確定仕様：
     *   currentPage == 0             → 未読（先頭のまま）
     *   1 〜 totalPages-2          → 読書中
     *   currentPage == totalPages-1  → 既読（最終ページ到達）
     */
    fun savePage(currentPage: Int) {
        val totalPages = _uiState.value.pages.size
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
            // 現在ページのブックマーク状態を更新
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, currentPage)
            _uiState.update { it.copy(isCurrentPageBookmarked = isBookmarked) }
        }
    }

    /** 現在ページのブックマークをトグル（登録↔削除） */
    fun toggleBookmark(currentPage: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.toggleBookmark(filePath, currentPage)
            val bookmarks   = bookmarkRepository.getBookmarks(filePath)
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, currentPage)
            _uiState.update {
                it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked)
            }
        }
    }

    /** ブックマーク一覧を再読み込み */
    fun loadBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val bookmarks = bookmarkRepository.getBookmarks(filePath)
            _uiState.update { it.copy(bookmarks = bookmarks) }
        }
    }

    /** ブックマークを個別に削除 */
    fun deleteBookmark(page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = ComicVeilDatabase.getDatabase(getApplication())
            db.bookmarkDao().deleteBookmark(filePath, page)
            val bookmarks    = bookmarkRepository.getBookmarks(filePath)
            val isBookmarked = bookmarkRepository.isBookmarked(filePath, lastSavedPage)
            _uiState.update { it.copy(bookmarks = bookmarks, isCurrentPageBookmarked = isBookmarked) }
        }
    }

    /** ブックマークを全削除 */
    fun deleteAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.deleteAllBookmarks(filePath)
            _uiState.update { it.copy(bookmarks = emptyList(), isCurrentPageBookmarked = false) }
        }
    }

    /**
     * 先頭・最終ページでさらにページ送りしようとしたときに呼ぶ
     * ViewerScreen のタップゾーン判定から呼び出す
     */
    fun onPageLimitReached(event: PageLimitEvent) {
        _pageLimitEvent.tryEmit(event)
    }

    companion object {
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

// ─── ページ展開関数 ───────────────────────────────────────────────────────────

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

private fun extractZip(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    // Shift-JIS / UTF-8 を判定して Commons Compress で開く
    val encoding = detectZipEncoding(file)
    org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
        file.inputStream().buffered(),
        encoding,
        true,  // useUnicodeExtraFields
        true   // allowStoredEntriesWithDataDescriptor
    ).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory &&
                !name.substringAfterLast("/").startsWith(".") &&
                !name.startsWith("__") &&
                !name.contains("..") &&
                name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
            ) {
                pages.add(Pair(name, zis.readBytes()))
            }
            entry = zis.nextEntry
        }
    }
    return pages.sortedBy { it.first.lowercase() }.map { it.second }
}

/**
 * ZIPファイルの文字コードを簡易判定する。
 * 最初のエントリのUTF-8フラグを確認し、なければShift-JISとみなす。
 */
private fun detectZipEncoding(file: File): String {
    return try {
        org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
            file.inputStream().buffered(),
            "UTF-8",
            true,
            true
        ).use { zis ->
            val first = zis.nextEntry
            val isUtf8 = (first as? org.apache.commons.compress.archivers.zip.ZipArchiveEntry)
                ?.generalPurposeBit?.usesUTF8ForNames() == true
            if (isUtf8) "UTF-8" else "Shift_JIS"
        }
    } catch (e: Exception) {
        Log.d("ComicVeil", "UTF-8判定失敗、Shift_JISで開く: ${e.message}")
        "Shift_JIS"
    }
}

private fun extractRar(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    Archive(file).use { archive ->
        archive.fileHeaders
            .filter { header ->
                // フォルダエントリを除外
                !header.isDirectory &&
                header.fileName.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
            }
            .sortedBy { it.fileName.lowercase() }
            .forEach { header ->
                val outputStream = ByteArrayOutputStream()
                archive.extractFile(header, outputStream)
                pages.add(Pair(header.fileName, outputStream.toByteArray()))
            }
    }
    return pages.map { it.second }
}