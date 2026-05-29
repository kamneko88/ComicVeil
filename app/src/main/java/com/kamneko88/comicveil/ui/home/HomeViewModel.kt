package com.kamneko88.comicveil.ui.home

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「再開 or 最初から」ダイアログの状態
 * @param fileItem    タップされたファイル
 * @param savedPage   DBに保存されているページ番号（0始まり）
 * @param totalPages  総ページ数
 */
data class ResumeDialogState(
    val fileItem: FileItem,
    val savedPage: Int,
    val totalPages: Int
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val fileRepository = LocalFileRepository()
    private val progressRepository: ReadingProgressRepository

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _currentPath = MutableStateFlow<File?>(null)
    val currentPath: StateFlow<File?> = _currentPath.asStateFlow()

    /** ダイアログの表示状態。null のときは非表示 */
    private val _dialogState = MutableStateFlow<ResumeDialogState?>(null)
    val dialogState: StateFlow<ResumeDialogState?> = _dialogState.asStateFlow()

    /**
     * ビューワーへの遷移イベント（ファイルパスを流す）
     * SharedFlow を使うことで「一度だけ発火」を保証する
     */
    private val _navigateEvent = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    init {
        val dao = ComicVeilDatabase.getDatabase(application).readingProgressDao()
        progressRepository = ReadingProgressRepository(dao)
    }

    fun loadInitialFolder() {
        val downloadFolder = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        loadFolder(downloadFolder)
    }

    fun loadFolder(folder: File) {
        _currentPath.value = folder
        _files.value = fileRepository.getFiles(folder)
    }

    fun navigateUp(): Boolean {
        val parent = _currentPath.value?.parentFile
        return if (parent != null) {
            loadFolder(parent)
            true
        } else {
            false
        }
    }

    /**
     * コミックファイルがタップされたときの処理
     * DBに読書履歴があればダイアログを出し、なければ直接開く
     */
    fun onComicTapped(fileItem: FileItem) {
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                progressRepository.getProgress(fileItem.path)
            }
            if (progress != null && progress.currentPage > 0) {
                // 読書履歴あり → ダイアログ表示
                _dialogState.value = ResumeDialogState(
                    fileItem = fileItem,
                    savedPage = progress.currentPage,
                    totalPages = progress.totalPages
                )
            } else {
                // 読書履歴なし → そのまま開く
                _navigateEvent.tryEmit(fileItem.path)
            }
        }
    }

    /** 「○○ページから再開」が選ばれた */
    fun resumeReading() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        _navigateEvent.tryEmit(state.fileItem.path)
    }

    /** 「最初から読む」が選ばれた：DBのページを0にリセットしてから開く */
    fun readFromBeginning() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                progressRepository.saveProgress(
                    filePath = state.fileItem.path,
                    currentPage = 0,
                    totalPages = state.totalPages
                )
            }
            _navigateEvent.tryEmit(state.fileItem.path)
        }
    }

    /** ダイアログを閉じる（キャンセル） */
    fun dismissDialog() {
        _dialogState.value = null
    }
}