package com.kamneko88.comicveil.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.FileItemType
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.ThumbnailRepository
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.ui.transfer.TransferViewModel
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(),
    transferViewModel: TransferViewModel
) {
    val context          = LocalContext.current
    val files            by viewModel.files.collectAsState()
    val nasServers       by viewModel.nasServers.collectAsState()
    val currentLocation  by viewModel.currentLocation.collectAsState()
    val dialogState      by viewModel.dialogState.collectAsState()
    val isLoading        by viewModel.isLoading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val nasError         by viewModel.nasError.collectAsState()
    val isStreamingMode  by viewModel.isStreamingMode.collectAsState()
    val fileInfoState    by viewModel.fileInfoState.collectAsState()

    viewModel.transferViewModel = transferViewModel

    var isListMode       by remember { mutableStateOf(true) }
    var hasPermission    by remember { mutableStateOf(false) }
    var showAddNasDialog by remember { mutableStateOf(false) }
    var editingServer    by remember { mutableStateOf<NasServer?>(null) }

    val thumbnailRepository = remember {
        ThumbnailRepository(File(context.cacheDir, "thumbnails"))
    }

    val isRoot = currentLocation is ViewLocation.Home
    val isNas  = currentLocation is ViewLocation.NasFolder

    val screenTitle = when (val loc = currentLocation) {
        is ViewLocation.Home        -> "HOME"
        is ViewLocation.LocalFolder -> loc.folder.name
        is ViewLocation.NasFolder   -> loc.displayTitle
    }

    LaunchedEffect(Unit) {
        viewModel.navigateEvent.collect { filePath ->
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            navController.navigate("viewer/$encodedPath")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToTransfer.collect { folderName ->
            val name    = folderName?.takeIf { it.isNotEmpty() } ?: "home"
            val encoded = URLEncoder.encode(name, "UTF-8")
            navController.navigate("transfer/$encoded")
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            hasPermission = true
            viewModel.loadInitialFolder()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { hasPermission = true; viewModel.loadInitialFolder() }
    }
    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    hasPermission = true; viewModel.loadInitialFolder()
                } else {
                    manageStorageLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    hasPermission = true; viewModel.loadInitialFolder()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    BackHandler(enabled = !isRoot) { viewModel.navigateUp() }

    // NASサーバー追加・編集ダイアログ
    if (showAddNasDialog) {
        AddNasServerDialog(
            onDismiss  = { showAddNasDialog = false; editingServer = null },
            onConfirm  = { server ->
                viewModel.addNasServer(server)
                showAddNasDialog = false
                editingServer = null
            },
            editServer = editingServer
        )
    }

    // 再開ダイアログ
    dialogState?.let { state ->
        val repo = remember { LocalFileRepository() }
        val (title, _) = remember(state.fileItem.name) { repo.parseFileName(state.fileItem.name) }
        val isNasStr = state.fileItem.isNas && isStreamingMode
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    if (isNasStr) "読み込みを開始します"
                    else "前回の続きから読みますか？"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeReading() }) {
                    Text(
                        if (isNasStr || state.totalPages == 0) "読み込む"
                        else "${state.savedPage + 1} / ${state.totalPages}ページから再開"
                    )
                }
            },
            dismissButton = {
                if (!isNasStr && state.totalPages > 0) {
                    TextButton(onClick = { viewModel.readFromBeginning() }) {
                        Text("最初から読む")
                    }
                }
            }
        )
    }

    // ファイル情報ポップアップ
    fileInfoState?.let { info ->
        FileInfoDialog(
            info           = info,
            onStatusChange = { status -> viewModel.updateFileStatus(info.fileItem, status) },
            onDismiss      = { viewModel.dismissFileInfo() }
        )
    }

    // NASエラーダイアログ
    nasError?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearNasError() },
            title = { Text("NAS接続エラー") },
            text  = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearNasError() }) { Text("閉じる") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text     = screenTitle,
                        style    = if (isRoot) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (!isRoot) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Search, contentDescription = "検索")
                    }
                    TextButton(onClick = { }) { Text("名前▼") }
                    IconButton(onClick = { isListMode = !isListMode }) {
                        Icon(
                            if (isListMode) Icons.Default.GridView
                            else Icons.AutoMirrored.Filled.List,
                            contentDescription = "表示切替"
                        )
                    }
                    TextButton(onClick = { }) { Text("編集") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier              = Modifier.fillMaxWidth().height(56.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "About")
                    }
                    TextButton(onClick = { showAddNasDialog = true }) { Text("サーバー") }

                    if (isNas) {
                        IconButton(onClick = { viewModel.toggleMode() }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector        = if (isStreamingMode) Icons.Default.Wifi
                                    else Icons.Default.Download,
                                    contentDescription = null,
                                    modifier           = Modifier.size(18.dp),
                                    tint               = if (isStreamingMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text       = if (isStreamingMode) "STR" else "DL",
                                    fontSize   = 9.sp,
                                    color      = if (isStreamingMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        TextButton(onClick = { }) { Text("C") }
                    }

                    IconButton(onClick = { viewModel.openTransferScreen() }) {
                        Icon(Icons.Default.SwapVert, contentDescription = "転送状況")
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("NASを読み込み中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (isRoot && nasServers.isNotEmpty()) {
                        item {
                            Text(
                                text     = "NASサーバー",
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(nasServers) { server ->
                            NasServerListItem(
                                server   = server,
                                onClick  = { viewModel.navigateToNas(server) },
                                onEdit   = { editingServer = it; showAddNasDialog = true },
                                onDelete = { viewModel.deleteNasServer(it.id) }
                            )
                            HorizontalDivider()
                        }
                        item {
                            HorizontalDivider(
                                thickness = 4.dp,
                                color     = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                text     = "ローカル",
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (files.isEmpty() && !isLoading) {
                        item {
                            Box(
                                modifier         = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text  = if (hasPermission) "ファイルが見つかりません"
                                    else "権限を確認中…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(files) { fileItem ->
                            val itemStatus by produceState(
                                initialValue = ReadStatus.UNREAD,
                                key1         = fileItem.path
                            ) {
                                value = viewModel.getStatusForItem(fileItem.path)
                            }
                            FileListItem(
                                fileItem            = fileItem,
                                thumbnailRepository = thumbnailRepository,
                                status              = itemStatus,
                                onClick = {
                                    when {
                                        fileItem.isFolder -> {
                                            if (fileItem.isNas) viewModel.navigateToNas(
                                                fileItem.nasServer!!, fileItem.nasPath
                                            )
                                            else viewModel.loadFolder(fileItem.file!!)
                                        }
                                        fileItem.isComic -> viewModel.onComicTapped(fileItem)
                                    }
                                },
                                onInfoClick = { viewModel.openFileInfo(fileItem) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            // STRダウンロード進捗オーバーレイ
            downloadProgress?.let { progress ->
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "読み込み中…", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text      = progress.fileName,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines  = 2,
                            overflow  = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (progress.fraction != null) {
                            LinearProgressIndicator(
                                progress = { progress.fraction!! },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text  = progress.progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.cancelStrDownload() }) {
                            Text(
                                text  = "転送を中止する",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── ファイル情報ポップアップ ─────────────────────────────────────────────────

@Composable
fun FileInfoDialog(
    info: FileInfoState,
    onStatusChange: (ReadStatus) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = info.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info.author != null) {
                    FileInfoRow(label = "作者", value = info.author)
                }
                if (info.fileItem.size > 0) {
                    FileInfoRow(label = "サイズ", value = formatFileSize(info.fileItem.size))
                }
                if (info.fileItem.lastModified > 0) {
                    FileInfoRow(
                        label = "更新日",
                        value = dateFormat.format(Date(info.fileItem.lastModified))
                    )
                }
                val ext = info.fileItem.name.substringAfterLast(".", "")
                if (ext.isNotEmpty()) {
                    FileInfoRow(label = "形式", value = ext.uppercase())
                }

                HorizontalDivider()

                Text(
                    text  = "読書状態",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadStatus.entries.forEach { status ->
                        // 「読書中」は手動選択不可（自動遷移のみ）
                        val isSelectable = status != ReadStatus.READING
                        FilterChip(
                            selected = info.status == status,
                            enabled  = isSelectable,
                            onClick  = { if (isSelectable) onStatusChange(status) },
                            label    = { Text(status.label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

@Composable
private fun FileInfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text     = value,
            style    = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}

// ─── 読書状態バッジ ───────────────────────────────────────────────────────────

@Composable
fun ReadStatusBadge(status: ReadStatus) {
    val (backgroundColor, textColor) = when (status) {
        ReadStatus.READING -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ReadStatus.READ    -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        ReadStatus.UNREAD  -> Pair(Color.Transparent, Color.Transparent)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text     = status.label,
            style    = MaterialTheme.typography.labelSmall,
            color    = textColor,
            fontSize = 10.sp
        )
    }
}

// ─── NASサーバーリストアイテム ────────────────────────────────────────────────

@Composable
fun NasServerListItem(
    server: NasServer,
    onClick: () -> Unit,
    onEdit: (NasServer) -> Unit,
    onDelete: (NasServer) -> Unit
) {
    var showMenu   by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("サーバーを削除") },
            text  = { Text("「${server.displayName}」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete(server) }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("キャンセル") }
            }
        )
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = Icons.Default.Dns,
            contentDescription = null,
            modifier           = Modifier.size(40.dp),
            tint               = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = server.displayName,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text  = "${server.host} / ${server.shareName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "メニュー",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded         = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text    = { Text("編集") },
                    onClick = { showMenu = false; onEdit(server) }
                )
                DropdownMenuItem(
                    text    = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; showDelete = true }
                )
            }
        }
    }
}

// ─── ファイルリストアイテム ───────────────────────────────────────────────────

@Composable
fun FileListItem(
    fileItem: FileItem,
    thumbnailRepository: ThumbnailRepository,
    onClick: () -> Unit,
    onInfoClick: () -> Unit = {},
    status: ReadStatus = ReadStatus.UNREAD
) {
    val repository = remember { LocalFileRepository() }
    val (title, author) = remember(fileItem.name) { repository.parseFileName(fileItem.name) }

    val thumbnailFile by produceState<File?>(initialValue = null, key1 = fileItem.path) {
        value = thumbnailRepository.getOrGenerateThumbnail(fileItem)
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier         = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailFile != null) {
                AsyncImage(
                    model              = thumbnailFile,
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector        = when (fileItem.type) {
                        FileItemType.FOLDER -> Icons.Default.Folder
                        else                -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    modifier           = Modifier.size(32.dp),
                    tint               = when (fileItem.type) {
                        FileItemType.FOLDER -> MaterialTheme.colorScheme.primary
                        else -> if (fileItem.isNas) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.secondary
                    }
                )
            }
        }

        Column(
            modifier            = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text     = title,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (author != null) {
                Text(
                    text     = author,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text  = when {
                        fileItem.isFolder -> if (fileItem.isNas) "NASフォルダ" else "フォルダ"
                        else              -> fileItem.extension.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fileItem.isComic && status != ReadStatus.UNREAD) {
                    ReadStatusBadge(status = status)
                }
            }
        }

        if (fileItem.isComic) {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector        = Icons.Default.Info,
                    contentDescription = "ファイル情報",
                    modifier           = Modifier.size(20.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}