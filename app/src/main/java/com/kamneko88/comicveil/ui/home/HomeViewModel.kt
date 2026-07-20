package com.kamneko88.comicveil.ui.home

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.ArchiveScanner
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.SafFileRepository
import com.kamneko88.comicveil.data.SortPrefs
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.db.ColorLabel
import com.kamneko88.comicveil.data.db.ComicFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.db.ReadingProgressRepository
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.NasServerPrefs
import com.kamneko88.comicveil.data.nas.RemoteBookmark
import com.kamneko88.comicveil.data.nas.RemoteBookmarkPrefs
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
    data class SafFolder(val uri: Uri, val displayName: String) : ViewLocation()
    data class NasFolder(val server: NasServer, val path: String) : ViewLocation() {
        val displayTitle: String get() =
            if (path.isEmpty()) server.displayName else path.substringAfterLast("/")
    }
}

/** スクロール位置を覚えておくための、場所ごとに一意なキー */
val ViewLocation.key: String
    get() = when (this) {
        is ViewLocation.Home        -> "home"
        is ViewLocation.LocalFolder -> "local:${folder.absolutePath}"
        is ViewLocation.SafFolder   -> "saf:$uri"
        is ViewLocation.NasFolder   -> "nas:${server.id}:$path"
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
    private val safFileRepository   = SafFileRepository()
    private val progressRepository  : ReadingProgressRepository
    private val comicFileRepository : ComicFileRepository
    private val fileTitleDao        : com.kamneko88.comicveil.data.db.FileTitleDao
    private val smbRepository       = SmbRepository()
    private val nasServerPrefs      = NasServerPrefs(application)
    private val remoteBookmarkPrefs = RemoteBookmarkPrefs(application)
    val appPrefs                    = AppPrefs(application)
    val sortPrefs                   = SortPrefs(application)

    private val homeFolder: File
        get() = appPrefs.resolveHomeFolder(getApplication())

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

    // ── 検索（今見ているフォルダ／HOME内のみが対象。サブフォルダやNAS横断はしない） ──
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _nasServers = MutableStateFlow<List<NasServer>>(emptyList())
    val nasServers: StateFlow<List<NasServer>> = _nasServers.asStateFlow()

    /** HOMEに表示するリモートブックマーク（解決済みFileItem） */
    private val _bookmarks = MutableStateFlow<List<FileItem>>(emptyList())
    val bookmarks: StateFlow<List<FileItem>> = _bookmarks.asStateFlow()

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

    private val _navigateToVolumes = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val navigateToVolumes: SharedFlow<String> = _navigateToVolumes.asSharedFlow()

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
        fileTitleDao        = db.fileTitleDao()
        refreshNasServers()

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _files, _sortKey, _ascending, _folderOrder,
                _statusFilter, _colorLabelFilter, _fileStatuses, _fileMetaMap, _searchQuery
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
                val query        = arr[8] as String
                applySort(rawFiles, key, asc, order, statusF, colorF, statuses, metas, query)
            }.collect { sorted ->
                _displayFiles.value = sorted
            }
        }
    }

    // ─── スクロール位置の記憶 ────────────────────────────

    /**
     * フォルダごとの一覧のスクロール位置。
     * ビューワーへ移動すると画面は破棄されるが、ViewModelは生き残るので
     * ここに位置を覚えておき、戻ってきたときに元の位置へ戻せるようにする。
     * （続きの巻を探すとき、毎回先頭に戻されるのを防ぐ）
     */
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(key: String, index: Int, offset: Int) {
        scrollPositions[key] = index to offset
    }

    fun getScrollPosition(key: String): Pair<Int, Int>? = scrollPositions[key]

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
        metas: Map<String, com.kamneko88.comicveil.data.db.ComicFile>,
        searchQuery: String = ""
    ): List<FileItem> {
        val searched = if (searchQuery.isBlank()) {
            raw
        } else {
            raw.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        val filtered = if (statusFilter.isEmpty() && colorLabelFilter.isEmpty()) {
            searched
        } else {
            searched.filter { item ->
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
        refreshBookmarks()
    }

    // ─── リモートブックマーク（HOME登録） ────────────────────

    /** 保存済みブックマークをFileItemに解決する。参照先サーバーがなければ除外する。 */
    fun refreshBookmarks() {
        val servers = nasServerPrefs.getServers().associateBy { it.id }
        _bookmarks.value = remoteBookmarkPrefs.getBookmarks().mapNotNull { bm ->
            val server = servers[bm.serverId] ?: return@mapNotNull null
            FileItem.fromNas(
                name        = bm.name,
                nasPath     = bm.nasPath,
                isDirectory = bm.isFolder,
                size        = 0L,
                server      = server
            ).copy(isRemoteBookmark = true)
        }
    }

    /** このリモート項目がすでにHOMEに登録されているか */
    fun isBookmarked(fileItem: FileItem): Boolean {
        val serverId = fileItem.nasServer?.id ?: return false
        return remoteBookmarkPrefs.exists(serverId, fileItem.nasPath)
    }

    /** リモート項目のHOME登録を切り替える（登録↔解除） */
    fun toggleBookmark(fileItem: FileItem) {
        val server = fileItem.nasServer ?: return
        if (remoteBookmarkPrefs.exists(server.id, fileItem.nasPath)) {
            remoteBookmarkPrefs.removeByTarget(server.id, fileItem.nasPath)
        } else {
            remoteBookmarkPrefs.add(
                RemoteBookmark(
                    serverId = server.id,
                    nasPath  = fileItem.nasPath,
                    name     = fileItem.name,
                    isFolder = fileItem.isFolder
                )
            )
        }
        refreshBookmarks()
    }

    /**
     * サーバーに接続して共有フォルダ名の一覧を取得する（登録ダイアログの選択式用）。
     * 成功＝接続確認も兼ねる。失敗（未対応・認証エラー等）は Result.failure で返し、呼び出し側で手入力に誘導する。
     */
    suspend fun listShares(host: String, username: String, password: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching { smbRepository.listShares(host.trim(), username.trim(), password) }
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

    // ─── ローカル/SAF ホームの読み込み共通処理 ───────────────────────────

    /** appPrefsのhomeFolderTypeに応じて、Homeの中身を読み込む */
    private fun loadHomeContents() {
        _currentLocation.value = ViewLocation.Home
        when (appPrefs.homeFolderType) {
            AppPrefs.HomeFolderType.APP_FOLDER -> {
                _files.value = fileRepository.getFiles(homeFolder)
            }
            AppPrefs.HomeFolderType.SAF_FOLDER -> {
                val uriString = appPrefs.homeFolderSafUri
                _files.value = if (uriString != null) {
                    safFileRepository.getFiles(getApplication(), Uri.parse(uriString))
                } else {
                    emptyList()
                }
            }
        }
        loadFileStatuses(_files.value)
    }

    fun loadInitialFolder() {
        if (_currentLocation.value !is ViewLocation.Home) return
        loadHomeContents()
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

    /** SAFフォルダ内のサブフォルダに入る */
    fun loadSafFolder(uri: Uri, displayName: String) {
        _currentLocation.value = ViewLocation.SafFolder(uri, displayName)
        _files.value = safFileRepository.getFiles(getApplication(), uri)
        loadFileStatuses(_files.value)
    }

    fun navigateToRoot() {
        loadHomeContents()
    }

    fun navigateToHome() {
        loadHomeContents()
    }

    /**
     * SAFの「フォルダ選択」ピッカーで選ばれたフォルダをホームフォルダとして保存する。
     * 永続的なアクセス権限を取得してからAppPrefsに保存し、Homeとして読み込む。
     */
    fun pickedSafHomeFolder(uri: Uri) {
        val context = getApplication<Application>()
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            _nasError.value = "フォルダへのアクセス許可の取得に失敗しました"
            return
        }
        appPrefs.homeFolderType    = AppPrefs.HomeFolderType.SAF_FOLDER
        appPrefs.homeFolderSafUri  = uri.toString()
        loadHomeContents()
    }

    /** SAFの「フォルダ選択」ピッカーで選ばれたフォルダをDL保存先として保存する */
    fun pickedSafDownloadFolder(uri: Uri) {
        val context = getApplication<Application>()
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            _nasError.value = "フォルダへのアクセス許可の取得に失敗しました"
            return
        }
        appPrefs.downloadFolderType   = AppPrefs.DownloadFolderType.SAF_FOLDER
        appPrefs.downloadFolderSafUri = uri.toString()
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
                if (parent != null) loadFolder(parent) else loadHomeContents()
                true
            }

            is ViewLocation.SafFolder -> {
                val parentDoc = safFileRepository.getParent(getApplication(), loc.uri)
                if (parentDoc != null) {
                    loadSafFolder(parentDoc.uri, parentDoc.name ?: "フォルダ")
                } else {
                    loadHomeContents()
                }
                true
            }

            is ViewLocation.NasFolder -> {
                if (loc.path.isEmpty()) {
                    loadHomeContents()
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
            when {
                fileItem.isNas -> {
                    if (_isStreamingMode.value) openNasComicStr(fileItem) else openNasComicDl(fileItem)
                }
                fileItem.isSaf -> {
                    val cacheFile = withContext(Dispatchers.IO) { ensureSafCached(fileItem) }
                    if (cacheFile == null) {
                        _nasError.value = "ファイルの読み込みに失敗しました"
                        return@launch
                    }
                    openLocalOrVolumeComic(fileItem.copy(file = cacheFile))
                }
                else -> {
                    openLocalOrVolumeComic(fileItem)
                }
            }
        }
    }

    /**
     * ローカル（またはSAFキャッシュ済み）のコミックを開く。
     * 先にアーカイブの中身をスキャンし、複数の巻フォルダが見つかった場合は巻一覧画面へ、
     * そうでなければ今まで通りの再開ダイアログ・Viewer遷移を行う。
     */
    private suspend fun openLocalOrVolumeComic(item: FileItem) {
        val file = item.file
        val effectivePath = file?.absolutePath ?: item.path

        if (file != null) {
            val ext = file.extension.lowercase()
            if (ext in setOf("zip", "cbz", "rar", "cbr", "7z")) {
                val scan = withContext(Dispatchers.IO) { ArchiveScanner.scan(file) }
                if (scan.volumes != null) {
                    _navigateToVolumes.tryEmit(file.absolutePath)
                    return
                }
            }
        }

        val progress = withContext(Dispatchers.IO) {
            progressRepository.getProgress(effectivePath)
        }
        if (progress != null && progress.currentPage > 0) {
            _dialogState.value = ResumeDialogState(
                item, progress.currentPage, progress.totalPages
            )
        } else {
            _navigateEvent.tryEmit(effectivePath)
        }
    }

    /**
     * SAFで選んだコミックファイルを、URIハッシュ値による決定的なパスでアプリキャッシュへコピーする。
     * すでにコピー済みならそのまま返す（NASのSTRキャッシュと同じ考え方）。
     */
    private fun ensureSafCached(fileItem: FileItem): File? {
        val uri = fileItem.uri ?: return null
        val ext = fileItem.name.substringAfterLast(".")
        val cacheDir = File(getApplication<Application>().cacheDir, "saf_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "saf_${uri.toString().hashCode()}.$ext")
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
        } catch (e: Exception) {
            runCatching { cacheFile.delete() }
            null
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

    /**
     * NASコミックをストリーミング（STR）モードで開く。
     *
     * ZIPなら、ダウンロードを待たずに**落ちてきた分から読み始める**（数秒で開く）。
     * RAR/7z/PDFや壊れたZIPは構造上それができないので、従来どおり全体を落としてから開く。
     */
    private fun openNasComicStr(fileItem: FileItem) {
        val ext      = fileItem.name.substringAfterLast(".")
        val destFile = File(
            File(getApplication<Application>().cacheDir, "nas_cache"),
            "nas_${fileItem.nasPath.hashCode()}.$ext"
        )

        // キャッシュの名前は nas_-409694946.zip のような機械的なものなので、
        // 元の作品名を控えておく（閲覧履歴で正しいタイトルを出すため）
        rememberOriginalName(destFile.absolutePath, fileItem.name)

        // すでに全体が落ちているなら、そのまま開く
        val isFullyCached = destFile.exists() && destFile.length() > 0 &&
            (fileItem.size <= 0 || destFile.length() >= fileItem.size)
        if (isFullyCached) {
            viewModelScope.launch { openLocalOrVolumeComic(fileItem.copy(file = destFile)) }
            return
        }

        if (ext.lowercase() in setOf("zip", "cbz")) {
            startZipStreaming(fileItem, destFile)
        } else {
            downloadThenOpenNasComic(fileItem)
        }
    }

    /**
     * キャッシュパスと元のファイル名の対応を覚えておく。
     * リモートの本はキャッシュ上で機械的な名前になるため、
     * これがないと閲覧履歴で作品名が分からない。
     */
    private fun rememberOriginalName(cachePath: String, originalName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                fileTitleDao.save(
                    com.kamneko88.comicveil.data.db.FileTitle(cachePath, originalName)
                )
            }
        }
    }

    /**
     * ZIPのストリーミング再生を開始する。
     *
     * ZIPの「目次」（中央ディレクトリ）はファイル末尾にあるので、まずそこだけを先に読む。
     * 目次が読めれば総ページ数が分かるので、ダウンロードを始めながら読み始められる。
     * 目次が壊れている・巻フォルダ構成などの場合は、黙って従来方式（全DL）に切り替える。
     */
    private fun startZipStreaming(fileItem: FileItem, destFile: File) {
        val server = fileItem.nasServer ?: run {
            _nasError.value = "リモートサーバー情報がありません"
            return
        }

        viewModelScope.launch {
            // 目次の先読み中だけ短くローディングを出す
            _downloadProgress.value = DownloadProgress(
                fileName   = fileItem.name,
                downloaded = 0L,
                total      = -1L
            )

            val tail = withContext(Dispatchers.IO) {
                smbRepository.readTail(server, fileItem.nasPath)
            }
            _downloadProgress.value = null

            if (tail == null) {
                downloadThenOpenNasComic(fileItem)
                return@launch
            }

            val (fileSize, tailBytes) = tail
            val cacheDir = File(getApplication<Application>().cacheDir, "nas_cache")

            val scan = withContext(Dispatchers.IO) {
                com.kamneko88.comicveil.data.ZipStreamSupport.probeFromTail(cacheDir, fileSize, tailBytes)
            }

            // 目次が読めない（壊れている）・巻フォルダ構成 → 従来どおり全DLしてから開く
            if (scan == null || scan.volumes != null) {
                downloadThenOpenNasComic(fileItem)
                return@launch
            }

            withContext(Dispatchers.IO) {
                destFile.parentFile?.mkdirs()
                // 途中まで落ちていたファイルがあれば作り直す
                runCatching { destFile.delete() }
                com.kamneko88.comicveil.data.ZipStreamSupport.writeSidecar(destFile, scan, fileSize)
            }

            // フォアグラウンドサービスでダウンロードを開始し、すぐにビューワーへ
            transferViewModel?.enqueue(fileItem, isStreaming = true)
            _navigateEvent.tryEmit(destFile.absolutePath)
        }
    }

    private fun openNasComicDl(fileItem: FileItem) {
        transferViewModel?.enqueue(fileItem, isStreaming = false)
        val folderName = (currentLocation.value as? ViewLocation.NasFolder)?.displayTitle ?: ""
        _navigateToTransfer.tryEmit(folderName)
    }

    /**
     * NASファイルをキャッシュへダウンロードしてから、ローカルと同じ判定フロー
     * （巻検出・再開ダイアログ）に合流する。ZIP/RAR/7z問わずこの一本に統一。
     * ダウンロード完了後にArchiveScannerで巻検出するため、フォーマットを問わず
     * 巻選択画面が正しく機能する。
     */
    private fun downloadThenOpenNasComic(fileItem: FileItem) {
        val ext      = fileItem.name.substringAfterLast(".")
        val destFile = File(
            File(getApplication<Application>().cacheDir, "nas_cache"),
            "nas_${fileItem.nasPath.hashCode()}.$ext"
        )
        val server = fileItem.nasServer ?: run {
            _nasError.value = "リモートサーバー情報がありません"
            return
        }

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
                _downloadProgress.value = null
                openLocalOrVolumeComic(fileItem.copy(file = destFile))
            } catch (e: kotlinx.coroutines.CancellationException) {
                _downloadProgress.value = null
                runCatching { destFile.delete() }
            } catch (e: Exception) {
                _downloadProgress.value = null
                _nasError.value = "接続に失敗しました\n${e.message}"
                runCatching { destFile.delete() }
            } finally {
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
        _navigateEvent.tryEmit(state.fileItem.file?.absolutePath ?: state.fileItem.path)
    }

    fun readFromBeginning() {
        val state = _dialogState.value ?: return
        _dialogState.value = null
        val effectivePath = state.fileItem.file?.absolutePath ?: state.fileItem.path
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                progressRepository.saveProgress(effectivePath, 0, state.totalPages)
            }
            _navigateEvent.tryEmit(effectivePath)
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    // ─── ローカルファイル削除 ──────────────────────────────────────────────

    fun deleteLocalFiles(fileItems: List<FileItem>): Int {
        var count = 0
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            fileItems.forEach { item ->
                val deleted = when {
                    item.isSaf -> {
                        val doc = DocumentFile.fromSingleUri(context, item.uri!!)
                        doc?.delete() ?: false
                    }
                    item.file != null -> {
                        if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
                    }
                    else -> false
                }
                if (deleted) {
                    comicFileRepository.delete(item.path)
                    progressRepository.deleteProgress(item.path)
                    count++
                }
            }
            withContext(Dispatchers.Main) {
                when (val loc = _currentLocation.value) {
                    is ViewLocation.Home        -> loadHomeContents()
                    is ViewLocation.LocalFolder -> {
                        _files.value = fileRepository.getFiles(loc.folder)
                        loadFileStatuses(_files.value)
                    }
                    is ViewLocation.SafFolder   -> {
                        _files.value = safFileRepository.getFiles(context, loc.uri)
                        loadFileStatuses(_files.value)
                    }
                    else -> {}
                }
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
