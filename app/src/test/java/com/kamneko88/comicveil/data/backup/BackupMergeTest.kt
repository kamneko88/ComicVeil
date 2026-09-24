package com.kamneko88.comicveil.data.backup

import com.kamneko88.comicveil.data.db.Bookmark
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.RemoteBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {

    // ─── しおり ───────────────────────────────────────────────────────

    @Test
    fun `bookmark with same filePath and page as existing is skipped`() {
        val existing = listOf(Bookmark(id = 1, filePath = "a.zip", page = 5, createdAt = 100L))
        val incoming = listOf(BackupBookmarkRecord("a.zip", page = 5, createdAt = 200L))

        val result = BackupMerge.filterNewBookmarks(existing, incoming)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `bookmark with new page is kept`() {
        val existing = listOf(Bookmark(id = 1, filePath = "a.zip", page = 5, createdAt = 100L))
        val incoming = listOf(BackupBookmarkRecord("a.zip", page = 6, createdAt = 200L))

        val result = BackupMerge.filterNewBookmarks(existing, incoming)

        assertEquals(1, result.size)
        assertEquals(6, result[0].page)
    }

    @Test
    fun `duplicate bookmarks within the incoming list are only added once`() {
        val incoming = listOf(
            BackupBookmarkRecord("a.zip", page = 1, createdAt = 100L),
            BackupBookmarkRecord("a.zip", page = 1, createdAt = 200L)
        )

        val result = BackupMerge.filterNewBookmarks(emptyList(), incoming)

        assertEquals(1, result.size)
    }

    // ─── NASサーバー ─────────────────────────────────────────────────

    @Test
    fun `existing server is updated but keeps its password when not supplied`() {
        val existing = listOf(
            NasServer(id = "s1", displayName = "旧名前", host = "old-host", username = "user", password = "secret")
        )
        val incoming = listOf(
            BackupNasServerRecord(id = "s1", displayName = "新名前", host = "new-host", shareName = "share", username = "user2")
        )

        val result = BackupMerge.mergeNasServers(existing, incoming, passwords = emptyMap())

        assertEquals(1, result.toUpsert.size)
        val merged = result.toUpsert[0]
        assertEquals("新名前", merged.displayName)
        assertEquals("new-host", merged.host)
        assertEquals("secret", merged.password)
        assertTrue(result.newServerIdsWithEmptyPassword.isEmpty())
    }

    @Test
    fun `existing server password is overwritten when a new password is supplied`() {
        val existing = listOf(
            NasServer(id = "s1", displayName = "name", host = "host", username = "user", password = "old-pw")
        )
        val incoming = listOf(
            BackupNasServerRecord(id = "s1", displayName = "name", host = "host", shareName = "", username = "user")
        )

        val result = BackupMerge.mergeNasServers(existing, incoming, passwords = mapOf("s1" to "new-pw"))

        assertEquals("new-pw", result.toUpsert[0].password)
    }

    @Test
    fun `new server without password is added with empty password and flagged`() {
        val incoming = listOf(
            BackupNasServerRecord(id = "s2", displayName = "new server", host = "host", shareName = "", username = "user")
        )

        val result = BackupMerge.mergeNasServers(existing = emptyList(), incoming = incoming, passwords = emptyMap())

        assertEquals(1, result.toUpsert.size)
        assertEquals("", result.toUpsert[0].password)
        assertEquals(listOf("s2"), result.newServerIdsWithEmptyPassword)
    }

    @Test
    fun `new server with supplied password is added without being flagged`() {
        val incoming = listOf(
            BackupNasServerRecord(id = "s3", displayName = "new server", host = "host", shareName = "", username = "user")
        )

        val result = BackupMerge.mergeNasServers(
            existing = emptyList(),
            incoming = incoming,
            passwords = mapOf("s3" to "pw")
        )

        assertEquals("pw", result.toUpsert[0].password)
        assertTrue(result.newServerIdsWithEmptyPassword.isEmpty())
    }

    // ─── リモートブックマーク ─────────────────────────────────────────

    @Test
    fun `remote bookmark with same serverId and nasPath as existing is skipped`() {
        val existing = listOf(
            RemoteBookmark(id = "b1", serverId = "s1", nasPath = "/comics", name = "Comics", isFolder = true)
        )
        val incoming = listOf(
            BackupRemoteBookmarkRecord(id = "b2", serverId = "s1", nasPath = "/comics", name = "Comics renamed", isFolder = true)
        )

        val result = BackupMerge.filterNewRemoteBookmarks(existing, incoming)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `remote bookmark with new path is kept and preserves backup id`() {
        val incoming = listOf(
            BackupRemoteBookmarkRecord(id = "backup-id", serverId = "s1", nasPath = "/new", name = "New", isFolder = false)
        )

        val result = BackupMerge.filterNewRemoteBookmarks(emptyList(), incoming)

        assertEquals(1, result.size)
        assertEquals("backup-id", result[0].id)
    }
}
