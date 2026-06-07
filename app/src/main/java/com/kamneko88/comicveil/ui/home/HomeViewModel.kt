package com.kamneko88.comicveil.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.db.ColorLabel
import com.kamneko88.comicveil.data.db.ComicFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.NasServerPrefs
import com.kamneko88.comicveil.data.nas.SmbRepository
import com.kamneko88.comicveil.ui.transfer.TransferViewModel
import kotlinx.coroutines.Job
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

sealed class ViewLocation {
    object Home : ViewLocation()
    data class LocalFolder(val folder: File) : ViewLocation()
    data class NasFolder(val server: NasServer, val path: String) : ViewLocation() {
        val displayTitle: String get() =
            if (path.isEmpty()) server.displayName else path.substringAfterLast("/")
    }
}

data class ResumeDialogState(
    val fileItem: FileItem,
    val savedPage: Int,
    val totalPages: Int
)

/** STRモードのダウンロード進捗 */
data class DownloadProgress(
    val fileName: String,
    val downloaded: Long,
    val total: Long
) {
    val fraction: Float? get() = if (total > 0) downloaded.toFloat() / total else null

    val progressText: String get() = when {
        total > 0 -> "${formatBytes(downloaded)} / ${formatBytes(total)}"
        else      -> formatBytes(downloaded)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
        else                    -> "$bytes B"
    }
}

