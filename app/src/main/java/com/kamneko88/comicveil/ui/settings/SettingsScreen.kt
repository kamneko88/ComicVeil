package com.kamneko88.comicveil.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.kamneko88.comicveil.BuildConfig
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.LibarchivePoc
import com.kamneko88.comicveil.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ── 読む ──────────────────────────────────────────────────────────────
    var pageDirection     by remember { mutableStateOf(appPrefs.pageDirection) }
    var pageAnimation     by remember { mutableStateOf(appPrefs.pageAnimation) }
    var volumeKeyPageTurn by remember { mutableStateOf(appPrefs.volumeKeyPageTurn) }
    var zoomBounce        by remember { mutableStateOf(appPrefs.zoomBounce) }
    var doubleTapZoom     by remember { mutableStateOf(appPrefs.doubleTapZoom) }
    var spreadMode        by remember { mutableStateOf(appPrefs.spreadMode) }
    var spreadCoverSingle by remember { mutableStateOf(appPrefs.spreadCoverSingle) }
    var trimMargins       by remember { mutableStateOf(appPrefs.trimMargins) }

    // ── ファイル・フォルダ ────────────────────────────────────────────────
    var homeFolderType     by remember { mutableStateOf(appPrefs.homeFolderType) }
    var homeFolderSafName  by remember {
        mutableStateOf(
            appPrefs.homeFolderSafUri?.let { getSafFolderDisplayName(context, it) }
        )
    }
    var downloadFolderType     by remember { mutableStateOf(appPrefs.downloadFolderType) }
    var downloadFolderSafName  by remember {
        mutableStateOf(
            appPrefs.downloadFolderSafUri?.let { getSafFolderDisplayName(context, it) }
        )
    }

    // SAFフォルダ選択ピッカー（ホームフォルダ用）
    val homeSafPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.pickedSafHomeFolder(uri)
            homeFolderType    = AppPrefs.HomeFolderType.SAF_FOLDER
            homeFolderSafName = getSafFolderDisplayName(context, uri.toString())
        }
    }

    // SAFフォルダ選択ピッカー（DL保存先用）
    val downloadSafPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.pickedSafDownloadFolder(uri)
            downloadFolderType    = AppPrefs.DownloadFolderType.SAF_FOLDER
            downloadFolderSafName = getSafFolderDisplayName(context, uri.toString())
        }
    }

    // ── キャッシュ ────────────────────────────────────────────────────────
    var nasCacheSize             by remember { mutableLongStateOf(calcDirSize(File(context.cacheDir, "nas_cache"))) }
    var thumbnailCacheSize       by remember { mutableLongStateOf(calcDirSize(thumbnailCacheDir)) }
    var showClearNasDialog       by remember { mutableStateOf(false) }
    var showClearThumbnailDialog by remember { mutableStateOf(false) }

    // ── libarchive PoC（検証用・後で削除） ─────────────────────────────
    val coroutineScope = rememberCoroutineScope()
    var libarchivePocRunning by remember { mutableStateOf(false) }
    var libarchivePocResult  by remember { mutableStateOf<String?>(null) }
    val libarchivePocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            libarchivePocRunning = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LibarchivePoc.testArchive(context, uri)
                }
                libarchivePocRunning = false
                libarchivePocResult  = result
            }
        }
    }

    libarchivePocResult?.let { result ->
        AlertDialog(
            onDismissRequest = { libarchivePocResult = null },
            title = { Text("libarchive PoC 結果") },
            text  = {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { libarchivePocResult = null }) { Text("閉じる") }
            }
        )
    }

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
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearNasDialog = false }) { Text("キャンセル") }
            }
        )
    }

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
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
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

            // ════════════════════════════════════════════════════
            // 📖 読む
            // ════════════════════════════════════════════════════
            SettingsSectionHeader("📖  読む")

            // ── ページ送り方向 ─────────────────────────────────
            SettingsItemHeader(
                title       = "ページ送り方向",
                description = "タップ・スワイプでページが進む方向"
            )
            AppPrefs.PageDirection.entries.forEach { dir ->
                SettingsRadioItem(
                    label       = dir.label,
                    description = dir.description,
                    selected    = pageDirection == dir,
                    onSelect    = {
                        pageDirection          = dir
                        appPrefs.pageDirection = dir
                    }
                )
            }

            SettingsDivider()

            // ── ページ送りアニメーション ───────────────────────
            SettingsSwitchItem(
                title       = "ページ送りアニメーション",
                description = "スワイプ時のバウンスアニメーションの有無",
                checked     = pageAnimation,
                onCheckedChange = {
                    pageAnimation          = it
                    appPrefs.pageAnimation = it
                }
            )

            SettingsDivider()

            // ── 音量ボタンでページ送り ─────────────────────────
            SettingsSwitchItem(
                title       = "音量ボタンでページ送り",
                description = "音量UP＝前のページ、音量DOWN＝次のページ",
                checked     = volumeKeyPageTurn,
                onCheckedChange = {
                    volumeKeyPageTurn          = it
                    appPrefs.volumeKeyPageTurn = it
                }
            )

            SettingsDivider()

            // ── ズームバウンス ─────────────────────────────────
            SettingsSwitchItem(
                title       = "ズームバウンス",
                description = "ピンチズームの最大・最小倍率でバウンスする",
                checked     = zoomBounce,
                onCheckedChange = {
                    zoomBounce          = it
                    appPrefs.zoomBounce = it
                }
            )

            SettingsDivider()

            // ── ダブルタップズーム率 ───────────────────────────
            SettingsItemHeader(
                title       = "ダブルタップズーム率",
                description = "閲覧中のダブルタップで拡大する倍率"
            )
            AppPrefs.DoubleTapZoom.entries.forEach { zoom ->
                SettingsRadioItem(
                    label       = zoom.label,
                    description = "",
                    selected    = doubleTapZoom == zoom,
                    onSelect    = {
                        doubleTapZoom          = zoom
                        appPrefs.doubleTapZoom = zoom
                    }
                )
            }

            SettingsDivider()

            // ── 見開き表示 ───────────────────────────────
            SettingsItemHeader(
                title       = "見開き表示",
                description = "2ページを並べて表示する（紙の本と同じ見え方）"
            )
            AppPrefs.SpreadMode.entries.forEach { mode ->
                SettingsRadioItem(
                    label       = mode.label,
                    description = mode.description,
                    selected    = spreadMode == mode,
                    onSelect    = {
                        spreadMode          = mode
                        appPrefs.spreadMode = mode
                    }
                )
            }

            if (spreadMode != AppPrefs.SpreadMode.OFF) {
                Spacer(Modifier.height(8.dp))
                SettingsSwitchItem(
                    title       = "表紙を単独で表示",
                    description = "1ページ目だけを単独表示し、2ページ目以降を見開きにする。マンガは通常ON",
                    checked     = spreadCoverSingle,
                    onCheckedChange = {
                        spreadCoverSingle          = it
                        appPrefs.spreadCoverSingle = it
                    }
                )
            }

            SettingsDivider()

            // ── 余白削除 ────────────────────────────────
            SettingsSwitchItem(
                title       = "余白を削除",
                description = "ページ周囲の白・黒のフチを自動で切り落とし、本文を大きく表示する",
                checked     = trimMargins,
                onCheckedChange = {
                    trimMargins          = it
                    appPrefs.trimMargins = it
                }
            )

            Spacer(Modifier.height(24.dp))

            // ════════════════════════════════════════════════════
            // 🗂️ ファイル・フォルダ
            // ════════════════════════════════════════════════════
            SettingsSectionHeader("🗂️  ファイル・フォルダ")

            // ── Homeフォルダ ───────────────────────────────────
            SettingsItemHeader(
                title       = "Homeフォルダ",
                description = "コミックを表示するデフォルトの場所"
            )
            SettingsRadioItem(
                label       = "ComicVeilフォルダ（推奨）",
                description = "アプリ専用領域。権限不要で常にアクセス可能",
                selected    = homeFolderType == AppPrefs.HomeFolderType.APP_FOLDER,
                onSelect    = {
                    homeFolderType          = AppPrefs.HomeFolderType.APP_FOLDER
                    appPrefs.homeFolderType = AppPrefs.HomeFolderType.APP_FOLDER
                }
            )
            SettingsRadioItem(
                label       = "フォルダを選択",
                description = if (homeFolderType == AppPrefs.HomeFolderType.SAF_FOLDER && homeFolderSafName != null)
                    "選択中：$homeFolderSafName（タップで変更）"
                else
                    "端末内の好きなフォルダを選んで使用します",
                selected    = homeFolderType == AppPrefs.HomeFolderType.SAF_FOLDER,
                onSelect    = { homeSafPickerLauncher.launch(null) }
            )

            SettingsDivider()

            // ── DL保存先 ───────────────────────────────────────
            SettingsItemHeader(
                title       = "DL保存先",
                description = "DLモードでダウンロードしたファイルの保存先"
            )
            SettingsRadioItem(
                label       = "ComicVeilフォルダ（推奨）",
                description = "アプリ専用領域。権限不要で常にアクセス可能",
                selected    = downloadFolderType == AppPrefs.DownloadFolderType.APP_FOLDER,
                onSelect    = {
                    downloadFolderType          = AppPrefs.DownloadFolderType.APP_FOLDER
                    appPrefs.downloadFolderType = AppPrefs.DownloadFolderType.APP_FOLDER
                }
            )
            SettingsRadioItem(
                label       = "フォルダを選択",
                description = if (downloadFolderType == AppPrefs.DownloadFolderType.SAF_FOLDER && downloadFolderSafName != null)
                    "選択中：$downloadFolderSafName（タップで変更）"
                else
                    "端末内の好きなフォルダを選んで保存します",
                selected    = downloadFolderType == AppPrefs.DownloadFolderType.SAF_FOLDER,
                onSelect    = { downloadSafPickerLauncher.launch(null) }
            )

            Spacer(Modifier.height(24.dp))

            // ════════════════════════════════════════════════════
            // 🗄️ キャッシュ管理
            // ════════════════════════════════════════════════════
            SettingsSectionHeader("🗄️  キャッシュ管理")

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

            // ════════════════════════════════════════════════════
            // 🧪 libarchive PoC（検証用・後で削除）
            // ════════════════════════════════════════════════════
            Spacer(Modifier.height(24.dp))
            SettingsSectionHeader("🧪  libarchive PoC（RAR5検証用）")
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("RAR5ファイルを選んでlibarchiveで読む", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text  = if (libarchivePocRunning) "実行中..." else "タップしてファイルを選択",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick  = { libarchivePocLauncher.launch(arrayOf("*/*")) },
                    enabled  = !libarchivePocRunning
                ) { Text("選択") }
            }

            // ════════════════════════════════════════════════════
            // ℹ️ バージョン情報
            // ════════════════════════════════════════════════════
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

private fun getSafFolderDisplayName(context: android.content.Context, uriString: String): String? {
    return try {
        DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriString))?.name
    } catch (e: Exception) {
        null
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 共通コンポーザブル
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsItemHeader(title: String, description: String) {
    Text(text = title, style = MaterialTheme.typography.bodyMedium)
    if (description.isNotEmpty()) {
        Text(
            text  = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun SettingsRadioItem(
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
            if (description.isNotEmpty()) {
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotEmpty()) {
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
