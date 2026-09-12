package com.kamneko88.comicveil.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kamneko88.comicveil.data.CloudProviderEntry

/**
 * 「ファイルを取り込む」タップ時に表示する取り込み元選択ダイアログ。
 * ダウンロードフォルダ・検出済みクラウドプロバイダ・標準ピッカー（その他）の
 * 3種類のエントリを縦に並べる。
 *
 * 各プロバイダの実アイコンはPackageManager.getApplicationIcon()等の追加実装が
 * 必要になるため今回のスコープでは扱わず、テキストのみの行にする。
 */
@Composable
fun CloudPickerDialog(
    providers: List<CloudProviderEntry>,
    onSelectDownloads: () -> Unit,
    onSelectProvider: (CloudProviderEntry) -> Unit,
    onSelectOther: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取り込み元を選ぶ") },
        text = {
            Column {
                TextButton(onClick = onSelectDownloads, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ダウンロードフォルダ",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                providers.forEach { provider ->
                    TextButton(
                        onClick = { onSelectProvider(provider) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = provider.label,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onSelectOther, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "その他（すべてのアプリから選ぶ）",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
