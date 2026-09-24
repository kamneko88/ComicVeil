package com.kamneko88.comicveil.data.backup

import android.content.Context
import androidx.core.content.edit

/**
 * app_settings・sort_prefs（SharedPreferences）への生の読み書き。
 *
 * AppPrefs・SortPrefsクラスは特定の型付きプロパティ経由のアクセスしか提供していないため、
 * バックアップ用の許可リスト方式の読み書き（[BackupSettingsKeys]・[BackupSortPrefsCodec]）は
 * 同じSharedPreferencesファイルへ直接アクセスする（キー名・ファイル名はAppPrefs.kt / SortPrefs.kt
 * と揃えてある。両クラスの実装自体は変更しない）。
 */
object BackupPrefsIo {

    private const val SORT_PREFS_NAME = "sort_prefs"

    /** 許可リストに載っているapp_settingsの値だけを読み出す */
    fun readAppSettings(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences(BackupSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return BackupSettingsKeys.filterValid(prefs.all)
    }

    /** 検証済みの値をapp_settingsへ書き戻す */
    fun writeAppSettings(context: Context, values: Map<String, Any>) {
        val validated = BackupSettingsKeys.filterValid(values)
        if (validated.isEmpty()) return
        val prefs = context.getSharedPreferences(BackupSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val defs = BackupSettingsKeys.ALLOWLIST.associateBy { it.key }
        prefs.edit {
            validated.forEach { (key, value) ->
                when (defs.getValue(key).kind) {
                    BackupSettingsKeys.Kind.BOOLEAN -> putBoolean(key, value as Boolean)
                    BackupSettingsKeys.Kind.FLOAT -> putFloat(key, value as Float)
                    BackupSettingsKeys.Kind.ENUM -> putString(key, value as String)
                }
            }
        }
    }

    /** sort_prefsの値を読み出す */
    fun readSortSettings(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
        return BackupSortPrefsCodec.filterValid(prefs.all)
    }

    /** 検証済みの値をsort_prefsへ書き戻す */
    fun writeSortSettings(context: Context, values: Map<String, Any>) {
        val validated = BackupSortPrefsCodec.filterValid(values)
        if (validated.isEmpty()) return
        val prefs = context.getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            validated.forEach { (key, value) ->
                when (key) {
                    BackupSortPrefsCodec.KEY_ASCENDING -> putBoolean(key, value as Boolean)
                    BackupSortPrefsCodec.KEY_STATUS_FILTER,
                    BackupSortPrefsCodec.KEY_COLOR_LABEL_FILTER -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                    else -> putString(key, value as String)
                }
            }
        }
    }
}
