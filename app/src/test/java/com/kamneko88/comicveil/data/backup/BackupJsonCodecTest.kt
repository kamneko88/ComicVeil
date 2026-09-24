package com.kamneko88.comicveil.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupJsonCodecTest {

    private fun samplePayload(): BackupPayload = BackupPayload(
        schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
        appVersionName = "1.17.0",
        createdAt = 1_700_000_000_000L,
        items = listOf(BackupItemType.RATINGS_LABELS, BackupItemType.BOOKMARKS),
        files = listOf(BackupFileRecord("path/a.zip", status = 2, rating = 5, colorLabel = 1, registeredAt = 111L)),
        bookmarks = listOf(BackupBookmarkRecord("path/a.zip", page = 3, createdAt = 222L))
    )

    @Test
    fun `round trip preserves data`() {
        val payload = samplePayload()
        val json = BackupJsonCodec.toJson(payload)
        val restored = BackupJsonCodec.fromJson(json)

        assertEquals(payload.schemaVersion, restored.schemaVersion)
        assertEquals(payload.appVersionName, restored.appVersionName)
        assertEquals(payload.createdAt, restored.createdAt)
        assertEquals(payload.items.toSet(), restored.items.toSet())
        assertEquals(payload.files, restored.files)
        assertEquals(payload.bookmarks, restored.bookmarks)
    }

    @Test(expected = BackupParseException::class)
    fun `schemaVersion newer than supported is rejected`() {
        val payload = samplePayload().copy(schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION + 1)
        val json = BackupJsonCodec.toJson(payload)
        BackupJsonCodec.fromJson(json)
    }

    @Test(expected = BackupParseException::class)
    fun `broken json is rejected`() {
        BackupJsonCodec.fromJson("{ this is not valid json ")
    }

    @Test(expected = BackupParseException::class)
    fun `missing required top level field is rejected`() {
        // schemaVersionが無い
        BackupJsonCodec.fromJson(
            """{"appVersionName":"1.17.0","createdAt":1,"items":["bookmarks"]}"""
        )
    }

    @Test(expected = BackupParseException::class)
    fun `item listed but its payload object missing is rejected`() {
        BackupJsonCodec.fromJson(
            """{"schemaVersion":1,"appVersionName":"1.17.0","createdAt":1,"items":["bookmarks"]}"""
        )
    }

    @Test
    fun `settings round trip via JSON keeps raw values (allowlist filtering happens at restore-write time)`() {
        val payload = BackupPayload(
            schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
            appVersionName = "1.17.0",
            createdAt = 1L,
            items = listOf(BackupItemType.SETTINGS),
            settingsApp = mapOf("page_animation" to true, "app_theme" to "DARK"),
            settingsSort = mapOf("ascending" to true, "sort_key" to "NAME")
        )
        val json = BackupJsonCodec.toJson(payload)
        val restored = BackupJsonCodec.fromJson(json)

        assertEquals(payload.settingsApp, restored.settingsApp)
        assertEquals(payload.settingsSort, restored.settingsSort)
    }
}
