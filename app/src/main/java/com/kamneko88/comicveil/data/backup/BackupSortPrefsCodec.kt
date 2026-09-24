package com.kamneko88.comicveil.data.backup

import com.kamneko88.comicveil.data.SortPrefs

/**
 * sort_prefs（SharedPreferences）の5キー全体をバックアップ対象とするための検証ロジック。
 * app_settings と違い許可リストではなく全キー対象だが、型・列挙値は同様に検証する。
 * Android非依存（SortPrefsのenum定義のみ参照）なのでJVM単体テストで検証できる。
 */
object BackupSortPrefsCodec {

    const val KEY_SORT_KEY = "sort_key"
    const val KEY_ASCENDING = "ascending"
    const val KEY_FOLDER_ORDER = "folder_order"
    const val KEY_STATUS_FILTER = "status_filter"
    const val KEY_COLOR_LABEL_FILTER = "color_label_filter"

    private val sortKeyNames = SortPrefs.SortKey.entries.map { it.name }.toSet()
    private val folderOrderNames = SortPrefs.FolderOrder.entries.map { it.name }.toSet()

    /** 未知のキー・型不一致・列挙型の不正値は読み飛ばす */
    fun filterValid(raw: Map<String, Any?>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        (raw[KEY_SORT_KEY] as? String)?.let { if (it in sortKeyNames) result[KEY_SORT_KEY] = it }
        (raw[KEY_ASCENDING] as? Boolean)?.let { result[KEY_ASCENDING] = it }
        (raw[KEY_FOLDER_ORDER] as? String)?.let { if (it in folderOrderNames) result[KEY_FOLDER_ORDER] = it }

        val statusFilter = raw[KEY_STATUS_FILTER]
        if (statusFilter is Set<*>) {
            result[KEY_STATUS_FILTER] = statusFilter.filterIsInstance<String>().toSet()
        }
        val colorLabelFilter = raw[KEY_COLOR_LABEL_FILTER]
        if (colorLabelFilter is Set<*>) {
            result[KEY_COLOR_LABEL_FILTER] = colorLabelFilter.filterIsInstance<String>().toSet()
        }

        return result
    }
}
