package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    // ─── 閲覧履歴 ───────────────────────────────────
    // 読書位置には「最終閲覧日時」が入っているので、それを新しい順に並べれば
    // そのまま閲覧履歴になる（専用テーブルは不要）。

    /** 閲覧履歴（新しい順）を監視する */
    @Query("SELECT * FROM reading_progress ORDER BY lastReadAt DESC")
    fun observeHistory(): Flow<List<ReadingProgress>>

    /** 履歴をすべて削除する */
    @Query("DELETE FROM reading_progress")
    suspend fun clearHistory()
}