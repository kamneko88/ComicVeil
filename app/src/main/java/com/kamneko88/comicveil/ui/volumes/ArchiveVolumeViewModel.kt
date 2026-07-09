package com.kamneko88.comicveil.ui.volumes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.ArchiveScanner
import com.kamneko88.comicveil.data.db.ComicFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import com.kamneko88.comicveil.ui.viewer.ViewerViewModel
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

data class VolumeResumeDialogState(
    val volumeName: String,
    val key: String,
    val savedPage: Int,
    val totalPages: Int
)

/**
 * 1つのアーカイブファイル内にある「巻フォルダ」の一覧を扱うViewModel。
 * 各巻は archivePath + VOLUME_MARKER + volumeName という一意キーで、
 * 通常のコミック1冊と同じように読書位置・ブックマーク・レーティングを個別管理する。
 */
class ArchiveVolumeViewModel(
    application: Application,
    private val archivePath: String
) : AndroidViewModel(application) {

    private val progressRepository : ReadingProgressRepository
    private val comicFileRepository: ComicFileRepository

    val archiveName: String = File(archivePath).nameWithoutExtension

    private val _volumes = MutableStateFlow<List<String>>(emptyList())
    val volumes: StateFlow<List<String>> = _volumes.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _volumeStatuses = MutableStateFlow<Map<String, ReadStatus>>(emptyMap())
    val volumeStatuses: StateFlow<Map<String, ReadStatus>> = _volumeStatuses.asStateFlow()

    private val _dialogState = MutableStateFlow<VolumeResumeDialogState?>(null)
    val dialogState: StateFlow<VolumeResumeDialogState?> = _dialogState.asStateFlow()

    private val _navigateEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())

        viewModelScope.launch(Dispatchers.IO) {
            val scan = ArchiveScanner.scan(File(archivePath))
            val vols = scan.volumes ?: emptyList()
            val statusMap = vols.associateWith { vol ->
                comicFileRepository.getStatus(keyFor(vol))
            }
            _volumes.value        = vols
            _volumeStatuses.value = statusMap
            _isLoading.value      = false
        }
    }

    private fun keyFor(volume: String): String =
        "$archivePath${ViewerViewModel.VOLUME_MARKER_PUBLIC}$volume"

    fun onVolumeTapped(volume: String) {
        viewModelScope.launch {
            val key = keyFor(volume)
            val progress = withContext(Dispatchers.IO) { progressRepository.getProgress(key) }
            if (progress != null && progress.currentPage > 0) {
                _dialogState.value = VolumeResumeDialogState(
                    volumeName = volume,
                    key        = key,
                    savedPage  = progress.currentPage,
                    totalPages = progress.totalPages
                )
            } else {
                _navigateEvent.tryEmit(key)
            }
        }
    }

    fun resumeReading() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        _navigateEvent.tryEmit(state.key)
    }

    fun readFromBeginning() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        viewModelScope.launch(Dispatchers.IO) {
            progressRepository.saveProgress(state.key, 0, state.totalPages)
            _navigateEvent.tryEmit(state.key)
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    companion object {
        fun Factory(application: Application, archivePath: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ArchiveVolumeViewModel(application, archivePath) as T
                }
            }
        }
    }
}
