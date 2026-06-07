package com.kamneko88.comicveil.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ファイルメタデータテーブル
 * 読書状態・レーティング・カラーラベルなど、ファイルに紐づくアプリ管理情報を保存する
 */
@Entity(tableName = "files")
data class ComicFile(
    @PrimaryKey
    val filePath: String,
    val status: Int = STATUS_UNREAD,
    val rating: Int = 0,            // レーティング 0=なし、1～5星
    val colorLabel: Int = 0,        // カラーラベル 0=なし、1～5
    val registeredAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_UNREAD  = 0
        const val STATUS_READING = 1
        const val STATUS_READ    = 2
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

/** カラーラベルを表す enum */
enum class ColorLabel(val value: Int, val label: String, val colorHex: Long) {
    NONE  (0, "なし",   0x00000000L),
    RED   (1, "赤",     0xFFE53935L),
    ORANGE(2, "橙",     0xFFFF6F00L),
    GREEN (3, "緑",     0xFF43A047L),
    BLUE  (4, "青",     0xFF1E88E5L),
    PURPLE(5, "紫",     0xFF8E24AAL);

    companion object {
        fun fromValue(value: Int): ColorLabel =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}