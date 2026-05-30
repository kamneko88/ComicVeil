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
 * NASサーバー追加ダイアログ
 * editServer が null → 新規追加モード
 * editServer がある → 編集モード
 */
@Composable
fun AddNasServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (NasServer) -> Unit,
    editServer: NasServer? = null       // 編集時は既存サーバーを渡す
) {
    // 編集モードの場合は既存の値を初期値にセット
    var displayName by remember { mutableStateOf(editServer?.displayName ?: "自宅NAS") }
    var host        by remember { mutableStateOf(editServer?.host        ?: "") }
    var shareName   by remember { mutableStateOf(editServer?.shareName   ?: "") }
    var username    by remember { mutableStateOf(editServer?.username    ?: "") }
    var password    by remember { mutableStateOf(editServer?.password    ?: "") }

    val isValid    = host.isNotBlank() && shareName.isNotBlank()
    val isEditMode = editServer != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditMode) "NASサーバーを編集" else "NASサーバーを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── 表示名 ──────────────────────────────────────────────
                OutlinedTextField(
                    value         = displayName,
                    onValueChange = { displayName = it },
                    label         = { Text("表示名") },
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
                Text(
                    text     = "※ プレースホルダーは入力例です。タップして入力してください",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                // ── 共有フォルダ名 ───────────────────────────────────────
                OutlinedTextField(
                    value         = shareName,
                    onValueChange = { shareName = it },
                    label         = { Text("共有フォルダ名") },
                    placeholder   = { Text("例：share_data") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
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
                            // 編集モードの場合は元のIDを引き継ぐ
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