package com.kamneko88.comicveil.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ブックマークテーブル
 * ファイルパス + ページ番号で一意に識別する
 */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filePath: String,       // ファイルのフルパス
    val page: Int,              // 0始まりのページ番号
    val createdAt: Long = System.currentTimeMillis()
)
