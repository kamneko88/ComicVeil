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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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

    // ViewModelをファクトリで生成（filePath を渡すため）
    val viewModel: ViewerViewModel = viewModel(
        factory = ViewerViewModel.Factory(
            application = context.applicationContext as Application,
            filePath = filePath
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    var menuVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onClose() }

    when {
        // ページ読み込み中 or DB読み込み前はローディング表示
        // （両方揃ってから pagerState を生成する必要があるため）
        uiState.isLoading || !uiState.isSavedPageLoaded -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }

        uiState.error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("エラー：${uiState.error}") }
        }

        uiState.pages.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("ページが見つかりません") }
        }

        else -> {
            val pages = uiState.pages

            // initialPage をここで決定する。pagerState は一度作ったら変更不可なため、
            // DB読み込みが終わってから（isSavedPageLoaded == true）生成する必要がある
            val pagerState = rememberPagerState(
                initialPage = uiState.initialPage.coerceIn(0, pages.size - 1),
                pageCount = { pages.size }
            )

            // ページが変わるたびにDBへ保存
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // ─── ページ表示（HorizontalPager）───────────────────────────
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { menuVisible = !menuVisible }
                        },
                    reverseLayout = true,       // 右綴じ（右→左）
                    flingBehavior = springFling
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

                // ─── メニュー表示時の暗転オーバーレイ ─────────────────────
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

                // ─── 中央：「本を閉じる」ボタン ───────────────────────────
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

                // ─── 下部：ページスライダー ───────────────────────────────
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
                        // スライダーの値は右綴じ用に反転（右端=1ページ目）
                        var sliderValue by remember {
                            mutableFloatStateOf((pages.size - 1 - pagerState.currentPage).toFloat())
                        }
                        // pager の currentPage が変わったらスライダーも同期
                        LaunchedEffect(pagerState.currentPage) {
                            sliderValue = (pages.size - 1 - pagerState.currentPage).toFloat()
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
                                    val targetPage = pages.size - 1 - sliderValue.toInt()
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