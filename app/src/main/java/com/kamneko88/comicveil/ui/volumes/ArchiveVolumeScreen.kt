package com.kamneko88.comicveil.ui.volumes

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kamneko88.comicveil.data.db.ReadStatus
import com.kamneko88.comicveil.ui.home.AboutDialog
import com.kamneko88.comicveil.ui.home.ReadStatusBadge
import java.net.URLEncoder

/**
 * 複数の巻フォルダを内包するアーカイブを開いたときに表示する、巻の一覧画面。
 * タップすると該当の巻だけをビューワーで開く。閉じるとこの画面（＝巻一覧）に戻ってくる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveVolumeScreen(
    archivePath: String,
    navController: NavController,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: ArchiveVolumeViewModel = viewModel(
        factory = ArchiveVolumeViewModel.Factory(
            application = context.applicationContext as Application,
            archivePath = archivePath
        )
    )
    val volumes         by viewModel.volumes.collectAsState()
    val isLoading        by viewModel.isLoading.collectAsState()
    val volumeStatuses    by viewModel.volumeStatuses.collectAsState()
    val dialogState        by viewModel.dialogState.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    LaunchedEffect(Unit) {
        viewModel.navigateEvent.collect { key ->
            val encoded = URLEncoder.encode(key, "UTF-8")
            navController.navigate("viewer/$encoded")
        }
    }

    dialogState?.let { state ->
        val hasResume = state.savedPage > 0
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(state.volumeName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text  = { Text(if (hasResume) "前回の続きから読みますか？" else "最初から読みます") },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeReading() }) {
                    Text(if (hasResume) "${state.savedPage + 1} / ${state.totalPages}ページから再開" else "最初から読む")
                }
            },
            dismissButton = {
                if (hasResume) {
                    TextButton(onClick = { viewModel.readFromBeginning() }) { Text("最初から読む") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text     = viewModel.archiveName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // その他メニュー（HOMEと同じ中身）
                    Box {
                        var showMoreMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "その他")
                        }
                        DropdownMenu(
                            expanded         = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text    = { Text("閲覧履歴") },
                                onClick = {
                                    showMoreMenu = false
                                    navController.navigate("history")
                                }
                            )
                            DropdownMenuItem(
                                text    = { Text("このアプリについて") },
                                onClick = {
                                    showMoreMenu    = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    }

                    // 巻の並び順を入れ替える
                    // （SwapVertはHOMEの「転送状況」で使っているので、別のアイコンにする）
                    IconButton(onClick = { viewModel.toggleSortOrder() }) {
                        Icon(
                            imageVector        = Icons.Default.SortByAlpha,
                            contentDescription = "並び順を入れ替え"
                        )
                    }

                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(volumes) { volume ->
                        val status = volumeStatuses[volume] ?: ReadStatus.UNREAD
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onVolumeTapped(volume) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Folder,
                                contentDescription = null,
                                modifier           = Modifier.size(32.dp),
                                tint               = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text     = volume,
                                style    = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (status != ReadStatus.UNREAD) {
                                ReadStatusBadge(status = status)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
