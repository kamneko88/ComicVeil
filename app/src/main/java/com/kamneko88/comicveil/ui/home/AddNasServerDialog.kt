package com.kamneko88.comicveil.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kamneko88.comicveil.data.nas.NasServer
import kotlinx.coroutines.launch

/**
 * リモートサーバー追加・編集ダイアログ
 * editServer が null → 新規追加モード
 * editServer がある → 編集モード
 *
 * IP・ユーザー名・パスワードを入れて「接続して共有フォルダを取得」を押すと、
 * サーバーに接続して共有フォルダ一覧を取得し、リストから選べる（接続テストも兼ねる）。
 * 取得できない環境（RPC非対応など）では、共有フォルダ名を手入力すればよい。
 */
@Composable
fun AddNasServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (NasServer) -> Unit,
    onListShares: suspend (host: String, username: String, password: String) -> Result<List<String>>,
    editServer: NasServer? = null
) {
    var displayName by remember { mutableStateOf(editServer?.displayName ?: "") }
    var host        by remember { mutableStateOf(editServer?.host        ?: "") }
    var shareName   by remember { mutableStateOf(editServer?.shareName   ?: "") }
    var username    by remember { mutableStateOf(editServer?.username    ?: "") }
    var password    by remember { mutableStateOf(editServer?.password    ?: "") }

    var loading    by remember { mutableStateOf(false) }
    var shares     by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val scope      = rememberCoroutineScope()
    val isValid    = host.isNotBlank()
    val isEditMode = editServer != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditMode) "リモートを編集" else "リモートを追加") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    label = { Text("表示名（省略可）") }, placeholder = { Text("例：自宅NAS") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("IPアドレス / ホスト名") }, placeholder = { Text("例：192.168.1.100") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("ユーザー名（省略可）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("パスワード（省略可）") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                // ── 接続して共有フォルダを取得（接続テスト兼用） ─────────────
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true; fetchError = null
                            onListShares(host.trim(), username.trim(), password)
                                .onSuccess { shares = it; fetchError = null }
                                .onFailure { shares = null; fetchError = it.message ?: "接続に失敗しました" }
                            loading = false
                        }
                    },
                    enabled  = host.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("接続中…")
                    } else {
                        Text("接続して共有フォルダを取得")
                    }
                }

                // ── 取得結果 ─────────────────────────────────────────────
                fetchError?.let {
                    Text(
                        text  = "接続に失敗：$it\n共有フォルダ名は下に手入力もできます。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                shares?.let { list ->
                    if (list.isEmpty()) {
                        Text(
                            text  = "共有フォルダが見つかりませんでした。手入力してください。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("共有フォルダを選択：", style = MaterialTheme.typography.labelMedium)
                        list.forEach { s ->
                            ShareRow(name = s, selected = shareName == s) { shareName = s }
                        }
                        ShareRow(name = "（指定しない：ルートに接続）", selected = shareName.isBlank()) { shareName = "" }
                    }
                }

                // ── 共有フォルダ名（選択が入る／手入力も可） ─────────────────
                OutlinedTextField(
                    value = shareName, onValueChange = { shareName = it },
                    label = { Text("共有フォルダ名（省略可）") }, placeholder = { Text("例：share_data") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        NasServer(
                            id          = editServer?.id ?: java.util.UUID.randomUUID().toString(),
                            displayName = displayName.ifBlank { host },
                            host        = host.trim(),
                            shareName   = shareName.trim(),
                            username    = username.trim(),
                            password    = password
                        )
                    )
                },
                enabled = isValid
            ) {
                Text(if (isEditMode) "保存" else "追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun ShareRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text  = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else          MaterialTheme.colorScheme.onSurface
        )
    }
}
