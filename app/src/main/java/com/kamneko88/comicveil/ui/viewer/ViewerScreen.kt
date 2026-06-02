package com.kamneko88.comicveil.ui.viewer

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
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

    BackHandler { onClose() }

    // 先頭・最終ページイベントを受け取ってSnackbarを表示
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }

        uiState.error != null -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("読み込みエラー") },
                text = { Text("ファイルを開けませんでした。\n${uiState.error}") },
                confirmButton = {
                    TextButton(onClick = onClose) { Text("閉じる") }
                }
            )
        }

        uiState.pages.isEmpty() -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("非対応のファイル形式") },
                text = {
                    Text(
                        "画像ページが見つかりませんでした。\n\n" +
                                "ZIPの中にZIP・RARが入っている\n" +
                                "「二重圧縮」ファイルには対応していません。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = onClose) { Text("閉じる") }
                }
            )
        }

        else -> {
            val pages = uiState.pages
            val pagerState = rememberPagerState(
                initialPage = uiState.initialPage.coerceIn(0, pages.size - 1),
                pageCount = { pages.size }
            )

            LaunchedEffect(pagerState.currentPage) {
                viewModel.savePage(pagerState.currentPage)
            }

            // スワイプで端に達したときの通知
            // isScrollInProgress が false になった（スワイプ完了）タイミングで判定
            // hasScrolled フラグで開いた直後の誤通知を防ぐ
            var hasScrolled by remember { mutableStateOf(false) }
            LaunchedEffect(pagerState.isScrollInProgress) {
                if (pagerState.isScrollInProgress) {
                    hasScrolled = true
                } else if (hasScrolled) {
                    when (pagerState.currentPage) {
                        0              -> viewModel.onPageLimitReached(PageLimitEvent.FIRST)
                        pages.size - 1 -> viewModel.onPageLimitReached(PageLimitEvent.LAST)
                    }
                }
            }

            val springFling = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioHighBouncy,
                    stiffness = Spring.StiffnessVeryLow
                )
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
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pagerState.currentPage) {
                                detectTapGestures { offset ->
                                    val isFirst   = pagerState.currentPage == 0
                                    val isLast    = pagerState.currentPage == pages.size - 1
                                    // reverseLayout=true なので右が「前」左が「次」
                                    val isRightTap = offset.x > size.width * 0.66f
                                    val isLeftTap  = offset.x < size.width * 0.33f

                                    when {
                                        isRightTap && isFirst ->
                                            viewModel.onPageLimitReached(PageLimitEvent.FIRST)
                                        isLeftTap && isLast ->
                                            viewModel.onPageLimitReached(PageLimitEvent.LAST)
                                        else ->
                                            menuVisible = !menuVisible
                                    }
                                }
                            },
                        reverseLayout = true,
                        flingBehavior = springFling,
                        beyondViewportPageCount = 2
                    ) { pageIndex ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = pages[pageIndex],
                                contentDescription = "ページ ${pageIndex + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = menuVisible,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f))
                        )
                    }

                    AnimatedVisibility(
                        visible = menuVisible,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Button(
                            onClick = onClose,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Text(text = "  本を閉じる", fontSize = 16.sp)
                        }
                    }

                    AnimatedVisibility(
                        visible = menuVisible,
                        enter = slideInVertically(tween(200)) { it },
                        exit = slideOutVertically(tween(200)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        // スライダー操作中のターゲットページ（スライダーに連動してリアルタイム更新）
                        var sliderValue by remember {
                            mutableFloatStateOf(
                                (pages.size - 1 - pagerState.currentPage).toFloat()
                            )
                        }
                        // sliderValue から逆算したターゲットページ番号（0始まり）
                        val sliderTargetPage by remember {
                            derivedStateOf { pages.size - 1 - sliderValue.toInt() }
                        }
                        // スライダーが動いているかどうか（サムネイル列の表示切り替えに使用）
                        var isSliding by remember { mutableStateOf(false) }

                        // pagerState が変わったときにスライダーを同期（スワイプ操作時）
                        LaunchedEffect(pagerState.currentPage) {
                            if (!isSliding) {
                                sliderValue =
                                    (pages.size - 1 - pagerState.currentPage).toFloat()
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {

                            // ── サムネイル列（スライダー操作中に表示） ──────────────────
                            AnimatedVisibility(
                                visible = isSliding,
                                enter = fadeIn(tween(150)),
                                exit  = fadeOut(tween(150))
                            ) {
                                PageThumbnailStrip(
                                    pages        = pages,
                                    targetPage   = sliderTargetPage,
                                    visibleCount = 5
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // ── ページ番号表示 ─────────────────────────────────────────
                            // スライダー操作中はターゲットページ番号を表示
                            val displayPage = if (isSliding) sliderTargetPage + 1
                                             else pagerState.currentPage + 1
                            Text(
                                text     = "$displayPage / ${pages.size}",
                                color    = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            // ── スライダー ────────────────────────────────────────────
                            Slider(
                                value    = sliderValue,
                                onValueChange = { newValue ->
                                    sliderValue = newValue
                                    isSliding   = true
                                },
                                onValueChangeFinished = {
                                    isSliding = false
                                    scope.launch {
                                        pagerState.animateScrollToPage(sliderTargetPage)
                                    }
                                },
                                valueRange = 0f..(pages.size - 1).toFloat(),
                                steps      = if (pages.size > 2) pages.size - 2 else 0,
                                modifier   = Modifier.fillMaxWidth(),
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(Color.White, CircleShape)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── サムネイルストリップ ─────────────────────────────────────────────────────

/**
 * スライダー操作中にページサムネイルを横一列に表示するコンポーザブル。
 *
 * @param pages        全ページの ByteArray リスト
 * @param targetPage   中央に表示するページ番号（0始まり）
 * @param visibleCount 表示するサムネイル枚数（奇数推奨。例：5→中央±2枚）
 */
@Composable
private fun PageThumbnailStrip(
    pages: List<ByteArray>,
    targetPage: Int,
    visibleCount: Int = 5
) {
    val half = visibleCount / 2
    // 表示するページインデックスのリスト（範囲外はnullで埋める）
    val indices = (-half..half).map { offset ->
        val idx = targetPage + offset
        if (idx in pages.indices) idx else null
    }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.Bottom
    ) {
        indices.forEachIndexed { i, pageIdx ->
            val isCenter    = (i == half)
            // 中央サムネイルは大きく・明るく、前後は小さく・暗め
            val thumbWidth  = if (isCenter) 72.dp else 52.dp
            val thumbAlpha  = if (isCenter) 1.0f   else 0.55f
            val borderColor = if (isCenter) Color.White else Color.Transparent

            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .aspectRatio(0.71f)                      // 縦横比：マンガページ相当
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.5.dp, borderColor, RoundedCornerShape(4.dp))
                    .alpha(thumbAlpha)
                    .background(Color.DarkGray)
            ) {
                if (pageIdx != null) {
                    AsyncImage(
                        model              = pages[pageIdx],
                        contentDescription = "ページ ${pageIdx + 1}",
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )
                }
                // 中央サムネイルの下部にページ番号を表示
                if (isCenter && pageIdx != null) {
                    Text(
                        text       = "${pageIdx + 1}",
                        color      = Color.White,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            // サムネイル間のスペース（最後の要素の後ろには不要）
            if (i < indices.lastIndex) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}