/** ファイル情報ポップアップの状態 */
data class FileInfoState(
    val fileItem: FileItem,
    val title: String,
    val author: String?,
    val status: ReadStatus,
    val rating: Int = 0,
    val colorLabel: Int = 0
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val fileRepository      = LocalFileRepository()
    private val progressRepository  : ReadingProgressRepository
    private val comicFileRepository : ComicFileRepository
    private val smbRepository       = SmbRepository()
    private val nasServerPrefs      = NasServerPrefs(application)
    val appPrefs                    = AppPrefs(application)

    /** 現在のHomeフォルダ（設定に応じて動的に解決）*/
    private val homeFolder: File
        get() = appPrefs.resolveHomeFolder(getApplication())

    /** DL保存先フォルダ（設定に応じて動的に解決）*/
    private val downloadFolder: File
        get() = appPrefs.resolveDownloadFolder(getApplication())

    private val _currentLocation = MutableStateFlow<ViewLocation>(ViewLocation.Home)
    val currentLocation: StateFlow<ViewLocation> = _currentLocation.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _nasServers = MutableStateFlow<List<NasServer>>(emptyList())
    val nasServers: StateFlow<List<NasServer>> = _nasServers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** STRモードのダウンロード進捗（null = 非表示）*/
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _nasError = MutableStateFlow<String?>(null)
    val nasError: StateFlow<String?> = _nasError.asStateFlow()

    /** 再開ダイアログの状態（null = 非表示）*/
    private val _dialogState = MutableStateFlow<ResumeDialogState?>(null)
    val dialogState: StateFlow<ResumeDialogState?> = _dialogState.asStateFlow()

    /** ファイル情報ポップアップの状態（null = 非表示）*/
    private val _fileInfoState = MutableStateFlow<FileInfoState?>(null)
    val fileInfoState: StateFlow<FileInfoState?> = _fileInfoState.asStateFlow()

    /**
     * ファイルパス→ReadStatus のマップ（StateFlow）
     * Ⓘポップアップで変更した際にリストへ即時反映するために使用
     */
    private val _fileStatuses = MutableStateFlow<Map<String, ReadStatus>>(emptyMap())
    val fileStatuses: StateFlow<Map<String, ReadStatus>> = _fileStatuses.asStateFlow()

    /** ファイルパス→ComicFileのマップ（レーティング・カラーラベルをリストに即時反映） */
    private val _fileMetaMap = MutableStateFlow<Map<String, com.kamneko88.comicveil.data.db.ComicFile>>(emptyMap())
    val fileMetaMap: StateFlow<Map<String, com.kamneko88.comicveil.data.db.ComicFile>> = _fileMetaMap.asStateFlow()

    private val _navigateEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    /** TransferScreen への遷移イベント */
    private val _navigateToTransfer = MutableSharedFlow<String?>(replay = 0, extraBufferCapacity = 1)
    val navigateToTransfer: SharedFlow<String?> = _navigateToTransfer.asSharedFlow()

    /**
     * DL/STRモード
     * true  = STR（ストリーミング）：アプリキャッシュに一時保存・デフォルト
     * false = DL（ダウンロード）  ：Downloads/ComicVeil/ に永続保存
     */
    private val _isStreamingMode = MutableStateFlow(true)
    val isStreamingMode: StateFlow<Boolean> = _isStreamingMode.asStateFlow()

    /** STRモードの実行中ダウンロードJob */
    private var downloadJob: Job? = null

    /** TransferViewModel への参照（DLモード時に委譲）*/
    var transferViewModel: TransferViewModel? = null

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())
        refreshNasServers()
    }

    // ─── DL/STRモード ────────────────────────────────────────────────────

    fun toggleMode() {
        _isStreamingMode.value = !_isStreamingMode.value
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

    fun loadInitialFolder() {
        if (_currentLocation.value !is ViewLocation.Home) return
        _files.value = fileRepository.getFiles(homeFolder)
    }

    fun loadFolder(folder: File) {
        val location = if (folder.absolutePath == homeFolder.absolutePath) {
            ViewLocation.Home
        } else {
            ViewLocation.LocalFolder(folder)
        }
        _currentLocation.value = location
        _files.value = fileRepository.getFiles(folder)
        loadFileStatuses(_files.value)
    }

    /** Homeフォルダルートに戻る */
    fun navigateToRoot() {
        _currentLocation.value = ViewLocation.Home
        _files.value = fileRepository.getFiles(homeFolder)
        loadFileStatuses(_files.value)
    }

    /** Home画面に戻る（NASサーバー一覧も表示） */
    fun navigateToHome() {
        _currentLocation.value = ViewLocation.Home
        _files.value = fileRepository.getFiles(homeFolder)
        loadFileStatuses(_files.value)
    }

    // ─── NASナビゲーション ────────────────────────────────────────────────

    fun navigateToNas(server: NasServer, nasPath: String = "") {
        _currentLocation.value = ViewLocation.NasFolder(server, nasPath)
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _files.value = smbRepository.listDirectory(server, nasPath)
                loadFileStatuses(_files.value)
            } catch (e: Exception) {
                _nasError.value = "接続に失敗しました\n${e.message}"
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── バック処理 ──────────────────────────────────────────────────────

    fun navigateUp(): Boolean {
        return when (val loc = _currentLocation.value) {
            is ViewLocation.Home -> false

            is ViewLocation.LocalFolder -> {
                val parent = loc.folder.parentFile
                if (parent != null) loadFolder(parent) else {
                    _currentLocation.value = ViewLocation.Home
                    _files.value = fileRepository.getFiles(homeFolder)
                    loadFileStatuses(_files.value)
                }
                true
            }

            is ViewLocation.NasFolder -> {
                if (loc.path.isEmpty()) {
                    _currentLocation.value = ViewLocation.Home
                    _files.value = fileRepository.getFiles(homeFolder)
                    loadFileStatuses(_files.value)
                } else {
                    val parentPath = loc.path.substringBeforeLast("/", "")
                    navigateToNas(loc.server, parentPath)
                }
                true
            }
        }
    }

    // ─── ファイル状態の一括読み込み ─────────────────────────────────────

    /**
     * ファイルリストの読書状態をDBから一括取得して _fileStatuses に反映する
     * フォルダ移動・画面復帰時に呼ぶ
     */
    fun loadFileStatuses(fileList: List<FileItem> = _files.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val comics = fileList.filter { it.isComic }
            val statusMap = comics.associate { it.path to comicFileRepository.getStatus(it.path) }
            val metaMap   = comics.mapNotNull { comicFileRepository.getComicFile(it.path) }
                                  .associateBy { it.filePath }
            _fileStatuses.value = statusMap
            _fileMetaMap.value  = metaMap
        }
    }

    /** 単一ファイルの状態を _fileStatuses に即時反映する */
    private fun updateFileStatusInMap(filePath: String, status: ReadStatus) {
        _fileStatuses.value = _fileStatuses.value.toMutableMap().also {
            it[filePath] = status
        }
    }

    // ─── ファイルタップ ──────────────────────────────────────────────────

    fun onComicTapped(fileItem: FileItem) {
        viewModelScope.launch {
            if (fileItem.isNas) {
                if (_isStreamingMode.value) {
                    openNasComicStr(fileItem)
                } else {
                    openNasComicDl(fileItem)
                }
            } else {
                val progress = withContext(Dispatchers.IO) {
                    progressRepository.getProgress(fileItem.path)
                }
                if (progress != null && progress.currentPage > 0) {
                    _dialogState.value = ResumeDialogState(
                        fileItem, progress.currentPage, progress.totalPages
                    )
                } else {
                    _navigateEvent.tryEmit(fileItem.path)
                }
            }
        }
    }

    // ─── ファイル情報ポップアップ ─────────────────────────────────────────

    /** Ⓘボタンタップ：ファイル情報ポップアップを開く */
    fun openFileInfo(fileItem: FileItem) {
        viewModelScope.launch {
            val (title, author) = fileRepository.parseFileName(fileItem.name)
            val status = comicFileRepository.getStatus(fileItem.path)
            val comicFile = withContext(Dispatchers.IO) {
                comicFileRepository.getComicFile(fileItem.path)
            }
            _fileInfoState.value = FileInfoState(
                fileItem   = fileItem,
                title      = title,
                author     = author,
                status     = status,
                rating     = comicFile?.rating ?: 0,
                colorLabel = comicFile?.colorLabel ?: 0
            )
        }
    }

    /** ファイル情報ポップアップで読書状態を変更 */
    fun updateFileStatus(fileItem: FileItem, status: ReadStatus) {
        viewModelScope.launch {
            comicFileRepository.updateStatus(fileItem.path, status)
            if (status == ReadStatus.UNREAD) {
                withContext(Dispatchers.IO) {
                    progressRepository.saveProgress(fileItem.path, 0, 0)
                }
            }
            _fileInfoState.value = _fileInfoState.value?.copy(status = status)
            updateFileStatusInMap(fileItem.path, status)
        }
    }

    /** ファイル情報ポップアップでレーティングを変更 */
    fun updateRating(fileItem: FileItem, rating: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            comicFileRepository.updateRating(fileItem.path, rating)
        }
        _fileInfoState.value = _fileInfoState.value?.copy(rating = rating)
        _fileMetaMap.value = _fileMetaMap.value.toMutableMap().also { map ->
            val current = map[fileItem.path]
            if (current != null) map[fileItem.path] = current.copy(rating = rating)
        }
    }

    /** ファイル情報ポップアップでカラーラベルを変更 */
    fun updateColorLabel(fileItem: FileItem, colorLabel: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            comicFileRepository.updateColorLabel(fileItem.path, colorLabel)
        }
        _fileInfoState.value = _fileInfoState.value?.copy(colorLabel = colorLabel)
        _fileMetaMap.value = _fileMetaMap.value.toMutableMap().also { map ->
            val current = map[fileItem.path]
            if (current != null) map[fileItem.path] = current.copy(colorLabel = colorLabel)
        }
    }

    /** ファイル情報ポップアップを閉じる */
    fun dismissFileInfo() {
        _fileInfoState.value = null
    }

    // ─── STR/DLモード内部処理 ────────────────────────────────────────────

    private suspend fun openNasComicStr(fileItem: FileItem) {
        val ext      = fileItem.name.substringAfterLast(".")
        val destFile = File(
            File(getApplication<Application>().cacheDir, "nas_cache"),
            "nas_${fileItem.nasPath.hashCode()}.$ext"
        )

        if (destFile.exists() && destFile.length() > 0) {
            val progress = withContext(Dispatchers.IO) {
                progressRepository.getProgress(destFile.absolutePath)
            }
            if (progress != null && progress.currentPage > 0) {
                _dialogState.value = ResumeDialogState(
                    fileItem.copy(file = destFile),
                    progress.currentPage,
                    progress.totalPages
                )
            } else {
                _navigateEvent.tryEmit(destFile.absolutePath)
            }
            return
        }

        _dialogState.value = ResumeDialogState(
            fileItem   = fileItem,
            savedPage  = 0,
            totalPages = 0
        )
    }

    private fun openNasComicDl(fileItem: FileItem) {
        transferViewModel?.enqueue(fileItem, isStreaming = false)
        val folderName = (currentLocation.value as? ViewLocation.NasFolder)?.displayTitle ?: ""
        _navigateToTransfer.tryEmit(folderName)
    }

    fun startStrDownload(fileItem: FileItem) {
        val ext      = fileItem.name.substringAfterLast(".")
        val destFile = File(
            File(getApplication<Application>().cacheDir, "nas_cache"),
            "nas_${fileItem.nasPath.hashCode()}.$ext"
        )
        val server = fileItem.nasServer ?: return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadProgress.value = DownloadProgress(
                fileName   = fileItem.name,
                downloaded = 0L,
                total      = -1L
            )
            try {
                smbRepository.downloadFile(
                    server     = server,
                    nasPath    = fileItem.nasPath,
                    destFile   = destFile,
                    onProgress = { downloaded, total ->
                        _downloadProgress.value = DownloadProgress(
                            fileName   = fileItem.name,
                            downloaded = downloaded,
                            total      = total
                        )
                    }
                )
                _navigateEvent.tryEmit(destFile.absolutePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { destFile.delete() }
            } catch (e: Exception) {
                _nasError.value = "接続に失敗しました\n${e.message}"
                runCatching { destFile.delete() }
            } finally {
                _downloadProgress.value = null
                downloadJob = null
            }
        }
    }

    fun cancelStrDownload() {
        downloadJob?.cancel()
    }

    fun openTransferScreen() {
        val folderName = (currentLocation.value as? ViewLocation.NasFolder)?.displayTitle ?: ""
        _navigateToTransfer.tryEmit(folderName)
    }

    // ─── ダイアログ操作 ──────────────────────────────────────────────────

    fun resumeReading() {
        val state = _dialogState.value ?: return
        _dialogState.value = null

        if (state.fileItem.isNas && _isStreamingMode.value) {
            startStrDownload(state.fileItem)
        } else {
            _navigateEvent.tryEmit(state.fileItem.path)
        }
    }

    fun readFromBeginning() {
        val state = _dialogState.value ?: return
        _dialogState.value = null

        if (state.fileItem.isNas && _isStreamingMode.value) {
            startStrDownload(state.fileItem)
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    progressRepository.saveProgress(state.fileItem.path, 0, state.totalPages)
                }
                _navigateEvent.tryEmit(state.fileItem.path)
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    // ─── ローカルファイル削除 ──────────────────────────────────────────────

    /** ローカルファイルを削除し、DBの読書状態・読書位置も消去する
     * @return 削除成功になったファイル数
     */
    fun deleteLocalFiles(fileItems: List<FileItem>): Int {
        var count = 0
        viewModelScope.launch(Dispatchers.IO) {
            fileItems.forEach { item ->
                val file = item.file ?: return@forEach
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (deleted) {
                    comicFileRepository.delete(item.path)
                    progressRepository.deleteProgress(item.path)
                    count++
                }
            }
            // 削除後にファイルリストを更新
            val loc = _currentLocation.value
            val folder = when (loc) {
                is ViewLocation.Home        -> homeFolder
                is ViewLocation.LocalFolder -> loc.folder
                else                        -> return@launch
            }
            withContext(Dispatchers.Main) {
                _files.value = fileRepository.getFiles(folder)
                loadFileStatuses(_files.value)
            }
        }
        return count
    }

    // ─── キャッシュ管理 ─────────────────────────────────────────────────

    /** NAS STRキャッシュフォルダを参照 */
    private fun nasCacheDir(): File =
        File(getApplication<Application>().cacheDir, "nas_cache")

    /** 指定ファイルのSTRキャッシュが存在するか確認 */
    fun isNasCached(fileItem: FileItem): Boolean {
        if (!fileItem.isNas) return false
        val ext  = fileItem.name.substringAfterLast(".")
        val file = File(nasCacheDir(), "nas_${fileItem.nasPath.hashCode()}.$ext")
        return file.exists() && file.length() > 0
    }

    /** STRキャッシュを全削除してサイズ（bytes）を返す */
    fun clearNasCache(): Long {
        val dir = nasCacheDir()
        var totalBytes = 0L
        dir.listFiles()?.forEach {
            totalBytes += it.length()
            it.delete()
        }
        return totalBytes
    }

    /** サムネイルキャッシュを全削除してサイズ（bytes）を返す */
    fun clearThumbnailCache(thumbnailCacheDir: File): Long {
        var totalBytes = 0L
        thumbnailCacheDir.listFiles()?.forEach {
            totalBytes += it.length()
            it.delete()
        }
        return totalBytes
    }
}