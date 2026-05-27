package com.kamneko88.comicveil.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {

    // リスト/本棚モードの切り替え状態
    var isListMode by remember { mutableStateOf(true) }

    Scaffold(
        // 上部メニュー
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "HOME",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    // 検索ボタン
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Search, contentDescription = "検索")
                    }
                    // ソートボタン
                    TextButton(onClick = { }) {
                        Text("名前▼")
                    }
                    // リスト/本棚切り替え
                    IconButton(onClick = { isListMode = !isListMode }) {
                        Icon(
                            if (isListMode) Icons.Default.GridView
                            else Icons.Default.List,
                            contentDescription = "表示切替"
                        )
                    }
                    // 編集ボタン
                    TextButton(onClick = { }) {
                        Text("編集")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        // 下部メニュー
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.height(56.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A: Aboutボタン
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "About")
                    }
                    // B: サーバーボタン（仮アイコン）
                    TextButton(onClick = { }) {
                        Text("サーバー")
                    }
                    // C: プリセットボタン
                    TextButton(onClick = { }) {
                        Text("C")
                    }
                    // D: 明るさボタン
                    TextButton(onClick = { }) {
                        Text("D")
                    }
                    // E: 設定ボタン
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            }
        }
    ) { innerPadding ->
        // ファイル・フォルダ表示エリア（仮）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isListMode) "リストモード" else "本棚モード",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}