package com.kamneko88.comicveil.data.backup

import com.kamneko88.comicveil.data.AppPrefs

/**
 * バックアップに含める app_settings のキー許可リスト（allowlist方式）。
 *
 * ホームフォルダ・DLフォルダのSAF URI・アプリロック関連キーは、機種変更で無効になる／
 * 他端末へ持ち出すべきでないため、意図的にこの一覧から除外している
 * （home_folder_type・home_folder_saf_uri・download_folder_saf_uri・lock_mode・
 * lock_pin_hash・lock_pin_salt）。
 *
 * ここに列挙されていないキーはバックアップにも復元にも一切関与しない。
 */
object BackupSettingsKeys {

    /** app_settings（SharedPreferences）の実体名。AppPrefs.kt と同じ文字列。 */
    const val PREFS_NAME = "app_settings"

    enum class Kind { BOOLEAN, FLOAT, ENUM }

    data class KeyDef(
        val key: String,
        val kind: Kind,
        val validValues: Set<String> = emptySet()
    )

    val ALLOWLIST: List<KeyDef> = listOf(
        KeyDef("page_direction", Kind.ENUM, AppPrefs.PageDirection.entries.map { it.name }.toSet()),
        KeyDef("page_animation", Kind.BOOLEAN),
        KeyDef("page_turn_animation", Kind.BOOLEAN),
        KeyDef("volume_key_page_turn", Kind.BOOLEAN),
        KeyDef("zoom_bounce", Kind.BOOLEAN),
        KeyDef("double_tap_zoom", Kind.ENUM, AppPrefs.DoubleTapZoom.entries.map { it.name }.toSet()),
        KeyDef("spread_mode", Kind.ENUM, AppPrefs.SpreadMode.entries.map { it.name }.toSet()),
        KeyDef("spread_cover_single", Kind.BOOLEAN),
        KeyDef("split_wide_pages", Kind.BOOLEAN),
        KeyDef("spread_gutter", Kind.ENUM, AppPrefs.SpreadGutter.entries.map { it.name }.toSet()),
        KeyDef("background_color", Kind.ENUM, AppPrefs.BackgroundColor.entries.map { it.name }.toSet()),
        KeyDef("trim_mode", Kind.ENUM, AppPrefs.TrimMode.entries.map { it.name }.toSet()),
        KeyDef("trim_keep_aspect", Kind.BOOLEAN),
        KeyDef("list_display_mode", Kind.ENUM, AppPrefs.ListDisplayMode.entries.map { it.name }.toSet()),
        KeyDef("shelf_show_title", Kind.BOOLEAN),
        KeyDef("viewer_brightness", Kind.FLOAT),
        KeyDef("page_cache_limit", Kind.ENUM, AppPrefs.PageCacheLimit.entries.map { it.name }.toSet()),
        KeyDef("nas_stream_cache_limit", Kind.ENUM, AppPrefs.NasStreamCacheLimit.entries.map { it.name }.toSet()),
        KeyDef("app_theme", Kind.ENUM, AppPrefs.AppTheme.entries.map { it.name }.toSet())
    )

    private val byKey: Map<String, KeyDef> = ALLOWLIST.associateBy { it.key }

    /**
     * 許可リストのキー・型に合致する値だけを残す（復元時の入力検証用）。
     * 未知のキー・型不一致・列挙型の不正値は読み飛ばす。
     */
    fun filterValid(raw: Map<String, Any?>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in raw) {
            val def = byKey[key] ?: continue
            when (def.kind) {
                Kind.BOOLEAN -> if (value is Boolean) result[key] = value
                Kind.FLOAT -> when (value) {
                    is Float -> result[key] = value
                    is Double -> result[key] = value.toFloat()
                    is Int -> result[key] = value.toFloat()
                    is Long -> result[key] = value.toFloat()
                }
                Kind.ENUM -> if (value is String && def.validValues.contains(value)) {
                    result[key] = value
                }
            }
        }
        return result
    }
}
