package com.kamneko88.comicveil.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ファイルメタデータテーブル
 * 読書状態・タグ・評価など、ファイルに紐づくアプリ管理情報を保存する
 * Day 15以降でタグ・評価・カラーラベル・フラグ等を追加予定
 */
@Entity(tableName = "files")
data class ComicFile(
    @PrimaryKey
    val filePath: String,           // ファイル識別子（絶対パス or NAS URI）
    val status: Int = STATUS_UNREAD, // 読書状態
    val registeredAt: Long = System.currentTimeMillis() // 初回登録日時
) {
    companion object {
        const val STATUS_UNREAD  = 0  // 未読
        const val STATUS_READING = 1  // 読書中
        const val STATUS_READ    = 2  // 既読
    }
}

/** 読書状態を表す enum（UI層で使用） */
enum class ReadStatus(val value: Int, val label: String) {
    UNREAD (ComicFile.STATUS_UNREAD,  "未読"),
    READING(ComicFile.STATUS_READING, "読書中"),
    READ   (ComicFile.STATUS_READ,    "既読");

    companion object {
        fun fromValue(value: Int): ReadStatus =
            entries.firstOrNull { it.value == value } ?: UNREAD
    }
}