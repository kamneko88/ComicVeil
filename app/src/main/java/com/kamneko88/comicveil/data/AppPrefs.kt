package com.kamneko88.comicveil.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * アプリ全体の設定を SharedPreferences で管理するクラス。
 * Room ではなく SharedPreferences を使用（DBマイグレーション不要・軽量）
 */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // ─── Homeフォルダの場所 ───────────────────────────────────────────────

    /** Homeフォルダの種類 */
    enum class HomeFolderType {
        APP_FOLDER,   // アプリ専用フォルダ（デフォルト）
        DOWNLOADS     // Downloadsフォルダ
    }

    var homeFolderType: HomeFolderType
        get() = HomeFolderType.valueOf(
            prefs.getString(KEY_HOME_FOLDER, HomeFolderType.APP_FOLDER.name)
                ?: HomeFolderType.APP_FOLDER.name
        )
        set(value) = prefs.edit().putString(KEY_HOME_FOLDER, value.name).apply()

    // ─── DL保存先 ─────────────────────────────────────────────────────────

    /** DL保存先の種類 */
    enum class DownloadFolderType {
        APP_FOLDER,   // アプリ専用フォルダ（デフォルト）
        DOWNLOADS     // Downloadsフォルダ
    }

    var downloadFolderType: DownloadFolderType
        get() = DownloadFolderType.valueOf(
            prefs.getString(KEY_DOWNLOAD_FOLDER, DownloadFolderType.APP_FOLDER.name)
                ?: DownloadFolderType.APP_FOLDER.name
        )
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value.name).apply()

    // ─── フォルダパス解決 ─────────────────────────────────────────────────

    /**
     * 現在の設定に基づいてHomeフォルダを返す
     * @param context アプリのContext
     */
    fun resolveHomeFolder(context: Context): File = when (homeFolderType) {
        HomeFolderType.APP_FOLDER -> getAppFolder(context)
        HomeFolderType.DOWNLOADS  -> getDownloadsFolder()
    }

    /**
     * 現在の設定に基づいてDL保存先フォルダを返す
     * @param context アプリのContext
     */
    fun resolveDownloadFolder(context: Context): File = when (downloadFolderType) {
        DownloadFolderType.APP_FOLDER -> getAppFolder(context)
        DownloadFolderType.DOWNLOADS  -> File(getDownloadsFolder(), "ComicVeil")
    }

    // ─── ダブルタップズーム率 ───────────────────────────────────────────

    enum class DoubleTapZoom(val scale: Float, val label: String) {
        ZOOM_120(1.2f, "120%"),
        ZOOM_135(1.35f, "135%"),
        ZOOM_150(1.5f, "150%")
    }

    var doubleTapZoom: DoubleTapZoom
        get() = DoubleTapZoom.valueOf(
            prefs.getString(KEY_DOUBLE_TAP_ZOOM, DoubleTapZoom.ZOOM_120.name)
                ?: DoubleTapZoom.ZOOM_120.name
        )
        set(value) = prefs.edit().putString(KEY_DOUBLE_TAP_ZOOM, value.name).apply()

    // ─── ファイル一覧表示モード ───────────────────────────────────────────

    enum class ListDisplayMode { DETAIL, COMPACT }

    var listDisplayMode: ListDisplayMode
        get() = ListDisplayMode.valueOf(
            prefs.getString(KEY_LIST_DISPLAY_MODE, ListDisplayMode.DETAIL.name)
                ?: ListDisplayMode.DETAIL.name
        )
        set(value) = prefs.edit().putString(KEY_LIST_DISPLAY_MODE, value.name).apply()

    companion object {
        private const val KEY_HOME_FOLDER      = "home_folder_type"
        private const val KEY_DOWNLOAD_FOLDER  = "download_folder_type"
        private const val KEY_DOUBLE_TAP_ZOOM  = "double_tap_zoom"
        private const val KEY_LIST_DISPLAY_MODE = "list_display_mode"

        /**
         * アプリ専用フォルダ（外部ストレージ）
         * - PC/ファイルマネージャーからアクセス可能
         * - 他アプリからの干渉なし
         * - アンインストール時に自動削除
         * パス例：/storage/emulated/0/Android/data/com.kamneko88.comicveil/files/Comics
         */
        fun getAppFolder(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "Comics")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /**
         * Downloadsフォルダ
         * - 他のアプリとの共有領域
         * - ユーザーが明示的に選択した場合のみ使用
         */
        fun getDownloadsFolder(): File =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
}
