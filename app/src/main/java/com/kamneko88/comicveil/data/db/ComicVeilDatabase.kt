package com.kamneko88.comicveil.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * アプリ全体で1つだけ存在するRoomデータベース（シングルトン）
 * 今後テーブルを追加する場合は entities に追加し、version を上げる
 */
@Database(
    entities = [ReadingProgress::class],
    version = 1,
    exportSchema = false
)
abstract class ComicVeilDatabase : RoomDatabase() {

    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        @Volatile
        private var INSTANCE: ComicVeilDatabase? = null

        fun getDatabase(context: Context): ComicVeilDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ComicVeilDatabase::class.java,
                    "comic_veil_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}