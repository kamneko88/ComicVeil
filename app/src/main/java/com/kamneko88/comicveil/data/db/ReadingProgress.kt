package com.kamneko88.comicveil.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 読書位置の保存エンティティ
 * filePath をPKとして、ファイルごとに現在ページ・総ページ数・最終閲覧日時を管理する
 */
@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey
    val filePath: String,           // ファイルの絶対パス（ファイルの識別子）
    val currentPage: Int,           // 現在ページ（0始まり）
    val totalPages: Int,            // 総ページ数
    val lastReadAt: Long = System.currentTimeMillis()  // 最終閲覧日時（エポックms）
)