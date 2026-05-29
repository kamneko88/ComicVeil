package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.junrar.Archive
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
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
 * @param pages         展開済み画像データのリスト
 * @param isLoading     ページ読み込み中フラグ
 * @param error         エラーメッセージ（nullなら正常）
 * @param initialPage   DBから復元した前回ページ（0始まり）
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
 *
 * AndroidViewModelを使うのは、DB取得にApplicationContextが必要なため
 */
class ViewerViewModel(
    application: Application,
    private val filePath: String
) : AndroidViewModel(application) {

    private val repository: ReadingProgressRepository

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        // DBとRepositoryの初期化
        val dao = ComicVeilDatabase.getDatabase(application).readingProgressDao()
        repository = ReadingProgressRepository(dao)

        // ① DBから前回ページを読み込む（高速）
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                repository.getProgress(filePath)
            }
            _uiState.update {
                it.copy(
                    initialPage = progress?.currentPage ?: 0,
                    isSavedPageLoaded = true
                )
            }
        }

        // ② ファイルからページを読み込む（重い処理）
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    val pages = when (file.extension.lowercase()) {
                        "zip", "cbz" -> extractZip(file)
                        "rar", "cbr" -> extractRar(file)
                        else -> emptyList()
                    }
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
    }

    /**
     * ページが変わったときに呼ぶ。DBにページ位置を保存する。
     * viewModelScope を使うのでアプリがバックグラウンドに行っても保存を完了できる
     */
    fun savePage(currentPage: Int) {
        val totalPages = _uiState.value.pages.size
        if (totalPages == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveProgress(filePath, currentPage, totalPages)
        }
    }

    /**
     * ViewModelのFactoryパターン
     * filePath をコンストラクタに渡すために必要
     */
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

// ─── ページ展開関数（IO専用・ViewModelのコルーチンから呼ぶ）───────────

/** ZIP/CBZ 展開：ファイル名昇順でソートして返す */
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

/** RAR/CBR 展開：ファイル名昇順でソートして返す */
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