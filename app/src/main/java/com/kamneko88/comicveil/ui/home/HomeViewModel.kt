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

    private val _dialogState = MutableStateFlow<ResumeDialogState?>(null)
    val dialogState: StateFlow<ResumeDialogState?> = _dialogState.asStateFlow()

    private val _navigateEvent = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    init {
        val dao = ComicVeilDatabase.getDatabase(application).readingProgressDao()
        progressRepository = ReadingProgressRepository(dao)
    }

    /**
     * 初期フォルダ（Downloads）をロードする。
     * すでにいずれかのフォルダを表示中なら何もしない。
     * → ViewerScreenから戻ったときに loadInitialFolder が再呼び出しされても
     *   現在のフォルダ位置がリセットされなくなる。
     */
    fun loadInitialFolder() {
        if (_currentPath.value != null) return  // ★ 既に表示中のフォルダがあればスキップ
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

    fun onComicTapped(fileItem: FileItem) {
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                progressRepository.getProgress(fileItem.path)
            }
            if (progress != null && progress.currentPage > 0) {
                _dialogState.value = ResumeDialogState(
                    fileItem = fileItem,
                    savedPage = progress.currentPage,
                    totalPages = progress.totalPages
                )
            } else {
                _navigateEvent.tryEmit(fileItem.path)
            }
        }
    }

    fun resumeReading() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        _navigateEvent.tryEmit(state.fileItem.path)
    }

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

    fun dismissDialog() {
        _dialogState.value = null
    }
}