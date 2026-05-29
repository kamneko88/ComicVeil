package com.kamneko88.comicveil.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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

@Composable
fun AddNasServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (NasServer) -> Unit
) {
    var displayName by remember { mutableStateOf("自宅NAS") }
    var host        by remember { mutableStateOf("") }
    var shareName   by remember { mutableStateOf("") }
    var username    by remember { mutableStateOf("") }
    var password    by remember { mutableStateOf("") }

    val isValid = host.isNotBlank() && shareName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NASサーバーを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = displayName,
                    onValueChange = { displayName = it },
                    label         = { Text("表示名") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = host,
                    onValueChange = { host = it },
                    label         = { Text("IPアドレス / ホスト名") },
                    placeholder   = { Text("192.168.1.x") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = shareName,
                    onValueChange = { shareName = it },
                    label         = { Text("共有フォルダ名") },
                    placeholder   = { Text("share_data") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text("ユーザー名") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value                  = password,
                    onValueChange          = { password = it },
                    label                  = { Text("パスワード") },
                    singleLine             = true,
                    visualTransformation   = PasswordVisualTransformation(),
                    modifier               = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(NasServer(
                        displayName = displayName.ifBlank { host },
                        host        = host.trim(),
                        shareName   = shareName.trim(),
                        username    = username.trim(),
                        password    = password
                    ))
                },
                enabled = isValid
            ) { Text("追加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}