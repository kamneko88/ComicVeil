package com.kamneko88.comicveil.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsKeysTest {

    @Test
    fun `excluded keys are never present in the filtered result`() {
        // ホームフォルダ・DLフォルダのSAF URI・アプリロック関連は、実際のapp_settingsに
        // 保存され得る値を模して混ぜても、絶対に出力に含まれてはならない
        val raw: Map<String, Any?> = mapOf(
            "home_folder_type" to "SAF_FOLDER",
            "home_folder_saf_uri" to "content://tree/xxx",
            "download_folder_saf_uri" to "content://tree/yyy",
            "lock_mode" to "PIN_ONLY",
            "lock_pin_hash" to "deadbeef",
            "lock_pin_salt" to "cafebabe",
            "page_animation" to true,
            "app_theme" to "DARK"
        )

        val filtered = BackupSettingsKeys.filterValid(raw)

        assertFalse(filtered.containsKey("home_folder_type"))
        assertFalse(filtered.containsKey("home_folder_saf_uri"))
        assertFalse(filtered.containsKey("download_folder_saf_uri"))
        assertFalse(filtered.containsKey("lock_mode"))
        assertFalse(filtered.containsKey("lock_pin_hash"))
        assertFalse(filtered.containsKey("lock_pin_salt"))
        assertEquals(2, filtered.size)
        assertEquals(true, filtered["page_animation"])
        assertEquals("DARK", filtered["app_theme"])
    }

    @Test
    fun `unknown keys are dropped`() {
        val filtered = BackupSettingsKeys.filterValid(mapOf("totally_unknown_key" to "value"))
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `invalid enum value is dropped`() {
        val filtered = BackupSettingsKeys.filterValid(mapOf("app_theme" to "NOT_A_REAL_THEME"))
        assertFalse(filtered.containsKey("app_theme"))
    }

    @Test
    fun `type mismatch is dropped`() {
        // page_animationはBoolean専用。文字列が来たら無視する
        val filtered = BackupSettingsKeys.filterValid(mapOf("page_animation" to "true"))
        assertFalse(filtered.containsKey("page_animation"))
    }

    @Test
    fun `float values from JSON parsing as Double are accepted`() {
        val filtered = BackupSettingsKeys.filterValid(mapOf("viewer_brightness" to 0.5))
        assertEquals(0.5f, filtered["viewer_brightness"])
    }

    @Test
    fun `all allowlisted keys round trip when valid`() {
        val raw: Map<String, Any?> = mapOf(
            "page_direction" to "RIGHT_TO_LEFT",
            "page_animation" to true,
            "page_turn_animation" to true,
            "volume_key_page_turn" to false,
            "zoom_bounce" to true,
            "double_tap_zoom" to "ZOOM_120",
            "spread_mode" to "OFF",
            "spread_cover_single" to true,
            "split_wide_pages" to true,
            "spread_gutter" to "G1",
            "background_color" to "BLACK",
            "trim_mode" to "OFF",
            "trim_keep_aspect" to true,
            "list_display_mode" to "DETAIL",
            "shelf_show_title" to false,
            "viewer_brightness" to 0.8f,
            "page_cache_limit" to "GB1",
            "nas_stream_cache_limit" to "GB3",
            "app_theme" to "DARK"
        )
        val filtered = BackupSettingsKeys.filterValid(raw)
        assertEquals(raw.size, filtered.size)
    }
}
