package com.kamneko88.comicveil.ui.viewer

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onClose: () -> Unit
) {
    var pages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onClose() }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                val extractedPages = when (file.extension.lowercase()) {
                    "zip", "cbz" -> extractZip(file)
                    "rar", "cbr" -> extractRar(file)
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
        isLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }

        error != null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { Text("エラー：$error") }

        pages.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { Text("ページが見つかりません") }

        else -> {
            val pagerState = rememberPagerState(
                initialPage = 0,
                pageCount = { pages.size }
            )

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
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                menuVisible = !menuVisible
                            }
                        },
                    reverseLayout = true,
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                        Text(
                            text = "  本を閉じる",
                            fontSize = 16.sp
                        )
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
                            mutableFloatStateOf((pages.size - 1).toFloat())
                        }
                        LaunchedEffect(pagerState.currentPage) {
                            sliderValue = (pages.size - 1 - pagerState.currentPage).toFloat()
                        }

                        Text(
                            text = "${pages.size - sliderValue.toInt()} / ${pages.size}",
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
                            steps = pages.size - 2,
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

// ZIP/CBZ展開
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

// RAR/CBR展開
private fun extractRar(file: File): List<ByteArray> {
    val pages = mutableListOf<Pair<String, ByteArray>>()
    val archive = Archive(file)
    archive.use {
        val headers = archive.fileHeaders
            .filter { header ->
                val ext = header.fileName
                    .substringAfterLast(".").lowercase()
                ext in setOf("jpg", "jpeg", "png", "webp")
            }
            .sortedBy { it.fileName.lowercase() }

        for (header in headers) {
            val outputStream = ByteArrayOutputStream()
            archive.extractFile(header, outputStream)
            pages.add(Pair(header.fileName, outputStream.toByteArray()))
        }
    }
    return pages.map { it.second }
}