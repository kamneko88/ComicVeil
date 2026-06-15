package com.kamneko88.comicveil.data

import android.content.Context
import android.content.SharedPreferences

/**
 * ソート・フィルター設定の永続化
 * SharedPreferences で管理（DB migration 不要）
 */
class SortPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)

    // ── ソートキー ────────────────────────────────────────────────────────

    enum class SortKey(val label: String) {
        NAME("名前"),
        DATE("更新日"),
        RATING("レーティング")
    }

    var sortKey: SortKey
        get() = SortKey.valueOf(prefs.getString("sort_key", SortKey.NAME.name) ?: SortKey.NAME.name)
        set(v) = prefs.edit().putString("sort_key", v.name).apply()

    // ── 昇順/降順 ────────────────────────────────────────────────────────

    var ascending: Boolean
        get() = prefs.getBoolean("ascending", true)
        set(v) = prefs.edit().putBoolean("ascending", v).apply()

    // ── フォルダ表示順 ───────────────────────────────────────────────────

    enum class FolderOrder(val label: String) {
        FOLDER_FIRST("フォルダ優先"),
        FILE_FIRST("ファイル優先"),
        MIXED("混在")
    }

    var folderOrder: FolderOrder
        get() = FolderOrder.valueOf(
            prefs.getString("folder_order", FolderOrder.FOLDER_FIRST.name)
                ?: FolderOrder.FOLDER_FIRST.name
        )
        set(v) = prefs.edit().putString("folder_order", v.name).apply()

    // ── フィルター：読書状態 ─────────────────────────────────────────────
    // null = フィルターなし、値あり = 指定状態のみ表示

    /** アクティブな読書状態フィルター（カンマ区切りで複数保存） */
    var statusFilter: Set<String>
        get() = prefs.getStringSet("status_filter", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("status_filter", v).apply()

    // ── フィルター：カラーラベル ─────────────────────────────────────────
    // 空 = フィルターなし、値あり = 指定ラベルのみ表示

    var colorLabelFilter: Set<String>
        get() = prefs.getStringSet("color_label_filter", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("color_label_filter", v).apply()

    /** すべてのフィルターをリセット */
    fun clearFilters() {
        prefs.edit()
            .remove("status_filter")
            .remove("color_label_filter")
            .apply()
    }

    /** フィルターが1つでもアクティブか */
    val hasActiveFilter: Boolean
        get() = statusFilter.isNotEmpty() || colorLabelFilter.isNotEmpty()
}
