package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * filesテーブルのDAO
 * 今日はstatus管理のみ。Day 15でタグ・評価クエリを追加予定。
 */
@Dao
interface ComicFileDao {

    /** ファイルパスでレコードを取得。未登録なら null */
    @Query("SELECT * FROM files WHERE filePath = :filePath")
    suspend fun getComicFile(filePath: String): ComicFile?

    /** レコードを保存（なければ追加、あれば更新） */
    @Upsert
    suspend fun upsertComicFile(comicFile: ComicFile)

    /** 読書状態だけ更新 */
    @Query("UPDATE files SET status = :status WHERE filePath = :filePath")
    suspend fun updateStatus(filePath: String, status: Int)

    /** レコード削除（ファイル削除時など） */
    @Query("DELETE FROM files WHERE filePath = :filePath")
    suspend fun deleteComicFile(filePath: String)
}