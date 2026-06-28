package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.view.doOnLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.kamneko88.comicveil.MainActivity
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onClose: () -> Unit
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
    val initialBrightness = remember {
        activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
    }
    var brightness           by remember { mutableFloatStateOf(initialBrightness) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showBookmarkList     by remember { mutableStateOf(false) }

    var isAnyPageZoomed by remember { mutableStateOf(false) }

    val appPrefs          = remember { com.kamneko88.comicveil.data.AppPrefs(context) }
    val isReverseLayout   = appPrefs.pageDirection == com.kamneko88.comicveil.data.AppPrefs.PageDirection.RIGHT_TO_LEFT
    val pageAnimation     = appPrefs.pageAnimation
    val volumeKeyPageTurn = appPrefs.volumeKeyPageTurn
    val zoomBounce        = appPrefs.zoomBounce

    LaunchedEffect(Unit) { viewModel.loadBookmarks() }

    LaunchedEffect(brightness) {
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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

            val pagerState = rememberPagerState(
                initialPage = uiState.initialPage.coerceIn(0, pagerCount - 1),
                pageCount   = { pagerCount }
            )

            LaunchedEffect(pagerState.currentPage) { viewModel.savePage(pagerState.currentPage) }

            var hasScrolled by remember { mutableStateOf(false) }
            LaunchedEffect(pagerState.isScrollInProgress) {
                if (pagerState.isScrollInProgress) {
                    hasScrolled = true
                } else if (hasScrolled) {
                    when (pagerState.currentPage) {
                        0              -> viewModel.onPageLimitReached(PageLimitEvent.FIRST)
                        pagerCount - 1 -> viewModel.onPageLimitReached(PageLimitEvent.LAST)
                    }
                }
            }

            DisposableEffect(volumeKeyPageTurn, pagerCount) {
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
                                    val target = (pagerState.currentPage + 1).coerceAtMost(pagerCount - 1)
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
                snapAnimationSpec = if (pageAnimation) {
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
                containerColor = Color.Black
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .statusBarsPadding()
                ) {
                    HorizontalPager(
                        state                   = pagerState,
                        userScrollEnabled       = !isAnyPageZoomed,
                        modifier                = Modifier.fillMaxSize(),
                        reverseLayout           = isReverseLayout,
                        flingBehavior           = springFling,
                        beyondViewportPageCount = 2
                    ) { pageIndex ->
                        when {
                            isProgressive && pageIndex < pageFiles.size -> {
                                ZoomablePage(
                                    imageModel    = pageFiles[pageIndex],
                                    pageIndex     = pageIndex,
                                    currentPage   = pagerState.currentPage,
                                    isScrolling   = pagerState.isScrollInProgress,
                                    zoomBounce    = zoomBounce,
                                    onMenuToggle  = { menuVisible = !menuVisible },
                                    onPageLimit   = { viewModel.onPageLimitReached(it) },
                                    isFirst       = pageIndex == 0,
                                    isLast        = pageIndex == pagerCount - 1,
                                    onZoomChanged = { isAnyPageZoomed = it }
                                )
                            }
                            !isProgressive && pageIndex < pages.size -> {
                                ZoomablePage(
                                    imageModel    = pages[pageIndex],
                                    pageIndex     = pageIndex,
                                    currentPage   = pagerState.currentPage,
                                    isScrolling   = pagerState.isScrollInProgress,
                                    zoomBounce    = zoomBounce,
                                    onMenuToggle  = { menuVisible = !menuVisible },
                                    onPageLimit   = { viewModel.onPageLimitReached(it) },
                                    isFirst       = pageIndex == 0,
                                    isLast        = pageIndex == pagerCount - 1,
                                    onZoomChanged = { isAnyPageZoomed = it }
                                )
                            }
                            else -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color.White)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text  = "読み込み中... (${pageIndex + 1}ページ)",
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = menuVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
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
                                                        scope.launch { pagerState.animateScrollToPage(bookmark.page) }
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

                    AnimatedVisibility(visible = menuVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200)), modifier = Modifier.align(Alignment.Center)) {
                        Button(onClick = onClose, modifier = Modifier.padding(16.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Text(text = "  本を閉じる", fontSize = 16.sp)
                        }
                    }

                    AnimatedVisibility(visible = menuVisible, enter = slideInVertically(tween(200)) { it }, exit = slideOutVertically(tween(200)) { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
                        val maxSlider = (pagerCount - 1).toFloat().coerceAtLeast(0f)
                        var sliderValue by remember { mutableFloatStateOf((pagerCount - 1 - pagerState.currentPage).toFloat().coerceIn(0f, maxSlider)) }
                        val sliderTargetPage by remember { derivedStateOf { (pagerCount - 1 - sliderValue.toInt()).coerceIn(0, pagerCount - 1) } }
                        var isSliding by remember { mutableStateOf(false) }

                        LaunchedEffect(pagerState.currentPage) {
                            if (!isSliding) sliderValue = (pagerCount - 1 - pagerState.currentPage).toFloat().coerceIn(0f, maxSlider)
                        }

                        Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AnimatedVisibility(visible = isSliding && !isProgressive, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                                PageThumbnailStrip(pages = pages, targetPage = sliderTargetPage, visibleCount = 5)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                IconButton(onClick = { showBookmarkList = true }) {
                                    Icon(Icons.Default.BookmarkBorder, contentDescription = "ブックマーク一覧", tint = if (uiState.bookmarks.isNotEmpty()) Color(0xFFFFD700) else Color.White)
                                }
                                IconButton(onClick = { viewModel.toggleBookmark(pagerState.currentPage) }) {
                                    Icon(if (uiState.isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "ブックマーク", tint = if (uiState.isCurrentPageBookmarked) Color(0xFFFFD700) else Color.White)
                                }
                            }
                            Text(text = "${if (isSliding) sliderTargetPage + 1 else pagerState.currentPage + 1} / $pagerCount", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                if (maxSlider > 0f) {
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { sliderValue = it; isSliding = true; showBrightnessSlider = false },
                                        onValueChangeFinished = { isSliding = false; scope.launch { pagerState.animateScrollToPage(sliderTargetPage) } },
                                        valueRange = 0f..maxSlider,
                                        steps = if (pagerCount > 2) pagerCount - 2 else 0,
                                        modifier = Modifier.weight(1f),
                                        thumb = { Box(modifier = Modifier.size(28.dp).background(Color.White, CircleShape)) }
                                    )
                                } else { Spacer(Modifier.weight(1f)) }
                                IconButton(onClick = { showBrightnessSlider = !showBrightnessSlider }) {
                                    Icon(imageVector = when { brightness < 0.33f -> Icons.Default.Brightness4; brightness < 0.66f -> Icons.Default.Brightness7; else -> Icons.Default.BrightnessHigh }, contentDescription = "明るさ", tint = if (showBrightnessSlider) androidx.compose.ui.graphics.Color.Yellow else Color.White)
                                }
                            }
                            AnimatedVisibility(visible = showBrightnessSlider, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    Icon(Icons.Default.Brightness4, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.01f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                    Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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

@Composable
private fun ZoomablePage(
    imageModel: Any,
    pageIndex: Int,
    currentPage: Int,
    isScrolling: Boolean,
    zoomBounce: Boolean,
    onMenuToggle: () -> Unit,
    onPageLimit: (PageLimitEvent) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    val scope     = rememberCoroutineScope()
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

                    val isPinch = withTimeoutOrNull(80L) {
                        var found = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size >= 2) { found = true; break }
                            if (ev.changes.any { it.positionChanged() && abs(it.position.x - downPos.x) > 8f }) break
                        }
                        found
                    } ?: false

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
                        var moved = false
                        while (true) {
                            val ev     = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            if (abs(change.position.x - downPos.x) > 10f ||
                                abs(change.position.y - downPos.y) > 10f) moved = true
                            if (!change.pressed) break
                        }

                        if (moved) return@awaitEachGesture
                        if (System.currentTimeMillis() - downTime > 300L) return@awaitEachGesture
                        if (System.currentTimeMillis() - lastScrollEndTime < SWIPE_GUARD_MS) return@awaitEachGesture

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
        AsyncImage(
            model              = imageModel,
            contentDescription = "ページ ${pageIndex + 1}",
            modifier           = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scaleAnim.value,
                    scaleY       = scaleAnim.value,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
        )
    }
}
