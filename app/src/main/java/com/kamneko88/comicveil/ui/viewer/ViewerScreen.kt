package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.view.doOnLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import kotlinx.coroutines.launch
import com.kamneko88.comicveil.MainActivity
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val viewModel: ViewerViewModel = viewModel(
        factory = ViewerViewModel.Factory(
            application = context.applicationContext as Application,
            filePath = filePath
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    var menuVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val activity = remember { context as? ComponentActivity }

    // 明るさはアプリ全体で覚える（作品別ではない）。
    // 未設定のうちは端末の明るさに従い、画面の明るさには手を出さない。
    val savedBrightness = remember { appPrefsForBrightness(context) }
    val systemBrightness = remember {
        runCatching {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    var brightness        by remember { mutableFloatStateOf(if (savedBrightness >= 0f) savedBrightness else systemBrightness) }
    var brightnessApplied by remember { mutableStateOf(savedBrightness >= 0f) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showBookmarkList     by remember { mutableStateOf(false) }
    var showPageStrip        by remember { mutableStateOf(false) }
    var orientationLocked    by remember { mutableStateOf(false) }

    // 明るさを調整したら、少しして自動で引っ込める。
    // 出しっぱなしだとページ移動スライダーと近くて誤タップのもとになるし、画面もうるさい。
    LaunchedEffect(showBrightnessSlider, brightness) {
        if (!showBrightnessSlider) return@LaunchedEffect
        kotlinx.coroutines.delay(BRIGHTNESS_AUTO_HIDE_MS)
        showBrightnessSlider = false
    }

    // 表示用のファイル名。
    // リモートの本はキャッシュ上で機械的な名前になっているので、
    // ViewModelが控えておいた元の作品名を使う（一時ファイル名は内部でだけ使う）。
    val displayFileName = uiState.displayName.ifEmpty {
        val parts = filePath.split("##vol##")
        val base  = java.io.File(parts[0]).name
        if (parts.size > 1) "$base - ${parts[1]}" else base
    }

    var isAnyPageZoomed by remember { mutableStateOf(false) }

    val appPrefs          = remember { com.kamneko88.comicveil.data.AppPrefs(context) }
    val isReverseLayout   = appPrefs.pageDirection == com.kamneko88.comicveil.data.AppPrefs.PageDirection.RIGHT_TO_LEFT
    val pageAnimation     = appPrefs.pageAnimation
    val volumeKeyPageTurn = appPrefs.volumeKeyPageTurn
    val zoomBounce        = appPrefs.zoomBounce
    val doubleTapScale    = appPrefs.doubleTapZoom.scale
    val trimMode          = appPrefs.trimMode
    val trimKeepAspect    = appPrefs.trimKeepAspect
    val pageTurnAnimation = appPrefs.pageTurnAnimation
    val spreadGutter      = appPrefs.spreadGutter.percent

    // 背景色（ページの周りの色）
    val backgroundColor = when (appPrefs.backgroundColor) {
        com.kamneko88.comicveil.data.AppPrefs.BackgroundColor.BLACK -> Color.Black
        com.kamneko88.comicveil.data.AppPrefs.BackgroundColor.WHITE -> Color.White
        com.kamneko88.comicveil.data.AppPrefs.BackgroundColor.GRAY  -> Color(0xFF808080)
    }

    // 見開き表示を使うか（設定＋画面の向きで決まる）
    val configuration = LocalConfiguration.current
    val isLandscape   = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val spreadEnabled = when (appPrefs.spreadMode) {
        com.kamneko88.comicveil.data.AppPrefs.SpreadMode.OFF       -> false
        com.kamneko88.comicveil.data.AppPrefs.SpreadMode.LANDSCAPE -> isLandscape
        com.kamneko88.comicveil.data.AppPrefs.SpreadMode.ALWAYS    -> true
    }
    val spreadCoverSingle = appPrefs.spreadCoverSingle

    LaunchedEffect(Unit) { viewModel.loadBookmarks() }

    LaunchedEffect(brightness, brightnessApplied) {
        if (!brightnessApplied) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            activity?.window?.let { window ->
                val attrs = window.attributes
                attrs.screenBrightness = brightness
                window.attributes = attrs
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                val attrs = window.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
            // 画面の向きロックを解除してから抜ける
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 閲覧中はシステムUI（ステータスバー・ナビゲーションバー）を隠して画像に没入できるようにし、
    // メニュー表示中だけ戻す（Comic Glassと同じ挙動）。
    //
    // 【重要】ビューワーの背景は黒なので、システムバーのアイコンは白にする必要がある。
    // enableEdgeToEdge() は端末のテーマに合わせて「明るい背景用＝黒いアイコン」を選ぶことがあり、
    // そのままだと黒背景に黒アイコンで時刻やWi-Fiが見えなくなる（バッテリーのような
    // 塗りつぶし表示だけが見える状態になる）。
    DisposableEffect(Unit) {
        val window     = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }

        val previousLightStatusBars = controller?.isAppearanceLightStatusBars ?: false
        val previousLightNavBars     = controller?.isAppearanceLightNavigationBars ?: false

        controller?.isAppearanceLightStatusBars     = false  // 白いアイコンにする
        controller?.isAppearanceLightNavigationBars = false

        onDispose {
            controller?.isAppearanceLightStatusBars     = previousLightStatusBars
            controller?.isAppearanceLightNavigationBars = previousLightNavBars
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(menuVisible) {
        val window     = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)

        // 黒背景の上に出すので、常に白いアイコンにしておく
        controller.isAppearanceLightStatusBars     = false
        controller.isAppearanceLightNavigationBars = false

        if (menuVisible) {
            // 通常のシステムバーとして表示する（時刻・Wi-Fi・電波等を含む完全な表示）
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val density = view.resources.displayMetrics.density
            val edgePx  = (40 * density).toInt()
            view.doOnLayout {
                val h = view.height.takeIf { it > 0 } ?: 2000
                val w = view.width.takeIf  { it > 0 } ?: 1080
                view.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, edgePx, h),
                    android.graphics.Rect(w - edgePx, 0, w, h)
                )
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.systemGestureExclusionRects = emptyList()
            }
        }
    }

    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        viewModel.pageLimitEvent.collect { event ->
            val message = when (event) {
                PageLimitEvent.FIRST -> "先頭ページです"
                PageLimitEvent.LAST  -> "最終ページです"
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    when {
        uiState.isLoading || !uiState.isSavedPageLoaded -> {
            val fileName = java.io.File(filePath).name
            Box(
                Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text  = fileName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = "読み込み中...",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── パスワード入力が必要 ────────────────────────────────────────────
        uiState.needsPassword -> {
            var password by remember { mutableStateOf("") }
            var showPassword by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("パスワードを入力") },
                text  = {
                    Column {
                        Text(
                            text  = "このZIPファイルはパスワードで保護されています。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value         = password,
                            onValueChange = { password = it },
                            label         = { Text("パスワード") },
                            singleLine    = true,
                            visualTransformation = if (showPassword)
                                VisualTransformation.None
                            else
                                PasswordVisualTransformation(),
                            trailingIcon  = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff
                                                      else Icons.Default.Visibility,
                                        contentDescription = if (showPassword) "隠す" else "表示"
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { if (password.isNotEmpty()) viewModel.retryWithPassword(password) }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick  = { if (password.isNotEmpty()) viewModel.retryWithPassword(password) },
                        enabled  = password.isNotEmpty()
                    ) { Text("開く") }
                },
                dismissButton = {
                    TextButton(onClick = onClose) { Text("キャンセル") }
                }
            )
        }

        uiState.error != null -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("読み込みエラー") },
                text  = { Text("ファイルを開けませんでした。\n${uiState.error}") },
                confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } }
            )
        }

        uiState.pages.isEmpty() && uiState.pageFiles.isEmpty() -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("非対応のファイル形式") },
                text  = {
                    Text(
                        "画像ページが見つかりませんでした。\n\n" +
                        "ZIPの中にZIP・RARが入っている\n「二重圧縮」ファイルには対応していません。"
                    )
                },
                confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } }
            )
        }

        else -> {
            val pages         = uiState.pages
            val pageFiles     = uiState.pageFiles
            val isProgressive = uiState.isProgressiveMode
            val totalCount    = if (uiState.isComplete) uiState.totalPageCount
                                else maxOf(uiState.availablePageCount, uiState.totalPageCount)
            val pagerCount    = when {
                isProgressive && totalCount > 0 -> totalCount
                isProgressive                   -> uiState.availablePageCount.coerceAtLeast(1)
                else                            -> pages.size
            }.coerceAtLeast(1)

            // 見開きの組（各要素は1ページ or 2ページ）。見開きがOFFなら、
            // 横長ページ（見開きの1枚絵）を検出して自動で仮想2分割する（見開き分割機能）。
            // 見開き表示がONのときは見開き分割を行わない（見開き表示が優先）。
            val widePages = uiState.widePages
            val (spreads, pageHalves) = remember(pagerCount, spreadEnabled, spreadCoverSingle, widePages, isReverseLayout) {
                if (spreadEnabled) {
                    val grouped = buildSpreads(pagerCount, spreadEnabled, spreadCoverSingle)
                    grouped to List(grouped.size) { PageHalf.FULL }
                } else {
                    buildSplitSpreads(pagerCount, widePages, rightFirst = isReverseLayout)
                }
            }
            // ページ番号 → そのページが入っている仮想位置（先に現れる方を採用。見開き分割時は「1つ目の半分」になる）
            val spreadOfPage = remember(spreads, pagerCount) {
                val seen = BooleanArray(pagerCount)
                IntArray(pagerCount).also { arr ->
                    spreads.forEachIndexed { spreadIdx, pagesInSpread ->
                        pagesInSpread.forEach { p ->
                            if (p in 0 until pagerCount && !seen[p]) { arr[p] = spreadIdx; seen[p] = true }
                        }
                    }
                }
            }

            val pagerState = rememberPagerState(
                initialPage = spreadOfPage.getOrElse(uiState.initialPage.coerceIn(0, pagerCount - 1)) { 0 },
                pageCount   = { spreads.size }
            )

            // 現在表示している見開きの先頭ページ（進捗保存・ブックマーク・スライダーはページ単位で扱う）
            val currentPageIndex = spreads.getOrNull(pagerState.currentPage)?.firstOrNull() ?: 0

            LaunchedEffect(pagerState.currentPage) { viewModel.savePage(currentPageIndex) }

            var hasScrolled by remember { mutableStateOf(false) }
            LaunchedEffect(pagerState.isScrollInProgress) {
                if (pagerState.isScrollInProgress) {
                    hasScrolled = true
                } else if (hasScrolled) {
                    when (pagerState.currentPage) {
                        0                -> viewModel.onPageLimitReached(PageLimitEvent.FIRST)
                        spreads.size - 1 -> viewModel.onPageLimitReached(PageLimitEvent.LAST)
                    }
                }
            }

            DisposableEffect(volumeKeyPageTurn, spreads.size) {
                val mainActivity = context as? MainActivity
                if (mainActivity != null && volumeKeyPageTurn) {
                    mainActivity.volumeKeyListener = { keyCode ->
                        when (keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                scope.launch {
                                    val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                                    pagerState.animateScrollToPage(target)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                scope.launch {
                                    val target = (pagerState.currentPage + 1).coerceAtMost(spreads.size - 1)
                                    pagerState.animateScrollToPage(target)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
                onDispose {
                    (context as? MainActivity)?.volumeKeyListener = null
                }
            }

            val springFling = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = if (!pageTurnAnimation) {
                    // アニメーションOFF：即座に切り替える（非力な端末向け）
                    snap()
                } else if (pageAnimation) {
                    spring(
                        dampingRatio = Spring.DampingRatioHighBouncy,
                        stiffness    = Spring.StiffnessVeryLow
                    )
                } else {
                    tween(durationMillis = 200)
                }
            )

            Scaffold(
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData   = data,
                            containerColor = Color.Black.copy(alpha = 0.75f),
                            contentColor   = Color.White,
                            modifier       = Modifier.padding(bottom = 80.dp)
                        )
                    }
                },
                containerColor = backgroundColor
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                ) {
                    HorizontalPager(
                        state                   = pagerState,
                        userScrollEnabled       = !isAnyPageZoomed && !menuVisible,
                        modifier                = Modifier.fillMaxSize(),
                        reverseLayout           = isReverseLayout,
                        flingBehavior           = springFling,
                        beyondViewportPageCount = 2
                    ) { spreadIndex ->
                        val pagesInSpread = spreads.getOrNull(spreadIndex) ?: emptyList()
                        // 展開済みのページだけを集める（未展開なら空）
                        val models: List<Any> = pagesInSpread.mapNotNull { p ->
                            when {
                                isProgressive && p < pageFiles.size -> pageFiles[p]
                                !isProgressive && p < pages.size    -> pages[p]
                                else                                -> null
                            }
                        }

                        if (models.isNotEmpty()) {
                            ZoomablePage(
                                imageModels    = models,
                                pageIndex      = spreadIndex,
                                currentPage    = pagerState.currentPage,
                                isScrolling    = pagerState.isScrollInProgress,
                                zoomBounce     = zoomBounce,
                                doubleTapScale = doubleTapScale,
                                trimMode       = trimMode,
                                trimKeepAspect = trimKeepAspect,
                                spreadGutter   = spreadGutter,
                                reverseLayout  = isReverseLayout,
                                pageHalf       = pageHalves.getOrElse(spreadIndex) { PageHalf.FULL },
                                onMenuToggle   = { menuVisible = !menuVisible },
                                onPageLimit    = { viewModel.onPageLimitReached(it) },
                                isFirst        = spreadIndex == 0,
                                isLast         = spreadIndex == spreads.size - 1,
                                onZoomChanged  = { isAnyPageZoomed = it }
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text  = "読み込み中... (${(pagesInSpread.firstOrNull() ?: 0) + 1}ページ)",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // メニュー表示中の暗転オーバーレイ。
                    // ここでタッチを受け止めるので、メニュー中に下のページへ操作が素通りしない（誤操作防止）。
                    // 背景をタップするとメニューを閉じる。
                    AnimatedVisibility(visible = menuVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .pointerInput(Unit) {
                                    detectTapGestures { menuVisible = false }
                                }
                        )
                    }

                    if (showBookmarkList) {
                        val bookmarks = uiState.bookmarks
                        AlertDialog(
                            onDismissRequest = { showBookmarkList = false },
                            title = {
                                Row(
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier              = Modifier.fillMaxWidth()
                                ) {
                                    Text("ブックマーク")
                                    if (bookmarks.isNotEmpty()) {
                                        TextButton(onClick = { viewModel.deleteAllBookmarks() }) {
                                            Text("全削除", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            },
                            text = {
                                if (bookmarks.isEmpty()) {
                                    Text("ブックマークはまだ登録されていません")
                                } else {
                                    LazyColumn {
                                        items(bookmarks) { bookmark ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier          = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showBookmarkList = false
                                                        scope.launch {
                                                            pagerState.animateScrollToPage(
                                                                spreadOfPage.getOrElse(bookmark.page) { 0 }
                                                            )
                                                        }
                                                    }
                                                    .padding(vertical = 8.dp)
                                            ) {
                                                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray)) {
                                                    if (!isProgressive && bookmark.page in pages.indices) {
                                                        AsyncImage(model = pages[bookmark.page], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                    } else if (isProgressive && bookmark.page < pageFiles.size) {
                                                        AsyncImage(model = pageFiles[bookmark.page], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(text = "${bookmark.page + 1}ページ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                                IconButton(onClick = { viewModel.deleteBookmark(bookmark.page) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBookmarkList = false }) { Text("閉じる") } }
                        )
                    }

                    // 上部：本を閉じる（幅広のピルボタン）
                    AnimatedVisibility(
                        visible  = menuVisible,
                        enter    = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                        exit     = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Button(
                            onClick = onClose,
                            shape   = RoundedCornerShape(28.dp),
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = Color.Black.copy(alpha = 0.6f),
                                contentColor   = Color.White
                            ),
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(16.dp)
                                .fillMaxWidth(0.85f)
                                .height(52.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Text(text = "  本を閉じる", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // 下部：ページ情報＋ページ移動スライダー＋操作バー（左右2グループ）
                    AnimatedVisibility(
                        visible  = menuVisible,
                        enter    = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
                        exit     = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        val maxSlider = (pagerCount - 1).toFloat().coerceAtLeast(0f)
                        // 移動先プレビュー（サムネイルの中央・スライダー・現在ページを一元管理する）
                        var previewPage by remember { mutableIntStateOf(currentPageIndex) }
                        var sliderValue by remember { mutableFloatStateOf((pagerCount - 1 - currentPageIndex).toFloat().coerceIn(0f, maxSlider)) }
                        val sliderTargetPage by remember { derivedStateOf { (pagerCount - 1 - sliderValue.toInt()).coerceIn(0, pagerCount - 1) } }
                        var isSliding by remember { mutableStateOf(false) }

                        // ページがめくられたら移動先プレビューも追従させる
                        LaunchedEffect(currentPageIndex) { previewPage = currentPageIndex }

                        // 移動先プレビュー（サムネイルを流したとき等）に合わせてスライダーを同期させる
                        LaunchedEffect(previewPage) {
                            if (!isSliding) sliderValue = (pagerCount - 1 - previewPage).toFloat().coerceIn(0f, maxSlider)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            // 明るさスライダー（明るさボタンで開閉。ページ移動スライダーとは離して配置）
                            AnimatedVisibility(visible = showBrightnessSlider, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Brightness4, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                    Slider(
                                        value = brightness,
                                        onValueChange = {
                                            brightness        = it
                                            brightnessApplied = true
                                            appPrefs.viewerBrightness = it   // 次回以降もこの明るさで開く
                                        },
                                        valueRange = 0.01f..1f,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    )
                                    Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                }
                            }

                            // ページ移動サムネイル（ページ移動ボタンで開閉）。
                            // サムネイルを見ながら細かくページを選べる（下のスライダーは大まかな移動用）
                            AnimatedVisibility(visible = showPageStrip, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                                PageJumpStrip(
                                    pages          = pages,
                                    pageFiles      = pageFiles,
                                    isProgressive  = isProgressive,
                                    pageCount      = pagerCount,
                                    targetPage     = previewPage,
                                    reverseLayout  = isReverseLayout,
                                    onTargetChange = { previewPage = it },
                                    onSelect       = { target ->
                                        scope.launch {
                                            pagerState.animateScrollToPage(spreadOfPage.getOrElse(target) { 0 })
                                        }
                                    }
                                )
                            }

                            // スライド中のサムネイルプレビュー（ストリップ非表示時のみ）
                            AnimatedVisibility(visible = isSliding && !isProgressive && !showPageStrip, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                                PageThumbnailStrip(pages = pages, targetPage = sliderTargetPage, visibleCount = 5)
                            }

                            // ページ数・ファイル名
                            Text(
                                text       = "${previewPage + 1} / $pagerCount ページ",
                                color      = Color.White,
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text     = displayFileName,
                                color    = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                            )

                            // ページ移動スライダー（下部バーから離して配置し、明るさボタン等との誤タップを防ぐ）
                            if (maxSlider > 0f) {
                                val downloadFraction = uiState.downloadFraction
                                Slider(
                                    value = sliderValue,
                                    onValueChange = {
                                        sliderValue = it
                                        isSliding = true
                                        showBrightnessSlider = false
                                        previewPage = (pagerCount - 1 - it.toInt()).coerceIn(0, pagerCount - 1)
                                    },
                                    onValueChangeFinished = {
                                        isSliding = false
                                        scope.launch {
                                            pagerState.animateScrollToPage(spreadOfPage.getOrElse(sliderTargetPage) { 0 })
                                        }
                                    },
                                    valueRange = 0f..maxSlider,
                                    steps = if (pagerCount > 2) pagerCount - 2 else 0,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    thumb = { Box(modifier = Modifier.size(28.dp).background(Color.White, CircleShape)) },
                                    track = {
                                        // ストリーミング中は、どこまで読めるかを色で示す。
                                        // 右綴じなら若いページが右なので、右から伸びる。
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color.White.copy(alpha = 0.25f)),
                                            contentAlignment = if (isReverseLayout) Alignment.CenterEnd
                                                               else Alignment.CenterStart
                                        ) {
                                            if (downloadFraction != null) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(downloadFraction)
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(Color(0xFF4CAF50).copy(alpha = 0.7f))
                                                )
                                            }
                                        }
                                    }
                                )

                                // ストリーミング中の読み込み状況
                                if (downloadFraction != null) {
                                    Text(
                                        text     = "読み込み中 ${(downloadFraction * 100).toInt()}%",
                                        color    = Color(0xFF81C784),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // 操作バー：左（ブックマーク一覧・登録・ページ移動）／右（明るさ・向きロック・設定）
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                                        .padding(horizontal = 4.dp)
                                ) {
                                    IconButton(onClick = { showBookmarkList = true }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook,
                                            contentDescription = "ブックマーク一覧",
                                            tint = if (uiState.bookmarks.isNotEmpty()) Color(0xFFFFD700) else Color.White
                                        )
                                    }
                                    IconButton(onClick = { viewModel.toggleBookmark(currentPageIndex) }) {
                                        Icon(
                                            if (uiState.isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                            contentDescription = "このページをブックマーク",
                                            tint = if (uiState.isCurrentPageBookmarked) Color(0xFFFFD700) else Color.White
                                        )
                                    }
                                    IconButton(onClick = {
                                        showPageStrip = !showPageStrip
                                        if (showPageStrip) showBrightnessSlider = false
                                    }) {
                                        Icon(
                                            Icons.Default.ViewCarousel,
                                            contentDescription = "ページ移動",
                                            tint = if (showPageStrip) Color.Yellow else Color.White
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                                        .padding(horizontal = 4.dp)
                                ) {
                                    IconButton(onClick = {
                                        showBrightnessSlider = !showBrightnessSlider
                                        if (showBrightnessSlider) showPageStrip = false
                                    }) {
                                        Icon(
                                            imageVector = when {
                                                brightness < 0.33f -> Icons.Default.Brightness4
                                                brightness < 0.66f -> Icons.Default.Brightness7
                                                else               -> Icons.Default.BrightnessHigh
                                            },
                                            contentDescription = "明るさ",
                                            tint = if (showBrightnessSlider) Color.Yellow else Color.White
                                        )
                                    }
                                    IconButton(onClick = {
                                        orientationLocked = !orientationLocked
                                        activity?.requestedOrientation =
                                            if (orientationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    }) {
                                        Icon(
                                            imageVector = if (orientationLocked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation,
                                            contentDescription = "画面の向きをロック",
                                            tint = if (orientationLocked) Color.Yellow else Color.White
                                        )
                                    }
                                    IconButton(onClick = onOpenSettings) {
                                        Icon(Icons.Default.Settings, contentDescription = "設定", tint = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── ページ移動ストリップ（サムネイルを見ながら細かく移動） ──────────

/**
 * ページ移動ボタンで開閉するサムネイルの横スクロール。
 *
 * 使い分け：
 * - 下部のスライダー：大まかに飛ばしたいとき
 * - このストリップ：中身を見ながら細かくページを指定したいとき
 *
 * 挙動（Comic Glassと同じ）：
 * - サムネイルを流すと、**中央に来ているページがその都度「移動先」になる**（番号がハイライトし、スライダーも連動する）
 * - サムネイルをタップすると、そのページを実際に開く
 * つまり「流して目的ページを探す→タップして開く」の2ステップで使える。
 *
 * 右綴じ（マンガ）のときはページ番号が右から左へ並ぶ。
 */
@Composable
private fun PageJumpStrip(
    pages: List<ByteArray>,
    pageFiles: List<Any>,
    isProgressive: Boolean,
    pageCount: Int,
    targetPage: Int,
    reverseLayout: Boolean,
    onTargetChange: (Int) -> Unit,
    onSelect: (Int) -> Unit
) {
    val listState     = rememberLazyListState()
    val configuration = LocalConfiguration.current

    // 先頭・末尾のページも中央に持ってこられるよう、左右に余白を確保する
    val sidePadding = ((configuration.screenWidthDp.dp - THUMB_WIDTH) / 2).coerceAtLeast(0.dp)

    // 中央に来ているサムネイル＝現在の移動先
    val centeredIndex by remember {
        derivedStateOf {
            val info           = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index
                ?: targetPage
        }
    }

    // 外部（スライダー操作・ページめくり）から移動先が変わったときだけ、サムネイルをスクロールさせて追従させる
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(targetPage) {
        if (targetPage != centeredIndex) {
            programmatic = true
            listState.animateScrollToItem(targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            programmatic = false
        }
    }

    // ユーザーがサムネイルを流している間、中央のページをその都度移動先として通知する
    // （これによりページ番号のハイライトとスライダーがリアルタイムに連動する）
    LaunchedEffect(centeredIndex) {
        if (!programmatic && centeredIndex != targetPage) onTargetChange(centeredIndex)
    }

    LazyRow(
        state                 = listState,
        reverseLayout         = reverseLayout,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding        = PaddingValues(horizontal = sidePadding),
        verticalAlignment     = Alignment.Bottom,
        modifier              = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        items(pageCount) { index ->
            val isTarget = index == targetPage
            val model: Any? = when {
                isProgressive && index < pageFiles.size -> pageFiles[index]
                !isProgressive && index < pages.size    -> pages[index]
                else                                    -> null
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(THUMB_WIDTH)
                        .aspectRatio(0.71f)
                        .clip(RoundedCornerShape(4.dp))
                        .border(
                            2.dp,
                            if (isTarget) Color.White else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .alpha(if (isTarget) 1f else 0.5f)
                        .background(Color.DarkGray)
                        .clickable { onSelect(index) }
                ) {
                    if (model != null) {
                        AsyncImage(
                            model              = model,
                            contentDescription = "ページ ${index + 1}",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )
                    }
                }
                Text(
                    text       = "${index + 1}",
                    color      = if (isTarget) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize   = if (isTarget) 15.sp else 12.sp,
                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal,
                    modifier   = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private val THUMB_WIDTH = 110.dp

// ─── サムネイルストリップ ─────────────────────────────────────────────────────

@Composable
private fun PageThumbnailStrip(pages: List<ByteArray>, targetPage: Int, visibleCount: Int = 5) {
    val half    = visibleCount / 2
    val indices = (-half..half).map { offset -> val idx = targetPage + offset; if (idx in pages.indices) idx else null }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        indices.forEachIndexed { i, pageIdx ->
            val isCenter = (i == half)
            Box(modifier = Modifier.width(if (isCenter) 72.dp else 52.dp).aspectRatio(0.71f).clip(RoundedCornerShape(4.dp)).border(1.5.dp, if (isCenter) Color.White else Color.Transparent, RoundedCornerShape(4.dp)).alpha(if (isCenter) 1.0f else 0.55f).background(Color.DarkGray)) {
                if (pageIdx != null) AsyncImage(model = pages[pageIdx], contentDescription = "ページ ${pageIdx + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (isCenter && pageIdx != null) Text(text = "${pageIdx + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (i < indices.lastIndex) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

// ─── ズーム可能なページコンポーザブル ────────────────────────────────────────

private const val MIN_SCALE      = 1.0f
private const val MAX_SCALE      = 3.0f
private const val OVERSHOOT_MAX  = MAX_SCALE * 1.15f
private const val OVERSHOOT_MIN  = MIN_SCALE * 0.85f
private const val SWIPE_GUARD_MS = 300L

/** 明るさを最後に動かしてから、これだけ経ったらバーを自動で閉じる */
private const val BRIGHTNESS_AUTO_HIDE_MS = 2500L

/** 保存済みの明るさを読む（-1 なら未設定） */
private fun appPrefsForBrightness(context: android.content.Context): Float =
    com.kamneko88.comicveil.data.AppPrefs(context).viewerBrightness

@Composable
private fun ZoomablePage(
    imageModels: List<Any>,
    pageIndex: Int,
    currentPage: Int,
    isScrolling: Boolean,
    zoomBounce: Boolean,
    doubleTapScale: Float,
    trimMode: com.kamneko88.comicveil.data.AppPrefs.TrimMode,
    trimKeepAspect: Boolean,
    spreadGutter: Int,
    reverseLayout: Boolean,
    onMenuToggle: () -> Unit,
    onPageLimit: (PageLimitEvent) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    pageHalf: PageHalf = PageHalf.FULL
) {
    val scope     = rememberCoroutineScope()
    val context   = LocalContext.current
    val scaleAnim = remember { Animatable(MIN_SCALE) }
    var offset    by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(currentPage) {
        if (pageIndex != currentPage) {
            scaleAnim.snapTo(MIN_SCALE)
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    val isZoomed = scaleAnim.value > MIN_SCALE
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    var lastScrollEndTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isScrolling) {
        if (!isScrolling) lastScrollEndTime = System.currentTimeMillis()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTime  = System.currentTimeMillis()
                    val downPos   = firstDown.position

                    // 指が離れるまで（または2本目の指が触れるまで）イベントを追う。
                    // 以前は「最初の80ms以内に2本目の指が来たらピンチ」と判定していたが、
                    // 素早いタップだと「指を離した」イベントがこの80msの窓に飲み込まれてしまい、
                    // タップとして成立せずメニューが出ない原因になっていた。
                    var isPinch = false
                    var moved   = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size >= 2) { isPinch = true; break }
                        val change = ev.changes.firstOrNull() ?: break
                        // ブレの許容量はシステム標準（タッチスロップ）に合わせる
                        if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) moved = true
                        // ズーム中は1本指ドラッグで画像を動かせるようにする
                        if (moved && scaleAnim.value > MIN_SCALE) {
                            offset += change.positionChange()
                            change.consume()
                        }
                        if (!change.pressed) break   // 指が離れた
                    }

                    if (isPinch) {
                        val maxScale = if (zoomBounce) OVERSHOOT_MAX else MAX_SCALE
                        val minScale = if (zoomBounce) OVERSHOOT_MIN else MIN_SCALE

                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size < 2) break
                            val newScale = (scaleAnim.value * ev.calculateZoom()).coerceIn(minScale, maxScale)
                            scope.launch { scaleAnim.snapTo(newScale) }
                            if (scaleAnim.value > MIN_SCALE) offset = offset + ev.calculatePan()
                            ev.changes.forEach { it.consume() }
                        } while (true)

                        if (zoomBounce) {
                            val target = scaleAnim.value.coerceIn(MIN_SCALE, MAX_SCALE)
                            if (target != scaleAnim.value) {
                                scope.launch {
                                    scaleAnim.animateTo(target, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                                    if (target == MIN_SCALE) offset = Offset.Zero
                                }
                            }
                        } else {
                            if (scaleAnim.value < MIN_SCALE) {
                                scope.launch {
                                    scaleAnim.snapTo(MIN_SCALE)
                                    offset = Offset.Zero
                                }
                            }
                        }

                    } else {
                        // スワイプ（＝ページ送り／ズーム中のパン）だった場合はメニューを出さない
                        if (moved) return@awaitEachGesture
                        // 長押しでない限りタップとして扱う（以前は300ms以上押すと無効になっていた）
                        if (System.currentTimeMillis() - downTime > viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                        // ページ送り直後の誤タップを防ぐガード
                        if (System.currentTimeMillis() - lastScrollEndTime < SWIPE_GUARD_MS) return@awaitEachGesture

                        // ダブルタップ待ち：一定時間内に2回目のタップが来るかを確認する。
                        // 来ればズーム切り替え、来なければシングルタップ（メニュー表示）として扱う。
                        // このためメニューはごく短い間を置いてから表示される（Comic Glassと同じ挙動）。
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }

                        if (secondDown != null) {
                            // 2回目の指が離れるまで待つ（ドラッグならダブルタップとしない）
                            var secondMoved = false
                            while (true) {
                                val ev     = awaitPointerEvent()
                                val change = ev.changes.firstOrNull() ?: break
                                if ((change.position - secondDown.position).getDistance() > viewConfiguration.touchSlop) secondMoved = true
                                if (!change.pressed) break
                            }
                            if (secondMoved) return@awaitEachGesture

                            // ダブルタップ：ズーム中なら等倍に戻し、等倍なら設定倍率へズームする
                            scope.launch {
                                if (scaleAnim.value > MIN_SCALE) {
                                    scaleAnim.animateTo(MIN_SCALE, tween(200))
                                    offset = Offset.Zero
                                } else {
                                    offset = Offset.Zero
                                    scaleAnim.animateTo(doubleTapScale, tween(200))
                                }
                            }
                            return@awaitEachGesture
                        }

                        // ここまで来たらシングルタップ確定
                        if (isZoomed) {
                            onMenuToggle()
                        } else {
                            val isRightTap = downPos.x > size.width * 0.66f
                            val isLeftTap  = downPos.x < size.width * 0.33f
                            when {
                                isRightTap && isFirst -> onPageLimit(PageLimitEvent.FIRST)
                                isLeftTap  && isLast  -> onPageLimit(PageLimitEvent.LAST)
                                else                  -> onMenuToggle()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 見開きのときは2ページを並べる。
        // 右綴じ（マンガ）はページ番号の小さい方が右に来るので、並び順を反転させる。
        val ordered = if (reverseLayout) imageModels.reversed() else imageModels

        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scaleAnim.value,
                    scaleY       = scaleAnim.value,
                    translationX = offset.x,
                    translationY = offset.y
                )
        ) {
            ordered.forEachIndexed { i, model ->
                // 余白削除がONのときは表示直前に白・黒のフチを切り落とす。
                // 見開き分割中（pageHalf != FULL）は、そのあとさらに右半分・左半分だけを切り出す。
                val request = remember(model, trimMode, trimKeepAspect, pageHalf) {
                    val transforms = buildList {
                        when (trimMode) {
                            com.kamneko88.comicveil.data.AppPrefs.TrimMode.OFF -> {}
                            com.kamneko88.comicveil.data.AppPrefs.TrimMode.ON ->
                                add(TrimMarginsTransformation(whiteOnly = false, keepAspect = trimKeepAspect))
                            com.kamneko88.comicveil.data.AppPrefs.TrimMode.WHITE_ONLY ->
                                add(TrimMarginsTransformation(whiteOnly = true, keepAspect = trimKeepAspect))
                        }
                        if (pageHalf != PageHalf.FULL) add(HalfCropTransformation(pageHalf))
                    }
                    ImageRequest.Builder(context)
                        .data(model)
                        .let { builder -> if (transforms.isNotEmpty()) builder.transformations(transforms) else builder }
                        .build()
                }

                // 見開きのときは左右のページを内側に寄せて、真ん中に隙間ができないようにする
                // （ページはそれぞれ画面の半分を占めるため、中央寄せのままだと内側に余白が残る）
                val isSpread  = ordered.size >= 2
                val alignment = when {
                    !isSpread -> Alignment.Center
                    i == 0    -> Alignment.CenterEnd    // 左側のページ→右寄せ
                    else      -> Alignment.CenterStart  // 右側のページ→左寄せ
                }

                AsyncImage(
                    model              = request,
                    contentDescription = "ページ",
                    modifier           = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    alignment    = alignment,
                    contentScale = ContentScale.Fit
                )

                // 綴じ代（左右ページの間に入れる余白）
                if (isSpread && i == 0 && spreadGutter > 0) {
                    Spacer(Modifier.fillMaxHeight().width((spreadGutter * 2).dp))
                }
            }
        }
    }
}

/**
 * ページを見開きの組にまとめる。
 *
 * - 見開きがOFFなら、全ページを単独で返す
 * - 見開きがONで「表紙を単独表示」なら、[0] / [1,2] / [3,4] ... となる
 * - 見開きがONで表紙も見開きなら、[0,1] / [2,3] ... となる
 * - 最後の組が1ページだけになることもある
 */
private fun buildSpreads(
    pageCount: Int,
    spreadEnabled: Boolean,
    coverSingle: Boolean
): List<List<Int>> {
    if (!spreadEnabled || pageCount <= 1) {
        return (0 until pageCount).map { listOf(it) }
    }

    val result = mutableListOf<List<Int>>()
    var index  = 0

    if (coverSingle) {
        result.add(listOf(0))
        index = 1
    }

    while (index < pageCount) {
        if (index + 1 < pageCount) {
            result.add(listOf(index, index + 1))
            index += 2
        } else {
            result.add(listOf(index))
            index += 1
        }
    }
    return result
}

/**
 * 見開き分割用の仮想ページ列を作る（見開き表示モードがOFFのときだけ使う）。
 *
 * 横長ページ（見開きの1枚絵）と判定されたページだけ、表示位置を2つに分ける
 * （右綴じなら右半分→左半分、左綴じなら左半分→右半分の順）。
 * 実データ・物理ページ数は一切変えない。栞・読書進捗・スライダー・サムネイルは
 * これまで通りページ単位のまま扱われる（表示だけ2コマに分けて見せている）。
 *
 * @param widePages 横長と判定済みのページ番号の集合（未展開のページはまだ含まれないことがある）
 * @param rightFirst true なら右半分を先に見せる（右綴じ／マンガ）。false なら左半分から
 */
private fun buildSplitSpreads(
    pageCount: Int,
    widePages: Set<Int>,
    rightFirst: Boolean
): Pair<List<List<Int>>, List<PageHalf>> {
    val spreads = mutableListOf<List<Int>>()
    val halves  = mutableListOf<PageHalf>()
    val order   = if (rightFirst) listOf(PageHalf.RIGHT, PageHalf.LEFT) else listOf(PageHalf.LEFT, PageHalf.RIGHT)

    for (p in 0 until pageCount) {
        if (p in widePages) {
            order.forEach { half ->
                spreads.add(listOf(p))
                halves.add(half)
            }
        } else {
            spreads.add(listOf(p))
            halves.add(PageHalf.FULL)
        }
    }
    return spreads to halves
}
