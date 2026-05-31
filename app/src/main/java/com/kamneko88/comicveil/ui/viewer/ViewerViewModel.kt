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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 閲覧画面のUI状態
 * @param pages             展開済み画像データのリスト
 * @param isLoading         ページ読み込み中フラグ
 * @param error             エラーメッセージ（nullなら正常）
 * @param initialPage       DBから復元した前回ページ（0始まり）
 * @param isSavedPageLoaded DB読み込みが完了したフラグ
 */
data class ViewerUiState(
    val pages: List<ByteArray> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialPage: Int = 0,
    val isSavedPageLoaded: Boolean = false
)

/**
 * 閲覧画面のViewModel
 * - ファイルからページを読み込む（ZIP/RAR）
 * - Roomから前回ページを復元する
 * - ページ変化時にRoomへ自動保存する
 * - 読書状態を自動更新する（開いた→読書中、最終ページ→既読）
 */
class ViewerViewModel(
    application: Application,
    private val filePath: String
) : AndroidViewModel(application) {

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** 既読への自動更新を1回だけ実行するためのフラグ */
    private var readStatusUpdatedToRead = false

    /**
     * ビューワーを閉じるときに呼ばれる
     * 最終ページより前で閉じた場合は「読書中」に戻す
     */
    override fun onCleared() {
        super.onCleared()
        val state      = _uiState.value
        val totalPages = state.pages.size
        if (totalPages == 0) return

        // 最終ページに達していた（既読）場合はそのまま維持
        if (readStatusUpdatedToRead) return

        // それ以外（途中で閉じた）は読書中に戻す
        // onCleared は Main スレッドで呼ばれるため、GlobalScope + IO で実行
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

        // ③ ビューワーを開いた時点で「読書中」に更新
        viewModelScope.launch(Dispatchers.IO) {
            val current = comicFileRepository.getStatus(filePath)
            // 既読は手動設定を尊重してそのまま維持
            if (current != ReadStatus.READ) {
                comicFileRepository.updateStatus(filePath, ReadStatus.READING)
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
            // 読書位置を保存
            progressRepository.saveProgress(filePath, currentPage, totalPages)

            // 最終ページ到達で「既読」に更新（1回だけ）
            val isLastPage = currentPage >= totalPages - 1
            if (isLastPage && !readStatusUpdatedToRead) {
                readStatusUpdatedToRead = true
                comicFileRepository.updateStatus(filePath, ReadStatus.READ)
            }
        }
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