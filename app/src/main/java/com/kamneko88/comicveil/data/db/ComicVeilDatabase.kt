package com.kamneko88.comicveil.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * アプリ全体で1つだけ存在するRoomデータベース（シングルトン）
 * version 2：filesテーブルを追加
 */
@Database(
    entities = [ReadingProgress::class, ComicFile::class, Bookmark::class],
    version = 4,
    exportSchema = false
)
abstract class ComicVeilDatabase : RoomDatabase() {

    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun comicFileDao(): ComicFileDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {

        @Volatile
        private var INSTANCE: ComicVeilDatabase? = null

        /** version 1 → 2：filesテーブルを追加 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `files` (
                        `filePath`      TEXT NOT NULL PRIMARY KEY,
                        `status`        INTEGER NOT NULL DEFAULT 0,
                        `registeredAt`  INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** version 2 → 3：bookmarksテーブルを追加 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id`         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath`   TEXT NOT NULL,
                        `page`       INTEGER NOT NULL,
                        `createdAt`  INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** version 3 → 4：filesテーブルに rating / colorLabel を追加 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `files` ADD COLUMN `rating` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `files` ADD COLUMN `colorLabel` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): ComicVeilDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ComicVeilDatabase::class.java,
                    "comic_veil_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}