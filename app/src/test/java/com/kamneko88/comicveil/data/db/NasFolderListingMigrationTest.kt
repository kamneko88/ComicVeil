package com.kamneko88.comicveil.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * MIGRATION_5_6（新テーブル追加）が既存データを保ち、新テーブルが使えることを実SQLite（JVM）で確認する。
 * RoomのMigrationはSupportSQLiteDatabase.execSQLでNasFolderListingSchema.CREATE_STATEMENTSを流すだけなので、
 * 同じ文をそのまま流して確かめる。
 */
class NasFolderListingMigrationTest {

    private lateinit var conn: Connection

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // version 5 時点の既存テーブル（MIGRATION_1_2〜4_5と同じ定義）
        exec("CREATE TABLE `files` (`filePath` TEXT NOT NULL PRIMARY KEY, `status` INTEGER NOT NULL DEFAULT 0, `registeredAt` INTEGER NOT NULL DEFAULT 0, `rating` INTEGER NOT NULL DEFAULT 0, `colorLabel` INTEGER NOT NULL DEFAULT 0)")
        exec("CREATE TABLE `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `filePath` TEXT NOT NULL, `page` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        exec("CREATE TABLE `file_titles` (`filePath` TEXT NOT NULL PRIMARY KEY, `originalName` TEXT NOT NULL)")
        exec("INSERT INTO files VALUES ('smb://h/s/a.zip', 2, 100, 4, 3)")
        exec("INSERT INTO bookmarks (filePath, page, createdAt) VALUES ('smb://h/s/a.zip', 7, 200)")
        exec("INSERT INTO file_titles VALUES ('/cache/nas_1.zip', '作品A 第1巻.zip')")
    }

    @After
    fun tearDown() = conn.close()

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    private fun count(table: String): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) }
    }

    private fun migrate() = NasFolderListingSchema.CREATE_STATEMENTS.forEach { exec(it) }

    @Test
    fun `migration keeps existing data`() {
        migrate()

        assertEquals(1, count("files"))
        assertEquals(1, count("bookmarks"))
        assertEquals(1, count("file_titles"))
        conn.createStatement().use { st ->
            st.executeQuery("SELECT status, rating, colorLabel FROM files WHERE filePath = 'smb://h/s/a.zip'").use {
                it.next()
                assertEquals(listOf(2, 4, 3), listOf(it.getInt(1), it.getInt(2), it.getInt(3)))
            }
        }
        assertEquals(0, count("nas_folder_listings"))
        assertEquals(0, count("nas_folder_listing_comics"))
    }

    @Test
    fun `migration is idempotent and new tables accept rows keyed by folder and position`() {
        migrate()
        migrate()

        exec("INSERT INTO nas_folder_listings VALUES ('dir')")
        exec("INSERT INTO nas_folder_listing_comics VALUES ('dir', 0, 'dir/a.zip', 'a.zip')")
        exec("INSERT INTO nas_folder_listing_comics VALUES ('dir', 1, 'dir/b.zip', 'b.zip')")
        exec("INSERT INTO nas_folder_listing_comics VALUES ('other', 0, 'other/a.zip', 'a.zip')")

        assertEquals(1, count("nas_folder_listings"))
        assertEquals(3, count("nas_folder_listing_comics"))
        // 同じ(folderPath, position)は重複できない
        val duplicated = runCatching {
            exec("INSERT INTO nas_folder_listing_comics VALUES ('dir', 0, 'dir/c.zip', 'c.zip')")
        }.isFailure
        assertEquals(true, duplicated)
    }
}
