package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * ファイルの「本来の名前」を覚えておくテーブル。
 *
 * リモート（NAS）の本はキャッシュに `nas_-409694946.zip` のような名前で保存されるため、
 * パスからは作品名が分からない。開いたときに元の名前を控えておき、
 * 閲覧履歴などで正しいタイトルを表示できるようにする。
 *
 * 読書位置（reading_progress）とは別テーブルにしてある。
 * 同じテーブルにすると、読書位置を保存するたびに名前が消えてしまうため。
 */
@Entity(tableName = "file_titles")
data class FileTitle(
    @PrimaryKey
    val filePath: String,
    /** 元のファイル名（例：「(一般コミック) [奥浩哉] GANTZ 第37巻.zip」） */
    val originalName: String
)

@Dao
interface FileTitleDao {

    @Upsert
    suspend fun save(title: FileTitle)

    @Query("SELECT * FROM file_titles")
    suspend fun getAll(): List<FileTitle>

    @Query("SELECT originalName FROM file_titles WHERE filePath = :filePath")
    suspend fun getName(filePath: String): String?
}
