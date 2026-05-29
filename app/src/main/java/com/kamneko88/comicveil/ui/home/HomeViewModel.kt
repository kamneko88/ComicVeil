package com.kamneko88.comicveil.ui.home

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.NasServerPrefs
import com.kamneko88.comicveil.data.nas.SmbRepository
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

// ─── 画面位置を表すシールドクラス ──────────────────────────────────────────

sealed class ViewLocation {
    /** ホーム（Downloadsルート + NASサーバー一覧） */
    object Home : ViewLocation()

    /** ローカルサブフォルダ（Downloads配下） */
    data class LocalFolder(val folder: File) : ViewLocation()

    /** NASフォルダ */
    data class NasFolder(val server: NasServer, val path: String) : ViewLocation() {
        /** 表示用タイトル */
        val displayTitle: String get() =
            if (path.isEmpty()) server.displayName else path.substringAfterLast("/")
    }
}

data class ResumeDialogState(
    val fileItem: FileItem,
    val savedPage: Int,
    val totalPages: Int
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    // ─── リポジトリ ────────────────────────────────────────────────────────
    private val fileRepository     = LocalFileRepository()
    private val progressRepository : ReadingProgressRepository
    private val smbRepository      = SmbRepository()
    private val nasServerPrefs     = NasServerPrefs(application)

    private val downloadsFolder = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    )

    // ─── 状態 ─────────────────────────────────────────────────────────────
    private val _currentLocation = MutableStateFlow<ViewLocation>(ViewLocation.Home)
    val currentLocation: StateFlow<ViewLocation> = _currentLocation.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _nasServers = MutableStateFlow<List<NasServer>>(emptyList())
    val nasServers: StateFlow<List<NasServer>> = _nasServers.asStateFlow()

    /** NASのロード中フラグ */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** NASファイルダウンロード中のメッセージ（nullなら非表示） */
    private val _downloadingMessage = MutableStateFlow<String?>(null)
    val downloadingMessage: StateFlow<String?> = _downloadingMessage.asStateFlow()

    /** NAS接続エラー（nullなら問題なし） */
    private val _nasError = MutableStateFlow<String?>(null)
    val nasError: StateFlow<String?> = _nasError.asStateFlow()

    /** 再開ダイアログ（ローカルファイル用） */
    private val _dialogState = MutableStateFlow<ResumeDialogState?>(null)
    val dialogState: StateFlow<ResumeDialogState?> = _dialogState.asStateFlow()

    /** ビューワーへの遷移イベント */
    private val _navigateEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    init {
        val dao = ComicVeilDatabase.getDatabase(application).readingProgressDao()
        progressRepository = ReadingProgressRepository(dao)
        refreshNasServers()
    }

    // ─── NASサーバー管理 ──────────────────────────────────────────────────

    fun refreshNasServers() {
        _nasServers.value = nasServerPrefs.getServers()
    }

    fun addNasServer(server: NasServer) {
        nasServerPrefs.saveServer(server)
        refreshNasServers()
    }

    fun deleteNasServer(id: String) {
        nasServerPrefs.deleteServer(id)
        refreshNasServers()
    }

    fun clearNasError() {
        _nasError.value = null
    }

    // ─── ローカルナビゲーション ───────────────────────────────────────────

    /**
     * 初期フォルダ（Downloads）を読み込む。
     * すでにどこかのフォルダを表示中なら何もしない（ViewerScreenから戻ったときの誤リセット防止）。
     */
    fun loadInitialFolder() {
        if (_currentLocation.value !is ViewLocation.Home) return
        _files.value = fileRepository.getFiles(downloadsFolder)
    }

    fun loadFolder(folder: File) {
        val location = if (folder.absolutePath == downloadsFolder.absolutePath) {
            ViewLocation.Home
        } else {
            ViewLocation.LocalFolder(folder)
        }
        _currentLocation.value = location
        _files.value = fileRepository.getFiles(folder)
    }

    // ─── NASナビゲーション ────────────────────────────────────────────────

    fun navigateToNas(server: NasServer, nasPath: String = "") {
        _currentLocation.value = ViewLocation.NasFolder(server, nasPath)
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _files.value = smbRepository.listDirectory(server, nasPath)
            } catch (e: Exception) {
                _nasError.value = "接続に失敗しました\n${e.message}"
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── 共通バック処理 ──────────────────────────────────────────────────

    fun navigateUp(): Boolean {
        return when (val loc = _currentLocation.value) {
            is ViewLocation.Home -> false

            is ViewLocation.LocalFolder -> {
                // 親フォルダへ（Downloadsルートに戻ったらHome扱い）
                val parent = loc.folder.parentFile
                if (parent != null) loadFolder(parent) else {
                    _currentLocation.value = ViewLocation.Home
                    _files.value = fileRepository.getFiles(downloadsFolder)
                }
                true
            }

            is ViewLocation.NasFolder -> {
                if (loc.path.isEmpty()) {
                    // NASルート → ホームへ
                    _currentLocation.value = ViewLocation.Home
                    _files.value = fileRepository.getFiles(downloadsFolder)
                } else {
                    // 親NASパスへ
                    val parentPath = loc.path.substringBeforeLast("/", "")
                    navigateToNas(loc.server, parentPath)
                }
                true
            }
        }
    }

    // ─── ファイルタップ ──────────────────────────────────────────────────

    fun onComicTapped(fileItem: FileItem) {
        viewModelScope.launch {
            if (fileItem.isNas) {
                // NASファイル：キャッシュにダウンロードしてからビューワーへ
                openNasComic(fileItem)
            } else {
                // ローカルファイル：DB確認→ダイアログ or 直接ビューワーへ
                val progress = withContext(Dispatchers.IO) {
                    progressRepository.getProgress(fileItem.path)
                }
                if (progress != null && progress.currentPage > 0) {
                    _dialogState.value = ResumeDialogState(
                        fileItem   = fileItem,
                        savedPage  = progress.currentPage,
                        totalPages = progress.totalPages
                    )
                } else {
                    _navigateEvent.tryEmit(fileItem.path)
                }
            }
        }
    }

    private suspend fun openNasComic(fileItem: FileItem) {
        val server = fileItem.nasServer ?: return
        val ext    = fileItem.name.substringAfterLast(".")
        // キャッシュファイルのパスは決定的（同じNASファイルは同じキャッシュを再利用）
        val cacheDir = File(getApplication<Application>().cacheDir, "nas_cache")
        val destFile = File(cacheDir, "nas_${fileItem.nasPath.hashCode()}.$ext")

        if (destFile.exists() && destFile.length() > 0) {
            // キャッシュヒット → そのまま開く
            _navigateEvent.tryEmit(destFile.absolutePath)
            return
        }

        _downloadingMessage.value = "ダウンロード中…\n${fileItem.name}"
        try {
            smbRepository.downloadFile(server, fileItem.nasPath, destFile)
            _navigateEvent.tryEmit(destFile.absolutePath)
        } catch (e: Exception) {
            _nasError.value = "ダウンロードに失敗しました\n${e.message}"
        } finally {
            _downloadingMessage.value = null
        }
    }

    // ─── ダイアログ操作 ──────────────────────────────────────────────────

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
                progressRepository.saveProgress(state.fileItem.path, 0, state.totalPages)
            }
            _navigateEvent.tryEmit(state.fileItem.path)
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }
}