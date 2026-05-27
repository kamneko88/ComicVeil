package com.kamneko88.comicveil.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

@Composable
fun ViewerScreen(filePath: String) {
    var pages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator()
            error != null -> Text("エラー：$error")
            pages.isEmpty() -> Text("ページが見つかりません")
            else -> {
                // 最初のページを表示
                AsyncImage(
                    model = pages[0],
                    contentDescription = "ページ1",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
            .sortedBy { it.name }

        for (entry in entries) {
            zip.getInputStream(entry).use { stream ->
                pages.add(Pair(entry.name, stream.readBytes()))
            }
        }
    }
    return pages.map { it.second }
}