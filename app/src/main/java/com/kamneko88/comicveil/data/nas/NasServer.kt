package com.kamneko88.comicveil.data.nas

import java.util.UUID

data class NasServer(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val host: String,
    val shareName: String,
    val username: String,
    val password: String
)