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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.FileItemType
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.ThumbnailRepository
import java.io.File
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val files by viewModel.files.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    var isListMode by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(false) }

    // ThumbnailRepository を1つ生成してリスト全体で共有する
    val thumbnailRepository = remember {
        ThumbnailRepository(File(context.cacheDir, "thumbnails"))
    }

    val rootFolder = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    )
    val isRoot = currentPath == null ||
            currentPath?.absolutePath == rootFolder.absolutePath

    // ─── ビューワーへの遷移イベント ───────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.navigateEvent.collect { filePath ->
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            navController.navigate("viewer/$encodedPath")
        }
    }

    // ─── 権限まわり ────────────────────────────────────────────────────────
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            hasPermission = true
            viewModel.loadInitialFolder()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
            viewModel.loadInitialFolder()
        }
    }

    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    hasPermission = true
                    viewModel.loadInitialFolder()
                } else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    ).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    manageStorageLauncher.launch(intent)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    hasPermission = true
                    viewModel.loadInitialFolder()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    BackHandler(enabled = !isRoot) {
        viewModel.navigateUp()
    }

    // ─── 「再開 or 最初から」ダイアログ ───────────────────────────────────
    dialogState?.let { state ->
        val fileRepository = remember { LocalFileRepository() }
        val (title, _) = remember(state.fileItem.name) {
            fileRepository.parseFileName(state.fileItem.name)
        }
        val displayPage = state.savedPage + 1
        val displayTotal = if (state.totalPages > 0) " / ${state.totalPages}" else ""

        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = {
                Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Text(text = "前回の続きから読みますか？")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeReading() }) {
                    Text("${displayPage}${displayTotal}ページから再開")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.readFromBeginning() }) {
                    Text("最初から読む")
                }
            }
        )
    }

    // ─── メイン画面 ────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isRoot) {
                        Text("HOME", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Text(
                            text = currentPath?.name ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
            BottomAppBar(modifier = Modifier.height(56.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "About")
                    }
                    TextButton(onClick = { }) { Text("サーバー") }
                    TextButton(onClick = { }) { Text("C") }
                    TextButton(onClick = { }) { Text("D") }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }
        }
    ) { innerPadding ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasPermission) "ファイルが見つかりません" else "権限を確認中..."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(files) { fileItem ->
                    FileListItem(
                        fileItem = fileItem,
                        thumbnailRepository = thumbnailRepository,
                        onClick = {
                            when {
                                fileItem.isFolder -> viewModel.loadFolder(fileItem.file)
                                fileItem.isComic -> viewModel.onComicTapped(fileItem)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun FileListItem(
    fileItem: FileItem,
    thumbnailRepository: ThumbnailRepository,
    onClick: () -> Unit
) {
    val repository = remember { LocalFileRepository() }
    val (title, author) = remember(fileItem.name) {
        repository.parseFileName(fileItem.name)
    }

    // サムネイルを非同期で読み込む
    // key1 = fileItem.path にすることで、別ファイルに変わったときに再実行される
    val thumbnailFile by produceState<File?>(
        initialValue = null,
        key1 = fileItem.path
    ) {
        value = thumbnailRepository.getOrGenerateThumbnail(fileItem)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ─── サムネイルエリア（56×80dp）─────────────────────────────────
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailFile != null) {
                // サムネイル画像を表示
                AsyncImage(
                    model = thumbnailFile,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 読み込み中 or 生成失敗 → アイコンでフォールバック
                Icon(
                    imageVector = when (fileItem.type) {
                        FileItemType.FOLDER -> Icons.Default.Folder
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = when (fileItem.type) {
                        FileItemType.FOLDER -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondary
                    }
                )
            }
        }

        // ─── テキストエリア ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (author != null) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (fileItem.isFolder) "フォルダ"
                else fileItem.file.extension.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}