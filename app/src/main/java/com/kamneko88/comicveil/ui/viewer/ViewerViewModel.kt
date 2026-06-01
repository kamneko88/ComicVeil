package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.junrar.Archive
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
import java.util.zip.ZipFile

data class ViewerUiState(
    val pages: List<ByteArray> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialPage: Int = 0,
    val isSavedPageLoaded: Boolean = false
)

/** 先頭・最終ページ通知イベント */
enum class PageLimitEvent { FIRST, LAST }

class ViewerViewModel(
    application: Application,
    private val filePath: String
) : AndroidViewModel(application) {

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** 先頭・最終ページ通知イベント */
    private val _pageLimitEvent = MutableSharedFlow<PageLimitEvent>(
        replay = 0, extraBufferCapacity = 1
    )
    val pageLimitEvent: SharedFlow<PageLimitEvent> = _pageLimitEvent.asSharedFlow()

    /**
     * 既読への自動更新を1回だけ実行するためのフラグ
     * init の③でDBを確認して初期化する（既読ファイルを開いた場合はtrueで開始）
     */
    private var readStatusUpdatedToRead = false

    override fun onCleared() {
        super.onCleared()
        val totalPages = _uiState.value.pages.size
        if (totalPages == 0) return
        if (readStatusUpdatedToRead) return

        // 途中で閉じた場合は「読書中」に戻す
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            comicFileRepository.updateStatus(filePath, ReadStatus.READING)
        }
    }

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())

        // ① DBから前回ページを読み込む
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                progressRepository.getProgress(filePath)
            }
            _uiState.update {
                it.copy(
                    initialPage       = progress?.currentPage ?: 0,
                    isSavedPageLoaded = true
                )
            }
        }

        // ② ファイルからページを読み込む
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    val pages = when (file.extension.lowercase()) {
                        "zip", "cbz" -> extractZip(file)
                        "rar", "cbr" -> extractRar(file)
                        else         -> emptyList()
                    }
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }

        // ③ 読書状態を更新する
        viewModelScope.launch(Dispatchers.IO) {
            val current = comicFileRepository.getStatus(filePath)
            when (current) {
                ReadStatus.READ -> {
                    // 既読ファイルを開いた場合：フラグをtrueにして既読を維持
                    // onCleared で誤って「読書中」に戻さないようにする
                    readStatusUpdatedToRead = true
                }
                else -> {
                    // 未読・読書中は「読書中」に更新
                    comicFileRepository.updateStatus(filePath, ReadStatus.READING)
                }
            }
        }
    }

    /**
     * ページが変わったときに呼ぶ。
     * - DBにページ位置を保存する
     * - 最終ページに到達したら「既読」に自動更新する
     */
    fun savePage(currentPage: Int) {
        val totalPages = _uiState.value.pages.size
        if (totalPages == 0) return

        viewModelScope.launch(Dispatchers.IO) {
            progressRepository.saveProgress(filePath, currentPage, totalPages)

            val isLastPage = currentPage >= totalPages - 1
            if (isLastPage && !readStatusUpdatedToRead) {
                readStatusUpdatedToRead = true
                comicFileRepository.updateStatus(filePath, ReadStatus.READ)
            }
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

private fun extractZip(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { entry ->
                entry.name.substringAfterLast(".").lowercase() in
                        setOf("jpg", "jpeg", "png", "webp")
            }
            .sortedBy { it.name.lowercase() }
            .forEach { entry ->
                zip.getInputStream(entry).use { stream ->
                    pages.add(Pair(entry.name, stream.readBytes()))
                }
            }
    }
    return pages.map { it.second }
}

private fun extractRar(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    Archive(file).use { archive ->
        archive.fileHeaders
            .filter { header ->
                header.fileName.substringAfterLast(".").lowercase() in
                        setOf("jpg", "jpeg", "png", "webp")
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