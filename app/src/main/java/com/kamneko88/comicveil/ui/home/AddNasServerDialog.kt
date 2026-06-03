package com.kamneko88.comicveil.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kamneko88.comicveil.data.nas.NasServer

/**
 * リモートサーバー追加・編集ダイアログ
 * editServer が null → 新規追加モード
 * editServer がある → 編集モード
 *
 * 共有フォルダ名（shareName）は省略可能。
 * 将来的にはショートカット機能で個別指定できるようにする予定。
 */
@Composable
fun AddNasServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (NasServer) -> Unit,
    editServer: NasServer? = null
) {
    var displayName by remember { mutableStateOf(editServer?.displayName ?: "") }
    var host        by remember { mutableStateOf(editServer?.host        ?: "") }
    var shareName   by remember { mutableStateOf(editServer?.shareName   ?: "") }
    var username    by remember { mutableStateOf(editServer?.username    ?: "") }
    var password    by remember { mutableStateOf(editServer?.password    ?: "") }

    val isValid    = host.isNotBlank()
    val isEditMode = editServer != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditMode) "リモートを編集" else "リモートを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── 表示名 ──────────────────────────────────────────────
                OutlinedTextField(
                    value         = displayName,
                    onValueChange = { displayName = it },
                    label         = { Text("表示名（省略可）") },
                    placeholder   = { Text("例：自宅NAS") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                // ── IPアドレス / ホスト名 ────────────────────────────────
                OutlinedTextField(
                    value         = host,
                    onValueChange = { host = it },
                    label         = { Text("IPアドレス / ホスト名") },
                    placeholder   = { Text("例：192.168.1.100") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                // ── 共有フォルダ名 ───────────────────────────────────────
                OutlinedTextField(
                    value         = shareName,
                    onValueChange = { shareName = it },
                    label         = { Text("共有フォルダ名（省略可）") },
                    placeholder   = { Text("例：share_data") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                Text(
                    text     = "※ 省略するとサーバーのルートに接続します。共有フォルダはショートカットで個別登録できます（未実装）",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                // ── ユーザー名 ───────────────────────────────────────────
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text("ユーザー名（省略可）") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                // ── パスワード ───────────────────────────────────────────
                OutlinedTextField(
                    value                = password,
                    onValueChange        = { password = it },
                    label                = { Text("パスワード（省略可）") },
                    singleLine           = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier             = Modifier.fillMaxWidth()
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
