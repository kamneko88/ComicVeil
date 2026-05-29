package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * 読書位置のDAO（Data Access Object）
 * Upsert = INSERT OR UPDATE（存在すれば更新、なければ挿入）
 */
@Dao
interface ReadingProgressDao {

    /** ファイルパスで読書位置を取得。未登録なら null */
    @Query("SELECT * FROM reading_progress WHERE filePath = :filePath")
    suspend fun getProgress(filePath: String): ReadingProgress?

    /** 読書位置を保存（なければ追加、あれば更新） */
    @Upsert
    suspend fun saveProgress(progress: ReadingProgress)

    /** 読書位置を削除（ファイル削除時など） */
    @Query("DELETE FROM reading_progress WHERE filePath = :filePath")
    suspend fun deleteProgress(filePath: String)
}