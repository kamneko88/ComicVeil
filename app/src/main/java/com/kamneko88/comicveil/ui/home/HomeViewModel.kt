package com.kamneko88.comicveil.ui.home

import android.os.Environment
import androidx.lifecycle.ViewModel
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.LocalFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class HomeViewModel : ViewModel() {

    private val repository = LocalFileRepository()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files

    private val _currentPath = MutableStateFlow<File?>(null)
    val currentPath: StateFlow<File?> = _currentPath

    fun loadInitialFolder() {
        val initialFolder = Environment.getExternalStorageDirectory()
        loadFolder(initialFolder)
    }

    fun loadFolder(folder: File) {
        _currentPath.value = folder
        _files.value = repository.getFiles(folder)
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
}