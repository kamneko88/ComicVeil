package com.kamneko88.comicveil.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.SquareCheck
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.File
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.X
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.FileItemType
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.SortPrefs
import com.kamneko88.comicveil.data.ThumbnailRepository
import com.kamneko88.comicveil.data.db.ColorLabel
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val fileStatuses     by viewModel.fileStatuses.collectAsState()
    val fileMetaMap      by viewModel.fileMetaMap.collectAsState()
    val sortKey           by viewModel.sortKey.collectAsState()
    val ascending         by viewModel.ascending.collectAsState()
    val statusFilter      by viewModel.statusFilter.collectAsState()
    val colorLabelFilter  by viewModel.colorLabelFilter.collectAsState()
    val folderOrder       by viewModel.folderOrder.collectAsState()
    val dlSelectedPaths   by viewModel.dlSelectedPaths.collectAsState()
    val bookmarks         by viewModel.bookmarks.collectAsState()
    val searchQuery       by viewModel.searchQuery.collectAsState()

    viewModel.transferViewModel = transferViewModel

    val appPrefs         = remember { viewModel.appPrefs }
    var displayMode      by remember { mutableStateOf(appPrefs.listDisplayMode) }
    val shelfShowTitle   = appPrefs.shelfShowTitle
    var showAddNasDialog by remember { mutableStateOf(false) }
    var editingServer    by remember { mutableStateOf<NasServer?>(null) }

    // 編集モード関連
    var isEditMode         by remember { mutableStateOf(false) }
    var selectedPaths      by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm  by remember { mutableStateOf(false) }
    var showSortSheet      by remember { mutableStateOf(false) }
    var showAboutDialog    by remember { mutableStateOf(false) }
    // 検索（今見ているフォルダ／HOME内のみが対象）
    var isSearchMode by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // リモート項目の長押しメニュー対象（HOME登録/解除・情報）
    var contextTarget      by remember { mutableStateOf<FileItem?>(null) }

    val thumbnailRepository = remember {
        ThumbnailRepository(File(context.cacheDir, "thumbnails"), context)
    }

    val isRoot = currentLocation is ViewLocation.Home
    val isNas  = currentLocation is ViewLocation.NasFolder

    // ── 一覧のスクロール位置を覚えておく ──────────────────────
    // ビューワーへ移動するとこの画面は破棄されるため、戻ってくると一覧が先頭に戻ってしまう。
    // ViewModel（画面遷移をまたいで生き残る）に位置を預けて、戻ったときに復元する。
    val listState   = rememberLazyListState()
    val locationKey = currentLocation.key

    // 復元すべき位置は、保存処理が上書きする前に押さえておく
    val pendingRestore = remember(locationKey) { viewModel.getScrollPosition(locationKey) }
    var restoredKey    by remember { mutableStateOf<String?>(null) }

    // フォルダを移動したら検索状態はリセットする（検索対象は「今見ている場所」のみのため）
    LaunchedEffect(locationKey) {
        isSearchMode = false
        viewModel.setSearchQuery("")
    }

    LaunchedEffect(locationKey, files.isNotEmpty()) {
        if (files.isNotEmpty() && restoredKey != locationKey) {
            pendingRestore?.let { (index, offset) ->
                listState.scrollToItem(index, offset)
            }
            restoredKey = locationKey
        }
    }

    LaunchedEffect(locationKey, displayMode) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.saveScrollPosition(locationKey, index, offset)
        }
    }

    val screenTitle = when (val loc = currentLocation) {
        is ViewLocation.Home        -> "HOME"
        is ViewLocation.LocalFolder -> loc.folder.name
        is ViewLocation.SafFolder   -> loc.displayName
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

    LaunchedEffect(Unit) {
        viewModel.navigateToVolumes.collect { archivePath ->
            val encoded = URLEncoder.encode(archivePath, "UTF-8")
            navController.navigate("volumes/$encoded")
        }
    }

    // ファイルリストが変わったら状態を一括読み込み
    LaunchedEffect(files) {
        viewModel.loadFileStatuses(files)
    }

    // 権限は一切不要（アプリ専用フォルダ or SAFで選択したフォルダのみを扱うため）
    LaunchedEffect(Unit) {
        viewModel.loadInitialFolder()
    }

    BackHandler(enabled = isSearchMode || !isRoot || isEditMode) {
        if (isSearchMode) {
            isSearchMode = false
            viewModel.setSearchQuery("")
        } else if (isEditMode) {
            isEditMode    = false
            selectedPaths = emptySet()
        } else {
            isEditMode    = false
            selectedPaths = emptySet()
            viewModel.navigateUp()
        }
    }

    // このアプリについて
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // NASサーバー追加・編集ダイアログ
    if (showAddNasDialog) {
        AddNasServerDialog(
            onDismiss  = { showAddNasDialog = false; editingServer = null },
            onConfirm  = { server ->
                viewModel.addNasServer(server)
                showAddNasDialog = false
                editingServer = null
            },
            onListShares = { host, user, pass -> viewModel.listShares(host, user, pass) },
            editServer = editingServer
        )
    }

    // 再開ダイアログ
    dialogState?.let { state ->
        val repo = remember { LocalFileRepository() }
        val (title, _) = remember(state.fileItem.name) { repo.parseFileName(state.fileItem.name) }
        val isCached   = state.fileItem.file != null  // fileが設定されている = キャッシュ済み
        val hasResume  = state.savedPage > 0
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text  = {
                Text(
                    when {
                        !isCached  -> "読み込みを開始します"
                        hasResume  -> "前回の続きから読みますか？"
                        else       -> "最初から読みます"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeReading() }) {
                    Text(
                        when {
                            !isCached -> "最初から読む"
                            hasResume -> "${state.savedPage + 1} / ${state.totalPages}ページから再開"
                            else      -> "最初から読む"
                        }
                    )
                }
            },
            dismissButton = {
                // キャッシュ済みかつ読書位置ありの時のみ「最初から読む」を表示
                if (isCached && hasResume) {
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
            info             = info,
            onStatusChange   = { status -> viewModel.updateFileStatus(info.fileItem, status) },
            onRatingChange   = { rating -> viewModel.updateRating(info.fileItem, rating) },
            onColorLabelChange = { label -> viewModel.updateColorLabel(info.fileItem, label) },
            onDismiss        = { viewModel.dismissFileInfo() }
        )
    }

    // リモート項目の長押しメニュー（HOMEに登録/解除・情報）
    contextTarget?.let { target ->
        val alreadyBookmarked = viewModel.isBookmarked(target)
        AlertDialog(
            onDismissRequest = { contextTarget = null },
            title = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text  = {
                Column {
                    if (target.isNas) {
                        TextButton(
                            onClick  = {
                                viewModel.toggleBookmark(target)
                                contextTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector        = Lucide.Bookmark,
                                contentDescription = null,
                                modifier           = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text     = if (alreadyBookmarked) "HOMEから解除" else "HOMEに登録",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (target.isComic) {
                        TextButton(
                            onClick  = {
                                contextTarget = null
                                viewModel.openFileInfo(target)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Lucide.Info, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(text = "情報を見る", modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextTarget = null }) { Text("閉じる") }
            }
        )
    }

    // 削除確認ダイアログ
    if (showDeleteConfirm) {
        val selectedFileItems   = files.filter { it.path in selectedPaths }
        val selectedServerItems = nasServers.filter { it.id in selectedPaths }
        val totalCount = selectedFileItems.size + selectedServerItems.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("削除の確認") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${totalCount}件を削除します。この操作は元に戻せません。")
                    if (selectedServerItems.isNotEmpty()) {
                        Text(
                            text  = "リモート設定 ${selectedServerItems.size}件も含まれます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (selectedFileItems.isNotEmpty()) {
                        viewModel.deleteLocalFiles(selectedFileItems)
                    }
                    selectedServerItems.forEach { viewModel.deleteNasServer(it.id) }
                    isEditMode    = false
                    selectedPaths = emptySet()
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            }
        )
    }

    // NASエラーダイアログ
    nasError?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearNasError() },
            title = { Text("エラー") },
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
                    if (isSearchMode) {
                        OutlinedTextField(
                            value           = searchQuery,
                            onValueChange   = { viewModel.setSearchQuery(it) },
                            placeholder     = { Text("$screenTitle 内を検索") },
                            singleLine      = true,
                            modifier        = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            colors          = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor    = Color.Transparent
                            )
                        )
                        LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                    } else {
                    Text(
                        text     = screenTitle,
                        style    = if (isRoot) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    }
                },
                navigationIcon = {
                    if (!isRoot) {
                        var showNavMenu by remember { mutableStateOf(false) }

                        Box {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .combinedClickable(
                                        onClick     = {
                                            isEditMode    = false
                                            selectedPaths = emptySet()
                                            viewModel.navigateUp()
                                        },
                                        onLongClick = { showNavMenu = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Lucide.ArrowLeft, contentDescription = "戻る")
                            }
                            DropdownMenu(
                                expanded         = showNavMenu,
                                onDismissRequest = { showNavMenu = false }
                            ) {
                                // NASの場合のみ「サーバールートへ」のショートカットを表示
                                // （ローカル/SAFのルートはHOMEと同一なので不要）
                                val rootLabel = (currentLocation as? ViewLocation.NasFolder)?.server?.displayName
                                if (rootLabel != null) {
                                    DropdownMenuItem(
                                        text    = { Text(rootLabel) },
                                        onClick = {
                                            showNavMenu   = false
                                            isEditMode    = false
                                            selectedPaths = emptySet()
                                            (currentLocation as? ViewLocation.NasFolder)?.let {
                                                viewModel.navigateToNas(it.server, "")
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
                                // 下に表示：HOME
                                DropdownMenuItem(
                                    text    = { Text("HOME") },
                                    onClick = {
                                        showNavMenu   = false
                                        isEditMode    = false
                                        selectedPaths = emptySet()
                                        viewModel.navigateToHome()
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isEditMode) {
                        // 編集モード中：全選択ボタン
                        // ファイル・フォルダ（ローカル/SAFのみ）とリモートサーバーを対象に含める
                        val selectableLocal   = files.filter { !it.isNas }
                        val selectableServers = if (isRoot) nasServers.map { it.id } else emptyList()
                        val allSelectablePaths = selectableLocal.map { it.path } + selectableServers
                        TextButton(onClick = {
                            selectedPaths = if (selectedPaths.size == allSelectablePaths.size)
                                emptySet()
                            else
                                allSelectablePaths.toSet()
                        }) {
                            Text(if (selectedPaths.size == allSelectablePaths.size) "選択解除" else "全選択")
                        }
                        TextButton(onClick = {
                            isEditMode    = false
                            selectedPaths = emptySet()
                        }) { Text("完了") }
                    } else if (isSearchMode) {
                        // 検索中はソート・表示切替・編集を隠し、閉じるボタンだけ出す（誤操作防止・すっきり表示）
                        IconButton(onClick = {
                            isSearchMode = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Lucide.X, contentDescription = "検索を閉じる")
                        }
                    } else {
                        IconButton(onClick = { isSearchMode = true }) {
                            Icon(Lucide.Search, contentDescription = "検索")
                        }
                        // ソートボタン：現在のキーと昇降順を表示
                        val hasFilter = viewModel.hasActiveFilter
                        TextButton(onClick = { showSortSheet = true }) {
                            val sortColor = if (hasFilter) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                            Text(text = sortKey.label, color = sortColor)
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                imageVector        = if (ascending) Lucide.ArrowUp else Lucide.ArrowDown,
                                contentDescription = if (ascending) "昇順" else "降順",
                                tint               = sortColor,
                                modifier           = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = {
                            // 詳細 → コンパクト → 本棚 → 詳細 … と巡回する
                            displayMode = when (displayMode) {
                                com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.DETAIL  ->
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.COMPACT
                                com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.COMPACT ->
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.SHELF
                                com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.SHELF   ->
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.DETAIL
                            }
                            appPrefs.listDisplayMode = displayMode
                        }) {
                            Icon(
                                imageVector = when (displayMode) {
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.DETAIL  ->
                                        Lucide.List
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.COMPACT ->
                                        Lucide.LayoutGrid
                                    com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.SHELF   ->
                                        Lucide.BookOpen
                                },
                                contentDescription = "表示切替"
                            )
                        }
                        IconButton(onClick = {
                            isEditMode    = true
                            selectedPaths = emptySet()
                        }) {
                            Icon(Lucide.SquarePen, contentDescription = "編集")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column {
                // DLモード時に選択中ファイルがあれば「選択ファイルをダウンロード」ボタンを表示
                if (!isStreamingMode && isNas && dlSelectedPaths.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        androidx.compose.material3.Button(
                            onClick  = { viewModel.downloadSelected() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector        = Lucide.Download,
                                contentDescription = null,
                                modifier           = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("選択ファイルをダウンロード（${dlSelectedPaths.size}件）")
                        }
                    }
                    HorizontalDivider()
                }
                BottomAppBar {
                    Row(
                        modifier              = Modifier.fillMaxWidth().height(56.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        if (isEditMode && !isNas) {
                            val hasSelection = selectedPaths.isNotEmpty()
                            IconButton(
                                onClick = { if (hasSelection) showDeleteConfirm = true },
                                enabled = hasSelection
                            ) {
                                Icon(
                                    Lucide.Trash2,
                                    contentDescription = "削除",
                                    tint = if (hasSelection) MaterialTheme.colorScheme.error
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            }
                            Text(
                                text  = if (hasSelection) "${selectedPaths.size}件選択中" else "ファイルを選択してください",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // その他メニュー（閲覧履歴・このアプリについて）
                            Box {
                                var showMoreMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Lucide.EllipsisVertical, contentDescription = "その他")
                                }
                                DropdownMenu(
                                    expanded         = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text    = { Text("閲覧履歴") },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigate("history")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text    = { Text("このアプリについて") },
                                        onClick = {
                                            showMoreMenu = false
                                            showAboutDialog = true
                                        }
                                    )
                                }
                            }
                            IconButton(onClick = { showAddNasDialog = true }) {
                                Icon(Lucide.Server, contentDescription = "リモート")
                            }

                            if (isNas) {
                                IconButton(onClick = { viewModel.toggleMode() }) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector        = if (isStreamingMode) Lucide.Wifi
                                            else Lucide.Download,
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
                            }

                            IconButton(onClick = { viewModel.openTransferScreen() }) {
                                Icon(Lucide.ArrowUpDown, contentDescription = "転送状況")
                            }
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Lucide.Settings, contentDescription = "設定")
                            }
                        }
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
                        Text("リモートサーバーを読み込み中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (displayMode == com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.SHELF) {
                // ── 本棚モード（段ごとに独立した棚板を描き、本を棚に置いているように見せる） ───────────────
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val minTileWidth = 100.dp
                    val spacing      = 8.dp
                    val sidePadding  = 8.dp
                    val available    = maxWidth - sidePadding * 2
                    val columns      = ((available + spacing) / (minTileWidth + spacing)).toInt().coerceAtLeast(2)
                    val tileWidth    = (available - spacing * (columns - 1)) / columns

                    LazyColumn(
                        state    = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // ブックマーク（HOMEに登録したリモートのフォルダ・本）
                        if (isRoot && bookmarks.isNotEmpty()) {
                            item {
                                Text(
                                    text     = "ブックマーク",
                                    style    = MaterialTheme.typography.labelMedium,
                                    color    = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            itemsIndexed(bookmarks.chunked(columns)) { rowIndex, rowItems ->
                                ShelfRow(
                                    rowItems    = rowItems,
                                    columns     = columns,
                                    tileWidth   = tileWidth,
                                    rowIndex    = rowIndex,
                                    sidePadding = sidePadding,
                                    spacing     = spacing
                                ) { bm ->
                                    ShelfFileItem(
                                        fileItem            = bm,
                                        thumbnailRepository = thumbnailRepository,
                                        showTitle           = true,
                                        generateThumbnail   = false,
                                        onClick     = {
                                            if (bm.isFolder) viewModel.navigateToNas(bm.nasServer!!, bm.nasPath)
                                            else             viewModel.onComicTapped(bm)
                                        },
                                        onLongClick = { contextTarget = bm }
                                    )
                                }
                            }
                        }

                        // リモートサーバーも本棚モードでは他の本と同じく棚に並べる
                        if (isRoot && nasServers.isNotEmpty()) {
                            item {
                                Text(
                                    text     = "リモートサーバー",
                                    style    = MaterialTheme.typography.labelMedium,
                                    color    = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            itemsIndexed(nasServers.chunked(columns)) { rowIndex, rowItems ->
                                ShelfRow(
                                    rowItems    = rowItems,
                                    columns     = columns,
                                    tileWidth   = tileWidth,
                                    rowIndex    = rowIndex,
                                    sidePadding = sidePadding,
                                    spacing     = spacing
                                ) { server ->
                                    val isServerSelected = server.id in selectedPaths
                                    ShelfServerItem(
                                        server         = server,
                                        isEditMode     = isEditMode,
                                        isSelected     = isServerSelected,
                                        onToggleSelect = {
                                            selectedPaths = if (isServerSelected)
                                                selectedPaths - server.id
                                            else
                                                selectedPaths + server.id
                                        },
                                        onClick  = {
                                            isEditMode    = false
                                            selectedPaths = emptySet()
                                            viewModel.navigateToNas(server)
                                        },
                                        onEdit   = { editingServer = server; showAddNasDialog = true },
                                        onDelete = { viewModel.deleteNasServer(server.id) }
                                    )
                                }
                            }
                            item {
                                HorizontalDivider(
                                    thickness = 4.dp,
                                    color     = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }

                        if (files.isEmpty()) {
                            item {
                                Box(
                                    modifier         = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text  = "ファイルが見つかりません",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(files.chunked(columns)) { rowIndex, rowItems ->
                                ShelfRow(
                                    rowItems    = rowItems,
                                    columns     = columns,
                                    tileWidth   = tileWidth,
                                    rowIndex    = rowIndex,
                                    sidePadding = sidePadding,
                                    spacing     = spacing
                                ) { fileItem ->
                                    val itemStatus   = fileStatuses[fileItem.path] ?: ReadStatus.UNREAD
                                    val isSelected   = fileItem.path in selectedPaths
                                    val isCached     = fileItem.isNas && viewModel.isNasCached(fileItem)
                                    val isDlSelected = !isStreamingMode && !fileItem.isFolder &&
                                                       fileItem.path in dlSelectedPaths
                                    ShelfFileItem(
                                        fileItem            = fileItem,
                                        thumbnailRepository = thumbnailRepository,
                                        showTitle           = shelfShowTitle,
                                        status              = itemStatus,
                                        isEditMode          = isEditMode && !isNas,
                                        isSelected          = isSelected,
                                        isDlSelected        = isDlSelected,
                                        isCached            = isCached,
                                        onClick     = { handleFileClick(fileItem, isEditMode, isNas, isStreamingMode, isSelected, selectedPaths, viewModel, onSelectChange = { selectedPaths = it }) },
                                        onLongClick = {
                                            if (!isEditMode) {
                                                if (fileItem.isNas) contextTarget = fileItem
                                                else if (fileItem.isComic) viewModel.openFileInfo(fileItem)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // ブックマーク（HOMEに登録したリモートのフォルダ・本）
                    if (isRoot && bookmarks.isNotEmpty()) {
                        item {
                            Text(
                                text     = "ブックマーク",
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(bookmarks) { bm ->
                            val bmTap: () -> Unit = {
                                if (bm.isFolder) viewModel.navigateToNas(bm.nasServer!!, bm.nasPath)
                                else             viewModel.onComicTapped(bm)
                            }
                            if (displayMode == com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.COMPACT) {
                                CompactFileListItem(
                                    fileItem            = bm,
                                    thumbnailRepository = thumbnailRepository,
                                    generateThumbnail   = false,
                                    onClick     = bmTap,
                                    onLongClick = { contextTarget = bm }
                                )
                            } else {
                                FileListItem(
                                    fileItem            = bm,
                                    thumbnailRepository = thumbnailRepository,
                                    generateThumbnail   = false,
                                    onClick     = bmTap,
                                    onLongClick = { contextTarget = bm }
                                )
                            }
                            HorizontalDivider()
                        }
                        item {
                            HorizontalDivider(
                                thickness = 4.dp,
                                color     = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }

                    if (isRoot && nasServers.isNotEmpty()) {
                        item {
                            Text(
                                text     = "リモートサーバー",
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(nasServers) { server ->
                            val isServerSelected = server.id in selectedPaths
                            NasServerListItem(
                                server          = server,
                                isEditMode      = isEditMode,
                                isSelected      = isServerSelected,
                                onToggleSelect  = {
                                    selectedPaths = if (isServerSelected)
                                        selectedPaths - server.id
                                    else
                                        selectedPaths + server.id
                                },
                                onClick  = {
                                    isEditMode    = false
                                    selectedPaths = emptySet()
                                    viewModel.navigateToNas(server)
                                },
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
                                    text  = "ファイルが見つかりません",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(files) { fileItem ->
                            val itemStatus   = fileStatuses[fileItem.path] ?: ReadStatus.UNREAD
                            val isSelected   = fileItem.path in selectedPaths
                            val isCached     = fileItem.isNas && viewModel.isNasCached(fileItem)
                            val meta         = fileMetaMap[fileItem.path]
                            // DLモード時はファイルのみ選択状態色を適用
                            val isDlSelected = !isStreamingMode && !fileItem.isFolder &&
                                               fileItem.path in dlSelectedPaths
                            if (displayMode == com.kamneko88.comicveil.data.AppPrefs.ListDisplayMode.DETAIL) {
                                FileListItem(
                                    fileItem            = fileItem,
                                    thumbnailRepository = thumbnailRepository,
                                    status              = itemStatus,
                                    isEditMode          = isEditMode && !isNas,
                                    isSelected          = isSelected,
                                    isDlSelected        = isDlSelected,
                                    isCached            = isCached,
                                    rating              = meta?.rating ?: 0,
                                    colorLabel          = meta?.colorLabel ?: 0,
                                    onClick     = { handleFileClick(fileItem, isEditMode, isNas, isStreamingMode, isSelected, selectedPaths, viewModel, onSelectChange = { selectedPaths = it }) },
                                    onInfoClick = { if (!isEditMode) viewModel.openFileInfo(fileItem) },
                                    onLongClick = { if (!isEditMode && fileItem.isNas) contextTarget = fileItem }
                                )
                            } else {
                                CompactFileListItem(
                                    fileItem            = fileItem,
                                    thumbnailRepository = thumbnailRepository,
                                    status              = itemStatus,
                                    isEditMode          = isEditMode && !isNas,
                                    isSelected          = isSelected,
                                    isDlSelected        = isDlSelected,
                                    onClick     = { handleFileClick(fileItem, isEditMode, isNas, isStreamingMode, isSelected, selectedPaths, viewModel, onSelectChange = { selectedPaths = it }) },
                                    onInfoClick = { if (!isEditMode) viewModel.openFileInfo(fileItem) },
                                    onLongClick = { if (!isEditMode && fileItem.isNas) contextTarget = fileItem }
                                )
                            }
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
                                text  = "読み込みを中止する",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ── ソート・フィルター BottomSheet ──────────────────────────────
    if (showSortSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState       = sheetState,
            dragHandle       = { BottomSheetDefaults.DragHandle() }
        ) {
            SortFilterSheet(
                sortKey          = sortKey,
                ascending        = ascending,
                folderOrder      = folderOrder,
                statusFilter     = statusFilter,
                colorLabelFilter = colorLabelFilter,
                onSortKey        = { viewModel.setSortKey(it) },
                onFolderOrder    = { viewModel.setFolderOrder(it) },
                onStatusFilter   = { viewModel.toggleStatusFilter(it) },
                onColorFilter    = { viewModel.toggleColorLabelFilter(it) },
                onClearFilter    = { viewModel.clearFilters() },
                onClose          = { showSortSheet = false }
            )
        }
    }
}

// ─── このアプリについて ───────────────────────────────

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ComicVeil") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = "バージョン ${com.kamneko88.comicveil.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text  = "Android向けマンガ・コミックビューワー",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text(
                    text  = "ZIP / RAR / 7z / PDF に対応。リモートサーバー（SMB）の本を、" +
                            "ダウンロードしながら読めます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

// ─── ファイル情報ポップアップ ─────────────────────────────────────────────────

@Composable
fun FileInfoDialog(
    info: FileInfoState,
    onStatusChange: (ReadStatus) -> Unit,
    onRatingChange: (Int) -> Unit = {},
    onColorLabelChange: (Int) -> Unit = {},
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

                // ── 読書状態 ─────────────────────────────────────
                Text(
                    text  = "読書状態",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadStatus.entries.forEach { status ->
                        val isSelectable = status != ReadStatus.READING
                        FilterChip(
                            selected = info.status == status,
                            enabled  = isSelectable,
                            onClick  = { if (isSelectable) onStatusChange(status) },
                            label    = { Text(status.label, fontSize = 12.sp) }
                        )
                    }
                }

                HorizontalDivider()

                // ── レーティング ───────────────────────────────────────
                Text(
                    text  = "レーティング",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // なしボタン
                    FilterChip(
                        selected = info.rating == 0,
                        onClick  = { onRatingChange(0) },
                        label    = { Text("なし", fontSize = 12.sp) }
                    )
                    // 1〜5星
                    (1..5).forEach { star ->
                        IconButton(
                            onClick  = { onRatingChange(star) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Lucide.Star,
                                contentDescription = "star$star",
                                tint = if (star <= info.rating)
                                    Color(0xFFFFD700)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // ── カラーラベル ──────────────────────────────────────
                Text(
                    text  = "カラーラベル",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorLabel.entries.forEach { label ->
                        val isSelected = info.colorLabel == label.value
                        val labelColor = Color(label.colorHex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (label == ColorLabel.NONE)
                                        MaterialTheme.colorScheme.surfaceVariant
                                    else labelColor
                                )
                                .then(
                                    if (isSelected) Modifier.border(
                                        2.dp, Color.White,
                                        androidx.compose.foundation.shape.CircleShape
                                    ) else Modifier
                                )
                                .clickable { onColorLabelChange(label.value) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector        = Lucide.Check,
                                    contentDescription = label.label,
                                    tint               = if (label == ColorLabel.NONE)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
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

private fun formatLastModified(lastModified: Long): String {
    val now      = System.currentTimeMillis()
    val diffDays = ((now - lastModified) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        diffDays == 0 -> "今日"
        diffDays == 1 -> "昨日"
        diffDays <= 99 -> "${diffDays}日前"
        else -> java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(lastModified))
    }
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

// ─── NASキャッシュバッジ ─────────────────────────────────────────────────────

@Composable
fun CachedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text     = "Cached",
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onTertiaryContainer,
            fontSize = 10.sp
        )
    }
}

@Composable
fun BookmarkBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Lucide.Bookmark,
                contentDescription = null,
                modifier           = Modifier.size(10.dp),
                tint               = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text     = "HOME",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 10.sp
            )
        }
    }
}

// ─── NASサーバーリストアイテム ────────────────────────────────────────────────

@Composable
fun NasServerListItem(
    server: NasServer,
    onClick: () -> Unit,
    onEdit: (NasServer) -> Unit,
    onDelete: (NasServer) -> Unit,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    var showMenu   by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete && !isEditMode) {
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

    val backgroundColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else Color.Transparent

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable { if (isEditMode) onToggleSelect() else onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 編集モード時はチェックボックスを表示
        if (isEditMode) {
            Icon(
                imageVector        = if (isSelected) Lucide.SquareCheck
                                     else Lucide.Square,
                contentDescription = null,
                modifier           = Modifier.size(24.dp).padding(end = 4.dp),
                tint               = if (isSelected) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector        = Lucide.Server,
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
                    Lucide.EllipsisVertical,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    fileItem: FileItem,
    thumbnailRepository: ThumbnailRepository,
    onClick: () -> Unit,
    onInfoClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    status: ReadStatus = ReadStatus.UNREAD,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    isDlSelected: Boolean = false,
    isCached: Boolean = false,
    rating: Int = 0,
    colorLabel: Int = 0,
    generateThumbnail: Boolean = true
) {
    val repository = remember { LocalFileRepository() }
    val (title, author) = remember(fileItem.name) { repository.parseFileName(fileItem.name) }

    var thumbnailFile by remember(fileItem.path) { mutableStateOf<File?>(null) }
    LaunchedEffect(fileItem.path, generateThumbnail) {
        if (generateThumbnail) {
            thumbnailFile = thumbnailRepository.getOrGenerateThumbnail(fileItem)
        }
    }

    val backgroundColor = when {
        isSelected   -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isDlSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        else         -> Color.Transparent
    }

    val labelColor = ColorLabel.fromValue(colorLabel)

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // カラーラベル左端ライン
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(92.dp)
                .background(
                    if (labelColor != ColorLabel.NONE) Color(labelColor.colorHex)
                    else Color.Transparent
                )
        )

        // 編集モード時：チェックボックス
        if (isEditMode) {
            Icon(
                imageVector        = if (isSelected) Lucide.SquareCheck
                                     else Lucide.Square,
                contentDescription = null,
                modifier           = Modifier
                    .padding(start = 4.dp)
                    .size(24.dp),
                tint               = if (isSelected) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // サムネイル
        Box(
            modifier         = Modifier
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
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
                        FileItemType.FOLDER -> Lucide.Folder
                        else                -> Lucide.File
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

        // テキスト情報
        Column(
            modifier            = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
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
                        fileItem.isFolder -> if (fileItem.isNas) "リモートフォルダ" else "フォルダ"
                        else              -> fileItem.extension.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fileItem.isComic && status != ReadStatus.UNREAD) {
                    ReadStatusBadge(status = status)
                }
                if (isCached) {
                    CachedBadge()
                }
                if (fileItem.isRemoteBookmark) {
                    BookmarkBadge()
                }
            }
            // 更新日
            if (fileItem.lastModified > 0 && !fileItem.isFolder) {
                Text(
                    text  = formatLastModified(fileItem.lastModified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // レーティング表示（右端）
        if (rating > 0 && fileItem.isComic) {
            Column(
                modifier            = Modifier.padding(end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                (1..rating).forEach { _ ->
                    Icon(
                        imageVector        = Lucide.Star,
                        contentDescription = null,
                        tint               = Color(0xFFFFD700),
                        modifier           = Modifier.size(10.dp)
                    )
                }
            }
        }

        // 情報ボタン
        if (fileItem.isComic) {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector        = Lucide.Info,
                    contentDescription = "ファイル情報",
                    modifier           = Modifier.size(20.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── ファイルクリック共通ハンドラ ──────────────────────────────────────────────

private fun handleFileClick(
    fileItem: FileItem,
    isEditMode: Boolean,
    isNas: Boolean,
    isStreamingMode: Boolean,
    isSelected: Boolean,
    selectedPaths: Set<String>,
    viewModel: HomeViewModel,
    onSelectChange: (Set<String>) -> Unit
) {
    when {
        isEditMode && !isNas -> {
            onSelectChange(
                if (isSelected) selectedPaths - fileItem.path
                else selectedPaths + fileItem.path
            )
        }
        !isStreamingMode && isNas && !fileItem.isFolder -> {
            viewModel.toggleDlSelection(fileItem.path)
        }
        fileItem.isFolder -> {
            when {
                fileItem.isNas -> viewModel.navigateToNas(fileItem.nasServer!!, fileItem.nasPath)
                fileItem.isSaf -> viewModel.loadSafFolder(fileItem.uri!!, fileItem.name)
                else           -> viewModel.loadFolder(fileItem.file!!)
            }
        }
        fileItem.isComic -> viewModel.onComicTapped(fileItem)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactFileListItem(
    fileItem: FileItem,
    thumbnailRepository: ThumbnailRepository,
    onClick: () -> Unit,
    onInfoClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    status: ReadStatus = ReadStatus.UNREAD,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    isDlSelected: Boolean = false,
    generateThumbnail: Boolean = true
) {
    var thumbnailFile by remember(fileItem.path) { mutableStateOf<File?>(null) }
    LaunchedEffect(fileItem.path, generateThumbnail) {
        if (generateThumbnail) {
            thumbnailFile = thumbnailRepository.getOrGenerateThumbnail(fileItem)
        }
    }

    val backgroundColor = when {
        isSelected   -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isDlSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        else         -> Color.Transparent
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() })
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 編集モード：チェックボックス
        if (isEditMode) {
            Icon(
                imageVector        = if (isSelected) Lucide.SquareCheck
                                     else Lucide.Square,
                contentDescription = null,
                modifier           = Modifier.size(20.dp).padding(end = 4.dp),
                tint               = if (isSelected) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // サムネイル（小）
        Box(
            modifier = Modifier
                .size(40.dp)
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
                        FileItemType.FOLDER -> Lucide.Folder
                        else                -> Lucide.File
                    },
                    contentDescription = null,
                    modifier           = Modifier.size(24.dp),
                    tint               = when (fileItem.type) {
                        FileItemType.FOLDER -> MaterialTheme.colorScheme.primary
                        else -> if (fileItem.isNas) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.secondary
                    }
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // ファイル名（1行）
        Text(
            text     = fileItem.name,
            style    = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // ブックマークバッジ
        if (fileItem.isRemoteBookmark) {
            Spacer(Modifier.width(4.dp))
            BookmarkBadge()
        }

        // 読書状態バッジ（読書中/既読のみ）
        if (fileItem.isComic && status != ReadStatus.UNREAD) {
            Spacer(Modifier.width(4.dp))
            ReadStatusBadge(status = status)
        }

        // 情報ボタン
        if (fileItem.isComic) {
            IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector        = Lucide.Info,
                    contentDescription = "ファイル情報",
                    modifier           = Modifier.size(16.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── 本棚モードの「段」（壁面＋棚板） ────────────────────────────────────

/**
 * 本棚モードの1段分。
 *
 * 「壁面（本を立てる背景）」＋「棚板（前面の縁。ハイライトと影で厚みを表現）」の
 * 2層構造で1段を組み立てる。LazyColumnのitemとして段を積み重ねることで、
 * 実際の本棚のように棚同士が隙間なく連続する。
 * アダプティブグリッドと違い列数・タイル幅を呼び出し側で確定させているので、
 * 棚板が必ず本の行の真下に揃う。
 *
 * 段ごとに壁面の色味をわずかに変えて単調さを避けている（rowIndexで交互に切り替え）。
 */
@Composable
private fun <T> ShelfRow(
    rowItems: List<T>,
    columns: Int,
    tileWidth: Dp,
    rowIndex: Int,
    sidePadding: Dp,
    spacing: Dp,
    itemContent: @Composable (T) -> Unit
) {
    val wallColors = if (rowIndex % 2 == 0) {
        listOf(Color(0xFF8A5A34), Color(0xFF6B4327))
    } else {
        listOf(Color(0xFF7E5230), Color(0xFF603D22))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 壁面（本を立てるスペース）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(wallColors))
                .padding(horizontal = sidePadding, vertical = 10.dp),
            verticalAlignment       = Alignment.Bottom,
            horizontalArrangement   = Arrangement.spacedBy(spacing)
        ) {
            rowItems.forEach { item ->
                Box(modifier = Modifier.width(tileWidth)) {
                    itemContent(item)
                }
            }
            repeat(columns - rowItems.size) {
                Spacer(modifier = Modifier.width(tileWidth))
            }
        }
        // 棚板（前面の縁。上端ハイライト＋下に落ちる影で厚みを表現）
        ShelfBoard()
    }
}

@Composable
private fun ShelfBoard() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFC9986A), Color(0xFFA06F42), Color(0xFF7A4F2C))
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.White.copy(alpha = 0.5f))
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
        )
    }
}

// ─── 本棚タイル ────────────────────────────────────

/**
 * 本棚モードの1タイル。
 *
 * 「棚を眺める」ための画面なので、見せるのは表紙だけ。
 * カラーラベルやレーティングなどの細かい情報は長押しのファイル情報で確認する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfFileItem(
    fileItem: FileItem,
    thumbnailRepository: ThumbnailRepository,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showTitle: Boolean = false,
    status: ReadStatus = ReadStatus.UNREAD,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    isDlSelected: Boolean = false,
    isCached: Boolean = false,
    generateThumbnail: Boolean = true
) {
    val repository = remember { LocalFileRepository() }
    val (title, _) = remember(fileItem.name) { repository.parseFileName(fileItem.name) }

    var thumbnailFile by remember(fileItem.path) { mutableStateOf<File?>(null) }
    LaunchedEffect(fileItem.path, generateThumbnail) {
        if (generateThumbnail) {
            thumbnailFile = thumbnailRepository.getOrGenerateThumbnail(fileItem)
        }
    }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)   // 本の形
                .shadow(elevation = 5.dp, shape = RoundedCornerShape(6.dp), clip = false)  // 棚に置かれている落ち影
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (fileItem.isFolder) {
                // フォルダは表紙がないのでアイコンを中央に
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector        = Lucide.Folder,
                        contentDescription = null,
                        modifier           = Modifier.size(48.dp),
                        tint               = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (thumbnailFile != null) {
                AsyncImage(
                    model              = thumbnailFile,
                    contentDescription = title,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector        = Lucide.File,
                        contentDescription = null,
                        modifier           = Modifier.size(36.dp),
                        tint               = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // 読書状態バッジ（左上）
            if (fileItem.isComic && status != ReadStatus.UNREAD) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    ReadStatusBadge(status = status)
                }
            }

            // キャッシュ済みバッジ（右上）
            if (isCached) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    CachedBadge()
                }
            }

            // ブックマークバッジ（左上）
            if (fileItem.isRemoteBookmark) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    BookmarkBadge()
                }
            }

            // 選択状態の色重ね
            val overlayColor = when {
                isSelected   -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                isDlSelected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                else         -> Color.Transparent
            }
            if (overlayColor != Color.Transparent) {
                Box(Modifier.fillMaxSize().background(overlayColor))
            }

            // 編集モード：チェック（右下）
            if (isEditMode) {
                Icon(
                    imageVector        = if (isSelected) Lucide.SquareCheck
                                         else Lucide.Square,
                    contentDescription = null,
                    modifier           = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(22.dp),
                    tint               = if (isSelected) MaterialTheme.colorScheme.primary
                                         else Color.White
                )
            }
        }

        if (showTitle) {
            // 木目背景の上に乗るため、視認性重視で白文字＋影を付ける
            Text(
                text      = if (fileItem.isFolder) fileItem.name else title,
                style     = MaterialTheme.typography.labelSmall.copy(
                    color  = Color.White,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 4f)
                ),
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 本棚モードでのリモートサーバータイル。
 * ShelfFileItemと見た目を揃え（本の形のタイル＋落ち影）、サーバーアイコンを中央に表示する。
 * 編集・削除は長押しメニューから（一覧表示のNasServerListItemの「編集」「削除」と同じ操作先）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfServerItem(
    server: NasServer,
    onClick: () -> Unit,
    onEdit: (NasServer) -> Unit,
    onDelete: (NasServer) -> Unit,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    var showMenu           by remember { mutableStateOf(false) }
    var showDeleteConfirm  by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("サーバーを削除") },
            text  = { Text("「${server.displayName}」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete(server) }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            }
        )
    }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(
                onClick     = { if (isEditMode) onToggleSelect() else onClick() },
                onLongClick = { if (!isEditMode) showMenu = true }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)
                .shadow(elevation = 5.dp, shape = RoundedCornerShape(6.dp), clip = false)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector        = Lucide.Server,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                    tint               = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // 選択状態の色重ね（編集モード）
            if (isSelected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
            }

            if (isEditMode) {
                Icon(
                    imageVector        = if (isSelected) Lucide.SquareCheck else Lucide.Square,
                    contentDescription = null,
                    modifier           = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(22.dp),
                    tint               = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
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
                    onClick = { showMenu = false; showDeleteConfirm = true }
                )
            }
        }

        Text(
            text      = server.displayName,
            style     = MaterialTheme.typography.labelSmall.copy(
                color  = Color.White,
                shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 4f)
            ),
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(top = 4.dp)
        )
    }
}

// ─── ソート・フィルターシート ────────────────────────────────────────────────

@Composable
fun SortFilterSheet(
    sortKey: SortPrefs.SortKey,
    ascending: Boolean,
    folderOrder: SortPrefs.FolderOrder,
    statusFilter: Set<String>,
    colorLabelFilter: Set<String>,
    onSortKey: (SortPrefs.SortKey) -> Unit,
    onFolderOrder: (SortPrefs.FolderOrder) -> Unit,
    onStatusFilter: (String) -> Unit,
    onColorFilter: (String) -> Unit,
    onClearFilter: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ── ソート方法 ───────────────────────────────────────
        Text(
            text  = "ソート",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortPrefs.SortKey.entries.forEach { key ->
                val isSelected = sortKey == key
                FilterChip(
                    selected = isSelected,
                    onClick  = { onSortKey(key) },
                    label    = { Text(key.label) },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector        = if (ascending) Lucide.ArrowUp else Lucide.ArrowDown,
                                contentDescription = if (ascending) "昇順" else "降順",
                                modifier           = Modifier.size(16.dp)
                            )
                        }
                    } else null
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ── フォルダ表示順 ─────────────────────────────────
        Text(
            text  = "フォルダ表示順",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortPrefs.FolderOrder.entries.forEach { order ->
                FilterChip(
                    selected = folderOrder == order,
                    onClick  = { onFolderOrder(order) },
                    label    = { Text(order.label) }
                )
            }
        }

        // ── フィルター（ローカル・NAS共通） ───────────────────────────
        run {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text  = "フィルター",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (statusFilter.isNotEmpty() || colorLabelFilter.isNotEmpty()) {
                    TextButton(onClick = onClearFilter) { Text("リセット") }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 読書状態
            Text(
                text  = "読書状態",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadStatus.entries.forEach { status ->
                    FilterChip(
                        selected = status.name in statusFilter,
                        onClick  = { onStatusFilter(status.name) },
                        label    = { Text(status.label) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // カラーラベル
            Text(
                text  = "カラーラベル",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorLabel.entries
                    .filter { it != ColorLabel.NONE }
                    .forEach { label ->
                        val isSelected = label.value.toString() in colorLabelFilter
                        val labelColor = Color(label.colorHex)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(labelColor)
                                .then(
                                    if (isSelected) Modifier.border(
                                        3.dp, MaterialTheme.colorScheme.onSurface,
                                        androidx.compose.foundation.shape.CircleShape
                                    ) else Modifier
                                )
                                .clickable { onColorFilter(label.value.toString()) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector        = Lucide.Check,
                                    contentDescription = label.label,
                                    tint               = Color.White,
                                    modifier           = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick  = onClose,
            modifier = Modifier.align(Alignment.End)
        ) { Text("閉じる") }
        Spacer(Modifier.height(16.dp))
    }
}
