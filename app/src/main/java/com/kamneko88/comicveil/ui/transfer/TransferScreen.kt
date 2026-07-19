package com.kamneko88.comicveil.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.RefreshCw
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kamneko88.comicveil.data.nas.TransferItem
import com.kamneko88.comicveil.data.nas.TransferStatus

/**
 * ファイル転送画面
 *
 * @param viewModel       TransferViewModel（アプリ全体で共有）
 * @param fromFolderName  呼び出し元フォルダ名（nullの場合はHOMEから開いた扱い）
 * @param onClose         閉じる／戻るボタンのコールバック
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    viewModel: TransferViewModel,
    fromFolderName: String? = null,
    onClose: () -> Unit
) {
    val items by viewModel.items.collectAsState()

    // タブ管理（0=転送中, 1=履歴）
    var selectedTab by remember { mutableIntStateOf(0) }

    // フィルタリング
    val activeItems  = items.filter { it.status == TransferStatus.WAITING || it.status == TransferStatus.TRANSFERRING }
    val historyItems = items.filter { it.isFinished }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("ファイル転送") },
                    navigationIcon = {
                        if (fromFolderName != null) {
                            // NAS階層から開いた場合：「◀ フォルダ名」
                            Row(
                                modifier = Modifier
                                    .clickable { onClose() }
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector        = Lucide.ArrowLeft,
                                    contentDescription = "戻る",
                                    tint               = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text     = fromFolderName,
                                    color    = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            // HOMEから開いた場合：「閉じる」テキストボタン
                            TextButton(onClick = onClose) {
                                Text("閉じる")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                // タブ
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick  = { selectedTab = 0 },
                        text     = {
                            Text(
                                text = if (activeItems.isEmpty()) "転送中"
                                else "転送中（${activeItems.size}）"
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick  = { selectedTab = 1 },
                        text     = {
                            Text(
                                text = if (historyItems.isEmpty()) "履歴"
                                else "履歴（${historyItems.size}）"
                            )
                        }
                    )
                }
            }
        },
        bottomBar = {
            // タブによって下部ボタンを切り替え
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    if (selectedTab == 0 && activeItems.isNotEmpty()) {
                        TextButton(onClick = { viewModel.cancelAll() }) {
                            Text(
                                text  = "全てキャンセル",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (selectedTab == 1 && historyItems.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text(
                                text  = "履歴を削除",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> ActiveTransferTab(
                    items         = activeItems,
                    onCancelCurrent = { viewModel.cancelCurrent() },
                    onCancelWaiting = { viewModel.cancelWaiting(it) }
                )
                1 -> HistoryTab(
                    items   = historyItems,
                    onRetry = { viewModel.retry(it) }
                )
            }
        }
    }
}

// ─── 転送中タブ ───────────────────────────────────────────────────────────

@Composable
private fun ActiveTransferTab(
    items: List<TransferItem>,
    onCancelCurrent: () -> Unit,
    onCancelWaiting: (String) -> Unit
) {
    if (items.isEmpty()) {
        EmptyMessage("転送中のファイルはありません")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            when (item.status) {
                TransferStatus.TRANSFERRING -> TransferringRow(
                    item     = item,
                    onCancel = onCancelCurrent
                )
                TransferStatus.WAITING -> WaitingRow(
                    item     = item,
                    onCancel = { onCancelWaiting(item.id) }
                )
                else -> Unit
            }
            HorizontalDivider()
        }
    }
}

/** 転送中の行 */
@Composable
private fun TransferringRow(item: TransferItem, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // ファイル名
        Text(
            text     = item.fileName,
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))

        // プログレスバー
        if (item.fraction != null) {
            LinearProgressIndicator(
                progress = { item.fraction!! },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))

        // サイズ表示 ＋ キャンセルボタン
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text  = item.progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCancel) {
                Text(
                    text  = "キャンセル",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 待機中の行 */
@Composable
private fun WaitingRow(item: TransferItem, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.weight(1f)
        ) {
            Icon(
                imageVector        = Lucide.Hourglass,
                contentDescription = "待機中",
                modifier           = Modifier.size(18.dp),
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text     = item.fileName,
                    style    = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text  = "待機中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onCancel) {
            Text(
                text  = "キャンセル",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ─── 履歴タブ ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(
    items: List<TransferItem>,
    onRetry: (TransferItem) -> Unit
) {
    if (items.isEmpty()) {
        EmptyMessage("転送履歴はありません")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            HistoryRow(item = item, onRetry = { onRetry(item) })
            HorizontalDivider()
        }
    }
}

/** 履歴の行 */
@Composable
private fun HistoryRow(item: TransferItem, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 再転送可能なアイテムはタップで再転送
            .then(if (item.canRetry) Modifier.clickable { onRetry() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ステータスアイコン
        StatusIcon(status = item.status)
        Spacer(Modifier.width(12.dp))

        // ファイル名・ステータスラベル
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = item.fileName,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text  = statusLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(item.status)
            )
        }

        // 再転送可能なら「再転送」アイコンを表示
        if (item.canRetry) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector        = Lucide.RefreshCw,
                contentDescription = "再転送",
                modifier           = Modifier.size(20.dp),
                tint               = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** ステータスアイコン */
@Composable
private fun StatusIcon(status: TransferStatus) {
    val (icon, tint) = when (status) {
        TransferStatus.COMPLETED  -> Lucide.CircleCheck to Color(0xFF4CAF50)
        TransferStatus.CANCELLED  -> Lucide.CircleX to MaterialTheme.colorScheme.onSurfaceVariant
        TransferStatus.ERROR      -> Lucide.CircleAlert to MaterialTheme.colorScheme.error
        else                      -> Lucide.Hourglass to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector        = icon,
        contentDescription = null,
        modifier           = Modifier.size(24.dp),
        tint               = tint
    )
}

/** ステータスラベルテキスト */
private fun statusLabel(item: TransferItem): String = when (item.status) {
    TransferStatus.COMPLETED -> "転送完了"
    TransferStatus.CANCELLED -> "キャンセル済み　タップで再転送"
    TransferStatus.ERROR     -> "エラー：${item.errorMessage ?: "不明"}　タップで再転送"
    else                     -> ""
}

/** ステータスに応じた文字色 */
@Composable
private fun statusColor(status: TransferStatus): Color = when (status) {
    TransferStatus.COMPLETED -> Color(0xFF4CAF50)
    TransferStatus.ERROR     -> MaterialTheme.colorScheme.error
    else                     -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ─── 共通 ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyMessage(message: String) {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text  = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}