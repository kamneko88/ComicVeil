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
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import java.io.ByteArrayOutputStream
import java.io.File

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
    val needsPassword: Boolean = false
)

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

    private val _pageLimitEvent = MutableSharedFlow<PageLimitEvent>(replay = 0, extraBufferCapacity = 1)
    val pageLimitEvent: SharedFlow<PageLimitEvent> = _pageLimitEvent.asSharedFlow()

    private var lastSavedPage = 0

    override fun onCleared() {
        super.onCleared()
        val totalPages = _uiState.value.pages.size
        if (totalPages == 0) return
        val status = when {
            lastSavedPage >= totalPages - 1 -> ReadStatus.READ
            lastSavedPage == 0             -> ReadStatus.UNREAD
            else                           -> ReadStatus.READING
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
                    val file = File(filePath)
                    if (file.isDirectory) {
                        loadFromPageDirectory(file)
                    } else {
                        Log.d("ComicVeil", "展開開始: ${file.name} (${file.length()} bytes)")
                        val pages = when (file.extension.lowercase()) {
                            "zip", "cbz" -> extractZip(file, password)
                            "rar", "cbr" -> extractRar(file)
                            "7z"         -> extract7z(file)
                            "pdf"        -> extractPdf(file)
                            else         -> emptyList()
                        }
                        Log.d("ComicVeil", "展開完了: ${pages.size} ページ")
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
                } catch (e: Exception) {
                    Log.e("ComicVeil", "展開エラー: ${e::class.simpleName}: ${e.message}", e)
                    if (isPasswordError(e)) {
                        _uiState.update { it.copy(isLoading = false, needsPassword = true) }
                    } else {
                        _uiState.update { it.copy(error = e.message, isLoading = false) }
                    }
                }
            }
        }
    }

    fun retryWithPassword(password: String) {
        _uiState.update { it.copy(isLoading = true, needsPassword = false) }
        loadFile(password)
    }

    private fun isPasswordError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return e is Zip4jException && (
            msg.contains("password") ||
            msg.contains("wrong password") ||
            msg.contains("encrypt")
        ) || msg.contains("encrypted")
    }

    private suspend fun loadFromPageDirectory(pageDir: File) {
        while (true) {
            val files = pageDir.listFiles { f -> f.name.endsWith(".jpg") }
                ?.sortedBy { it.name } ?: emptyList()
            val isComplete = File(pageDir, "complete").exists()
            val filePaths = files.map { it.absolutePath }
            _uiState.update {
                it.copy(
                    pageFiles          = filePaths,
                    isProgressiveMode  = true,
                    availablePageCount = filePaths.size,
                    isLoading          = filePaths.isEmpty(),
                    isComplete         = isComplete
                )
            }
            if (isComplete) {
                val total = runCatching { File(pageDir, "complete").readText().toInt() }
                    .getOrDefault(filePaths.size)
                _uiState.update { it.copy(totalPageCount = total) }
                break
            }
            kotlinx.coroutines.delay(500)
        }
    }

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

// ─── ZIP（通常） ──────────────────────────────────────────────────────────────

private fun extractZip(file: File, password: String? = null): List<ByteArray> {
    // パスワード付きは zip4j で処理
    if (password != null) return extractZipWithPassword(file, password)

    val pages = mutableListOf<Pair<String, ByteArray>>()
    val encoding = detectZipEncoding(file)
    ZipArchiveInputStream(file.inputStream().buffered(), encoding, true, true).use { zis ->
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

// ─── ZIP（パスワード付き・zip4j使用） ────────────────────────────────────────

private fun extractZipWithPassword(file: File, password: String): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    val zipFile = Zip4jFile(file, password.toCharArray())
    zipFile.fileHeaders
        .filter { header ->
            !header.isDirectory &&
            !header.fileName.substringAfterLast("/").startsWith(".") &&
            !header.fileName.startsWith("__") &&
            header.fileName.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
        }
        .sortedBy { it.fileName.lowercase() }
        .forEach { header ->
            zipFile.getInputStream(header).use { stream ->
                pages.add(Pair(header.fileName, stream.readBytes()))
            }
        }
    return pages.map { it.second }
}

private fun detectZipEncoding(file: File): String {
    return try {
        ZipArchiveInputStream(file.inputStream().buffered(), "UTF-8", true, true).use { zis ->
            val first = zis.nextEntry
            val isUtf8 = (first as? ZipArchiveEntry)?.generalPurposeBit?.usesUTF8ForNames() == true
            if (isUtf8) "UTF-8" else "Shift_JIS"
        }
    } catch (e: Exception) {
        Log.d("ComicVeil", "UTF-8判定失敗、Shift_JISで開く: ${e.message}")
        "Shift_JIS"
    }
}

// ─── RAR ─────────────────────────────────────────────────────────────────────

private fun extractRar(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    Archive(file).use { archive ->
        archive.fileHeaders
            .filter { header ->
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

// ─── 7z ──────────────────────────────────────────────────────────────────────

private fun extract7z(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    SevenZFile.builder()
        .setFile(file)
        .get().use { sevenZFile ->
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    !name.substringAfterLast("/").startsWith(".") &&
                    !name.startsWith("__") &&
                    name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
                ) {
                    pages.add(Pair(name, sevenZFile.getInputStream(entry).readBytes()))
                }
                entry = sevenZFile.nextEntry
            }
        }
    return pages.sortedBy { it.first.lowercase() }.map { it.second }
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
