package com.kamneko88.comicveil.data.nas

import java.util.UUID

data class NasServer(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val host: String,
    val shareName: String = "",   // 共有フォルダ名（省略可・ショートカットで個別指定）
    val username: String,
    val password: String
)