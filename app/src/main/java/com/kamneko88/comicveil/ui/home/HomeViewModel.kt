package com.kamneko88.comicveil.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.SortPrefs
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
import net.lingala.zip4j.ZipFile as Zip4jFile
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
    val sortPrefs                   = SortPrefs(application)

    private val homeFolder: File
        get() = appPrefs.resolveHomeFolder(getApplication())

    private val downloadFolder: File
        get() = appPrefs.resolveDownloadFolder(getApplication())

    private val _currentLocation = MutableStateFlow<ViewLocation>(ViewLocation.Home)
    val currentLocation: StateFlow<ViewLocation> = _currentLocation.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())

    private val _displayFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _displayFiles.asStateFlow()

    private val _sortKey     = MutableStateFlow(sortPrefs.sortKey)
    val sortKey: StateFlow<SortPrefs.SortKey> = _sortKey.asStateFlow()

    private val _ascending   = MutableStateFlow(sortPrefs.ascending)
    val ascending: StateFlow<Boolean> = _ascending.asStateFlow()

    private val _folderOrder = MutableStateFlow(sortPrefs.folderOrder)
    val folderOrder: StateFlow<SortPrefs.FolderOrder> = _folderOrder.asStateFlow()

    private val _statusFilter     = MutableStateFlow(sortPrefs.statusFilter)
    val statusFilter: StateFlow<Set<String>> = _statusFilter.asStateFlow()

    private val _colorLabelFilter = MutableStateFlow(sortPrefs.colorLabelFilter)
    val colorLabelFilter: StateFlow<Set<String>> = _colorLabelFilter.asStateFlow()

    val hasActiveFilter: Boolean
        get() = _statusFilter.value.isNotEmpty() || _colorLabelFilter.value.isNotEmpty()

    private val _nasServers = MutableStateFlow<List<NasServer>>(emptyList())
    val nasServers: StateFlow<List<NasServer>> = _nasServers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _nasError = MutableStateFlow<String?>(null)
    val nasError: StateFlow<String?> = _nasError.asStateFlow()

    private val _dialogState = MutableStateFlow<ResumeDialogState?>(null)
    val dialogState: StateFlow<ResumeDialogState?> = _dialogState.asStateFlow()

    private val _fileInfoState = MutableStateFlow<FileInfoState?>(null)
    val fileInfoState: StateFlow<FileInfoState?> = _fileInfoState.asStateFlow()

    private val _fileStatuses = MutableStateFlow<Map<String, ReadStatus>>(emptyMap())
    val fileStatuses: StateFlow<Map<String, ReadStatus>> = _fileStatuses.asStateFlow()

    private val _fileMetaMap = MutableStateFlow<Map<String, com.kamneko88.comicveil.data.db.ComicFile>>(emptyMap())
    val fileMetaMap: StateFlow<Map<String, com.kamneko88.comicveil.data.db.ComicFile>> = _fileMetaMap.asStateFlow()

    private val _navigateEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val navigateEvent: SharedFlow<String> = _navigateEvent.asSharedFlow()

    private val _navigateToTransfer = MutableSharedFlow<String?>(replay = 0, extraBufferCapacity = 1)
    val navigateToTransfer: SharedFlow<String?> = _navigateToTransfer.asSharedFlow()

    private val _isStreamingMode = MutableStateFlow(true)
    val isStreamingMode: StateFlow<Boolean> = _isStreamingMode.asStateFlow()

    private val _dlSelectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val dlSelectedPaths: StateFlow<Set<String>> = _dlSelectedPaths.asStateFlow()

    private var downloadJob: Job? = null

    var transferViewModel: TransferViewModel? = null

    init {
        val db = ComicVeilDatabase.getDatabase(application)
        progressRepository  = ReadingProgressRepository(db.readingProgressDao())
        comicFileRepository = ComicFileRepository(db.comicFileDao())
        refreshNasServers()

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _files, _sortKey, _ascending, _folderOrder,
                _statusFilter, _colorLabelFilter, _fileStatuses, _fileMetaMap
            ) { arr ->
                @Suppress("UNCHECKED_CAST")
                val rawFiles     = arr[0] as List<FileItem>
                val key          = arr[1] as SortPrefs.SortKey
                val asc          = arr[2] as Boolean
                val order        = arr[3] as SortPrefs.FolderOrder
                val statusF      = arr[4] as Set<String>
                val colorF       = arr[5] as Set<String>
                val statuses     = arr[6] as Map<String, com.kamneko88.comicveil.data.db.ReadStatus>
                val metas        = arr[7] as Map<String, com.kamneko88.comicveil.data.db.ComicFile>
                applySort(rawFiles, key, asc, order, statusF, colorF, statuses, metas)
            }.collect { sorted ->
                _displayFiles.value = sorted
            }
        }
    }

    // ─── ソート・フィルター ────────────────────────────────────────────────

    fun setSortKey(key: SortPrefs.SortKey) {
        if (_sortKey.value == key) {
            _ascending.value = !_ascending.value
            sortPrefs.ascending = _ascending.value
        } else {
            _sortKey.value = key
            sortPrefs.sortKey = key
            _ascending.value = true
            sortPrefs.ascending = true
        }
    }

    fun setFolderOrder(order: SortPrefs.FolderOrder) {
        _folderOrder.value = order
        sortPrefs.folderOrder = order
    }

    fun toggleStatusFilter(statusName: String) {
        val current = _statusFilter.value.toMutableSet()
        if (statusName in current) current.remove(statusName) else current.add(statusName)
        _statusFilter.value = current
        sortPrefs.statusFilter = current
    }

    fun toggleColorLabelFilter(labelValue: String) {
        val current = _colorLabelFilter.value.toMutableSet()
        if (labelValue in current) current.remove(labelValue) else current.add(labelValue)
        _colorLabelFilter.value = current
        sortPrefs.colorLabelFilter = current
    }

    fun clearFilters() {
        _statusFilter.value     = emptySet()
        _colorLabelFilter.value = emptySet()
        sortPrefs.clearFilters()
    }

    private fun applySort(
        raw: List<FileItem>,
        key: SortPrefs.SortKey,
        ascending: Boolean,
        folderOrder: SortPrefs.FolderOrder,
        statusFilter: Set<String>,
        colorLabelFilter: Set<String>,
        statuses: Map<String, com.kamneko88.comicveil.data.db.ReadStatus>,
        metas: Map<String, com.kamneko88.comicveil.data.db.ComicFile>
    ): List<FileItem> {
        val filtered = if (statusFilter.isEmpty() && colorLabelFilter.isEmpty()) {
            raw
        } else {
            raw.filter { item ->
                if (!item.isComic) return@filter true
                val statusOk = statusFilter.isEmpty() ||
                    statuses[item.path]?.name in statusFilter
                val colorOk  = colorLabelFilter.isEmpty() ||
                    metas[item.path]?.colorLabel?.toString() in colorLabelFilter
                statusOk && colorOk
            }
        }

        val comparator: Comparator<FileItem> = when (key) {
            SortPrefs.SortKey.NAME   -> compareBy { it.name.lowercase() }
            SortPrefs.SortKey.DATE   -> compareBy { it.lastModified }
            SortPrefs.SortKey.RATING -> compareBy { metas[it.path]?.rating ?: 0 }
        }
        val sorted = if (ascending) filtered.sortedWith(comparator)
                     else           filtered.sortedWith(comparator).reversed()

        return when (folderOrder) {
            SortPrefs.FolderOrder.FOLDER_FIRST -> sorted.sortedWith(compareByDescending { it.isFolder })
            SortPrefs.FolderOrder.FILE_FIRST   -> sorted.sortedWith(compareBy { it.isFolder })
            SortPrefs.FolderOrder.MIXED        -> sorted
        }
    }

    // ─── DL/STRモード ────────────────────────────────────────────────────

    fun toggleMode() {
        _isStreamingMode.value = !_isStreamingMode.value
        _dlSelectedPaths.value = emptySet()
    }

    fun toggleDlSelection(filePath: String) {
        val current = _dlSelectedPaths.value.toMutableSet()
        if (filePath in current) current.remove(filePath) else current.add(filePath)
        _dlSelectedPaths.value = current
    }

    fun clearDlSelection() {
        _dlSelectedPaths.value = emptySet()
    }

    fun downloadSelected() {
        val selectedFiles = _files.value.filter { it.path in _dlSelectedPaths.value }
        selectedFiles.forEach { fileItem ->
            transferViewModel?.enqueue(fileItem, isStreaming = false)
        }
        _dlSelectedPaths.value = emptySet()
        val folderName = (currentLocation.value as? ViewLocation.NasFolder)?.displayTitle ?: ""
        _navigateToTransfer.tryEmit(folderName)
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

    fun navigateToRoot() {
        _currentLocation.value = ViewLocation.Home
        _files.value = fileRepository.getFiles(homeFolder)
        loadFileStatuses(_files.value)
    }

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
            val savedPage = if (progress != null && progress.currentPage > 0)
                progress.currentPage else 0
            val totalPages = progress?.totalPages ?: 0
            _dialogState.value = ResumeDialogState(
                fileItem   = fileItem.copy(file = destFile),
                savedPage  = savedPage,
                totalPages = totalPages
            )
        } else {
            _dialogState.value = ResumeDialogState(
                fileItem   = fileItem,
                savedPage  = 0,
                totalPages = 0
            )
        }
    }

    private fun openNasComicDl(fileItem: FileItem) {
        transferViewModel?.enqueue(fileItem, isStreaming = false)
        val folderName = (currentLocation.value as? ViewLocation.NasFolder)?.displayTitle ?: ""
        _navigateToTransfer.tryEmit(folderName)
    }

    fun startStrDownload(fileItem: FileItem) {
        val ext    = fileItem.name.substringAfterLast(".").lowercase()
        val server = fileItem.nasServer ?: return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            if (ext in setOf("zip", "cbz")) {
                startStrDownloadZipProgressive(fileItem, server, ext)
            } else {
                startStrDownloadFull(fileItem, server, ext)
            }
        }
    }

    /**
     * ZIP: Progressive Loading
     * パスワード付きZIPの場合は全体DLに切り替える
     */
    private suspend fun startStrDownloadZipProgressive(
        fileItem: FileItem,
        server: NasServer,
        ext: String
    ) {
        // NASから先頭4KBだけ取得してパスワード付きか確認する
        var isEncrypted = false
        try {
            val bytes = smbRepository.fetchPartialBytes(server, fileItem.nasPath, 4096L)
            if (bytes != null && bytes.isNotEmpty()) {
                val tmpFile = File(
                    getApplication<Application>().cacheDir,
                    "nas_enc_check_${fileItem.nasPath.hashCode()}.$ext"
                )
                tmpFile.writeBytes(bytes)
                isEncrypted = Zip4jFile(tmpFile).isEncrypted
                tmpFile.delete()
            }
        } catch (e: Exception) {
            // チェック失敗は無視して通常Progressive Loadingに進む
        }

        if (isEncrypted) {
            // パスワード付きZIP → 全体DLしてビューワーに渡す（パスワード入力はビューワー側）
            startStrDownloadFull(fileItem, server, ext)
            return
        }

        val pageDir = File(
            File(getApplication<Application>().cacheDir, "nas_pages"),
            "nas_${fileItem.nasPath.hashCode()}"
        )
        if (File(pageDir, "complete").exists()) {
            _navigateEvent.tryEmit(pageDir.absolutePath)
            return
        }
        pageDir.deleteRecursively()
        pageDir.mkdirs()

        var launched = false
        try {
            _downloadProgress.value = DownloadProgress(
                fileName   = fileItem.name,
                downloaded = 0L,
                total      = -1L
            )
            smbRepository.downloadZipProgressive(
                server      = server,
                nasPath     = fileItem.nasPath,
                pageDir     = pageDir,
                onPageReady = { savedCount ->
                    _downloadProgress.value = DownloadProgress(
                        fileName   = fileItem.name,
                        downloaded = savedCount.toLong(),
                        total      = -1L
                    )
                    if (!launched && savedCount >= 1) {
                        launched = true
                        _navigateEvent.tryEmit(pageDir.absolutePath)
                    }
                },
                onComplete = {
                    _downloadProgress.value = null
                    if (!launched) {
                        launched = true
                        _navigateEvent.tryEmit(pageDir.absolutePath)
                    }
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _downloadProgress.value = null
        } catch (e: Exception) {
            _downloadProgress.value = null
            _nasError.value = "接続に失敗しました\n${e.message}"
        } finally {
            downloadJob = null
        }
    }

    /** RARなど: 全体DL後に起動 */
    private suspend fun startStrDownloadFull(
        fileItem: FileItem,
        server: NasServer,
        ext: String
    ) {
        val destFile = File(
            File(getApplication<Application>().cacheDir, "nas_cache"),
            "nas_${fileItem.nasPath.hashCode()}.$ext"
        )
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

    private fun nasCacheDir(): File =
        File(getApplication<Application>().cacheDir, "nas_cache")

    fun isNasCached(fileItem: FileItem): Boolean {
        if (!fileItem.isNas) return false
        val ext  = fileItem.name.substringAfterLast(".")
        val file = File(nasCacheDir(), "nas_${fileItem.nasPath.hashCode()}.$ext")
        return file.exists() && file.length() >= 100 * 1024
    }

    fun clearNasCache(): Long {
        val dir = nasCacheDir()
        var totalBytes = 0L
        dir.listFiles()?.forEach {
            totalBytes += it.length()
            it.delete()
        }
        return totalBytes
    }

    fun clearThumbnailCache(thumbnailCacheDir: File): Long {
        var totalBytes = 0L
        thumbnailCacheDir.listFiles()?.forEach {
            totalBytes += it.length()
            it.delete()
        }
        return totalBytes
    }
}
