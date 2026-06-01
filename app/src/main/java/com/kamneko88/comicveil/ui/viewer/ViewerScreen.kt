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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            var sliderValue by remember {
                                mutableFloatStateOf(
                                    (pages.size - 1 - pagerState.currentPage).toFloat()
                                )
                            }
                            LaunchedEffect(pagerState.currentPage) {
                                sliderValue =
                                    (pages.size - 1 - pagerState.currentPage).toFloat()
                            }

                            Text(
                                text = "${pagerState.currentPage + 1} / ${pages.size}",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    scope.launch {
                                        val targetPage =
                                            pages.size - 1 - sliderValue.toInt()
                                        pagerState.animateScrollToPage(targetPage)
                                    }
                                },
                                valueRange = 0f..(pages.size - 1).toFloat(),
                                steps = if (pages.size > 2) pages.size - 2 else 0,
                                modifier = Modifier.fillMaxWidth(),
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