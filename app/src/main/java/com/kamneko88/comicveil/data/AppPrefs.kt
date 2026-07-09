package com.kamneko88.comicveil.data

import android.content.Context
import java.io.File

/**
 * アプリ全体の設定を SharedPreferences で管理するクラス。
 * Room ではなく SharedPreferences を使用（DBマイグレーション不要・軽量）
 */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // ─── Homeフォルダの場所 ───────────────────────────────────────────────

    enum class HomeFolderType {
        APP_FOLDER,  // アプリ専用フォルダ（デフォルト・権限不要）
        SAF_FOLDER   // ユーザーがSAFで選んだ任意のフォルダ
    }

    var homeFolderType: HomeFolderType
        get() = runCatching {
            HomeFolderType.valueOf(
                prefs.getString(KEY_HOME_FOLDER, HomeFolderType.APP_FOLDER.name)
                    ?: HomeFolderType.APP_FOLDER.name
            )
        }.getOrDefault(HomeFolderType.APP_FOLDER) // 旧バージョンのDOWNLOADS等、未知の値が保存されていた場合の保険
        set(value) = prefs.edit().putString(KEY_HOME_FOLDER, value.name).apply()

    /** SAFで選択したホームフォルダのツリーURI（未選択ならnull） */
    var homeFolderSafUri: String?
        get() = prefs.getString(KEY_HOME_FOLDER_SAF_URI, null)
        set(value) = prefs.edit().putString(KEY_HOME_FOLDER_SAF_URI, value).apply()

    // ─── DL保存先 ─────────────────────────────────────────────────────────

    enum class DownloadFolderType {
        APP_FOLDER,  // アプリ専用フォルダ（デフォルト・権限不要）
        SAF_FOLDER   // ユーザーがSAFで選んだ任意のフォルダ
    }

    var downloadFolderType: DownloadFolderType
        get() = runCatching {
            DownloadFolderType.valueOf(
                prefs.getString(KEY_DOWNLOAD_FOLDER, DownloadFolderType.APP_FOLDER.name)
                    ?: DownloadFolderType.APP_FOLDER.name
            )
        }.getOrDefault(DownloadFolderType.APP_FOLDER) // 旧バージョンのDOWNLOADS等、未知の値が保存されていた場合の保険
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value.name).apply()

    /** SAFで選択したDL保存先のツリーURI（未選択ならnull） */
    var downloadFolderSafUri: String?
        get() = prefs.getString(KEY_DOWNLOAD_FOLDER_SAF_URI, null)
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER_SAF_URI, value).apply()

    // ─── フォルダパス解決（アプリ専用フォルダのみ。SAF側は呼び出し元で分岐） ──────

    fun resolveHomeFolder(context: Context): File = getAppFolder(context)

    fun resolveDownloadFolder(context: Context): File = getAppFolder(context)

    // ─── ページ送り方向 ───────────────────────────────────────────────────

    enum class PageDirection(val label: String, val description: String) {
        RIGHT_TO_LEFT("右綴じ（マンガ）", "右→左にページが進む。日本のマンガに最適"),
        LEFT_TO_RIGHT("左綴じ（洋書・縦読み）", "左→右にページが進む。洋書・ウェブトゥーンに最適")
    }

    var pageDirection: PageDirection
        get() = PageDirection.valueOf(
            prefs.getString(KEY_PAGE_DIRECTION, PageDirection.RIGHT_TO_LEFT.name)
                ?: PageDirection.RIGHT_TO_LEFT.name
        )
        set(value) = prefs.edit().putString(KEY_PAGE_DIRECTION, value.name).apply()

    // ─── ページ送りアニメーション ─────────────────────────────────────────

    var pageAnimation: Boolean
        get() = prefs.getBoolean(KEY_PAGE_ANIMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_ANIMATION, value).apply()

    // ─── 音量ボタンでページ送り ───────────────────────────────────────────

    var volumeKeyPageTurn: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_KEY_PAGE_TURN, false)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_KEY_PAGE_TURN, value).apply()

    // ─── ズームバウンス ───────────────────────────────────────────────────

    var zoomBounce: Boolean
        get() = prefs.getBoolean(KEY_ZOOM_BOUNCE, true)
        set(value) = prefs.edit().putBoolean(KEY_ZOOM_BOUNCE, value).apply()

    // ─── ダブルタップズーム率 ─────────────────────────────────────────────

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
        private const val KEY_HOME_FOLDER            = "home_folder_type"
        private const val KEY_HOME_FOLDER_SAF_URI    = "home_folder_saf_uri"
        private const val KEY_DOWNLOAD_FOLDER        = "download_folder_type"
        private const val KEY_DOWNLOAD_FOLDER_SAF_URI = "download_folder_saf_uri"
        private const val KEY_PAGE_DIRECTION         = "page_direction"
        private const val KEY_PAGE_ANIMATION         = "page_animation"
        private const val KEY_VOLUME_KEY_PAGE_TURN   = "volume_key_page_turn"
        private const val KEY_ZOOM_BOUNCE            = "zoom_bounce"
        private const val KEY_DOUBLE_TAP_ZOOM        = "double_tap_zoom"
        private const val KEY_LIST_DISPLAY_MODE      = "list_display_mode"

        fun getAppFolder(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "Comics")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }
}
