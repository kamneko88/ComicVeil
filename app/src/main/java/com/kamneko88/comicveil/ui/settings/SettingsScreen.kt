package com.kamneko88.comicveil.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kamneko88.comicveil.BuildConfig
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.ui.home.HomeViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailCacheDir = remember { File(context.cacheDir, "thumbnails") }
    val appPrefs = remember { viewModel.appPrefs }

    // フォルダ設定状態（即時反映のため remember で保持）
    var homeFolderType     by remember { mutableStateOf(appPrefs.homeFolderType) }
    var downloadFolderType by remember { mutableStateOf(appPrefs.downloadFolderType) }

    // キャッシュサイズ（削除後に即時更新）
    var nasCacheSize       by remember { mutableLongStateOf(calcDirSize(File(context.cacheDir, "nas_cache"))) }
    var thumbnailCacheSize by remember { mutableLongStateOf(calcDirSize(thumbnailCacheDir)) }

    var showClearNasDialog       by remember { mutableStateOf(false) }
    var showClearThumbnailDialog by remember { mutableStateOf(false) }

    // STRキャッシュ削除確認
    if (showClearNasDialog) {
        AlertDialog(
            onDismissRequest = { showClearNasDialog = false },
            title = { Text("STRキャッシュを削除") },
            text  = { Text("ストリーミングでダウンロードしたキャッシュ（${formatBytes(nasCacheSize)}）を全て削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearNasDialog = false
                    viewModel.clearNasCache()
                    nasCacheSize = 0L
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearNasDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // サムネイルキャッシュ削除確認
    if (showClearThumbnailDialog) {
        AlertDialog(
            onDismissRequest = { showClearThumbnailDialog = false },
            title = { Text("サムネイルキャッシュを削除") },
            text  = { Text("サムネイル画像のキャッシュ（${formatBytes(thumbnailCacheSize)}）を全て削除します。\n次回表示時に再生成されます。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearThumbnailDialog = false
                    viewModel.clearThumbnailCache(thumbnailCacheDir)
                    thumbnailCacheSize = 0L
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearThumbnailDialog = false }) { Text("キャンセル") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── フォルダ設定 ────────────────────────────────────────────
            Text(
                text  = "フォルダ設定",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // Homeフォルダ
            Text(text = "Homeフォルダ", style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = "コミックを保存・表示するデフォルトの場所",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SettingsFolderRadio(
                label       = "ComicVeilフォルダ（推奨）",
                description = "Android/data/以下のアプリ専用領域。他アプリから干渉されません",
                selected    = homeFolderType == AppPrefs.HomeFolderType.APP_FOLDER,
                onSelect    = {
                    homeFolderType = AppPrefs.HomeFolderType.APP_FOLDER
                    appPrefs.homeFolderType = AppPrefs.HomeFolderType.APP_FOLDER
                }
            )
            SettingsFolderRadio(
                label       = "Downloadsフォルダ",
                description = "他のアプリと共有される領域。干渉の可能性あり",
                selected    = homeFolderType == AppPrefs.HomeFolderType.DOWNLOADS,
                onSelect    = {
                    homeFolderType = AppPrefs.HomeFolderType.DOWNLOADS
                    appPrefs.homeFolderType = AppPrefs.HomeFolderType.DOWNLOADS
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // DL保存先
            Text(text = "DL保存先", style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = "DLモードでダウンロードしたファイルの保存先",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SettingsFolderRadio(
                label       = "ComicVeilフォルダ（推奨）",
                description = "Homeフォルダと同じ場所に保存",
                selected    = downloadFolderType == AppPrefs.DownloadFolderType.APP_FOLDER,
                onSelect    = {
                    downloadFolderType = AppPrefs.DownloadFolderType.APP_FOLDER
                    appPrefs.downloadFolderType = AppPrefs.DownloadFolderType.APP_FOLDER
                }
            )
            SettingsFolderRadio(
                label       = "Downloads/ComicVeilフォルダ",
                description = "Downloadsフォルダ内のComicVeilサブフォルダに保存",
                selected    = downloadFolderType == AppPrefs.DownloadFolderType.DOWNLOADS,
                onSelect    = {
                    downloadFolderType = AppPrefs.DownloadFolderType.DOWNLOADS
                    appPrefs.downloadFolderType = AppPrefs.DownloadFolderType.DOWNLOADS
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── キャッシュ管理 ────────────────────────────────────────────
            Text(
                text  = "キャッシュ管理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            SettingsCacheItem(
                title    = "STRキャッシュ",
                subtitle = "ストリーミング再生でダウンロードした一時ファイル",
                size     = nasCacheSize,
                onClear  = { showClearNasDialog = true }
            )
            HorizontalDivider()

            SettingsCacheItem(
                title    = "サムネイルキャッシュ",
                subtitle = "ファイル一覧の表紙画像キャッシュ。削除後はアプリ再起動で再生成されます。",
                size     = thumbnailCacheSize,
                onClear  = { showClearThumbnailDialog = true }
            )
            HorizontalDivider()

            // ── 今後の設定項目（プレースホルダー）────────────────────────
            Spacer(Modifier.height(24.dp))
            Text(
                text  = "表示・操作",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "（今後実装予定）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // ── バージョン情報 ────────────────────────────────────────
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text      = "Comic Veil  v${BuildConfig.VERSION_NAME}",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsFolderRadio(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick  = onSelect,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCacheItem(
    title: String,
    subtitle: String,
    size: Long,
    onClear: () -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text  = if (size > 0) formatBytes(size) else "0 B（空）",
                style = MaterialTheme.typography.labelSmall,
                color = if (size > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick  = onClear,
            enabled  = size > 0,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text("削除")
        }
    }
}

private fun calcDirSize(dir: File): Long =
    dir.listFiles()?.sumOf { it.length() } ?: 0L

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}
