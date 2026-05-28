package com.kamneko88.comicveil.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import androidx.compose.foundation.layout.statusBarsPadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onClose: () -> Unit
) {
    var pages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // バウンスON/OFFの切り替え状態
    var bounceEnabled by remember { mutableStateOf(true) }

    BackHandler { onClose() }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                val extractedPages = when (file.extension.lowercase()) {
                    "zip", "cbz" -> extractZip(file)
                    else -> emptyList()
                }
                pages = extractedPages
                isLoading = false
            } catch (e: Exception) {
                error = e.message
                isLoading = false
            }
        }
    }

    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("エラー：$error") }
        }
        pages.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("ページが見つかりません") }
        }
        else -> {
            val pagerState = rememberPagerState(
                initialPage = 0,
                pageCount = { pages.size }
            )

            // バウンスON：スプリングアニメーション
            val springFling = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioHighBouncy,
                    stiffness = Spring.StiffnessVeryLow
                )
            )

            // バウンスOFF：通常アニメーション
            val normalFling = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = tween(durationMillis = 200)
            )

            Column(modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
            ) {

                // 上部バー
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 閉じるボタン
                        Button(
                            onClick = onClose,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("✕ 閉じる")
                        }

                        // バウンス切り替えボタン
                        Button(
                            onClick = { bounceEnabled = !bounceEnabled },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (bounceEnabled)
                                    Color(0xFF4CAF50)  // ON時：緑
                                else
                                    Color(0xFF9E9E9E)  // OFF時：グレー
                            )
                        ) {
                            Text(
                                text = if (bounceEnabled) "バウンス ON" else "バウンス OFF"
                            )
                        }
                    }

                    // ページ番号
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp)
                    )
                }

                // ページャー
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    reverseLayout = true,
                    flingBehavior = if (bounceEnabled) springFling else normalFling
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
            }
        }
    }
}

private fun extractZip(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence()
            .filter { entry ->
                val ext = entry.name.substringAfterLast(".").lowercase()
                ext in setOf("jpg", "jpeg", "png", "webp")
            }
            .sortedBy { it.name.lowercase() }

        for (entry in entries) {
            zip.getInputStream(entry).use { stream ->
                pages.add(Pair(entry.name, stream.readBytes()))
            }
        }
    }
    return pages.map { it.second }
}