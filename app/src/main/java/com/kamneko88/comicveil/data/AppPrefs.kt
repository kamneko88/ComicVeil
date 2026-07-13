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

    /** ページ送りのアニメーション自体の有無（OFFで即座に切り替わる・非力な端末向け） */
    var pageTurnAnimation: Boolean
        get() = prefs.getBoolean(KEY_PAGE_TURN_ANIMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_TURN_ANIMATION, value).apply()

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

    // ─── 見開き表示 ──────────────────────────────────────

    enum class SpreadMode(val label: String, val description: String) {
        OFF("しない", "常に1ページずつ表示する"),
        LANDSCAPE("横向きのときだけ", "端末を横にすると2ページ並べて表示する"),
        ALWAYS("常に見開き", "縦向きでも2ページ並べて表示する")
    }

    var spreadMode: SpreadMode
        get() = runCatching {
            SpreadMode.valueOf(
                prefs.getString(KEY_SPREAD_MODE, SpreadMode.OFF.name) ?: SpreadMode.OFF.name
            )
        }.getOrDefault(SpreadMode.OFF)
        set(value) = prefs.edit().putString(KEY_SPREAD_MODE, value.name).apply()

    /** 見開き時に1ページ目（表紙）を単独で表示するか。マンガは通常ON。 */
    var spreadCoverSingle: Boolean
        get() = prefs.getBoolean(KEY_SPREAD_COVER_SINGLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SPREAD_COVER_SINGLE, value).apply()

    /** 見開きの綴じ代（左右ページの中央に入れる余白。画像幅に対する割合） */
    enum class SpreadGutter(val percent: Int, val label: String) {
        G1(1, "1%"),
        G3(3, "3%"),
        G5(5, "5%"),
        G10(10, "10%")
    }

    var spreadGutter: SpreadGutter
        get() = runCatching {
            SpreadGutter.valueOf(
                prefs.getString(KEY_SPREAD_GUTTER, SpreadGutter.G1.name) ?: SpreadGutter.G1.name
            )
        }.getOrDefault(SpreadGutter.G1)
        set(value) = prefs.edit().putString(KEY_SPREAD_GUTTER, value.name).apply()

    // 背景色

    enum class BackgroundColor(val label: String) {
        BLACK("黒"),
        WHITE("白"),
        GRAY("グレー")
    }

    var backgroundColor: BackgroundColor
        get() = runCatching {
            BackgroundColor.valueOf(
                prefs.getString(KEY_BACKGROUND_COLOR, BackgroundColor.BLACK.name)
                    ?: BackgroundColor.BLACK.name
            )
        }.getOrDefault(BackgroundColor.BLACK)
        set(value) = prefs.edit().putString(KEY_BACKGROUND_COLOR, value.name).apply()

    // ─── 余白削除 ───────────────────────────────────────

    /** ページ周囲の白・黒の余白を自動で切り落とし、画面を広く使う */
    enum class TrimMode(val label: String, val description: String) {
        OFF("しない", "余白をそのまま表示する"),
        ON("削除する", "白・黒どちらの余白も切り落とす"),
        WHITE_ONLY("白い余白のみ", "黒背景のページはそのままにする")
    }

    var trimMode: TrimMode
        get() = runCatching {
            TrimMode.valueOf(prefs.getString(KEY_TRIM_MODE, TrimMode.OFF.name) ?: TrimMode.OFF.name)
        }.getOrDefault(TrimMode.OFF)
        set(value) = prefs.edit().putString(KEY_TRIM_MODE, value.name).apply()

    /** 余白削除後も元の縦横比を保つ（ページごとに大きさが変わるのを防ぐ） */
    var trimKeepAspect: Boolean
        get() = prefs.getBoolean(KEY_TRIM_KEEP_ASPECT, true)
        set(value) = prefs.edit().putBoolean(KEY_TRIM_KEEP_ASPECT, value).apply()

    // ─── ファイル一覧表示モード ───────────────────────────────────────────

    enum class ListDisplayMode { DETAIL, COMPACT, SHELF }

    var listDisplayMode: ListDisplayMode
        get() = ListDisplayMode.valueOf(
            prefs.getString(KEY_LIST_DISPLAY_MODE, ListDisplayMode.DETAIL.name)
                ?: ListDisplayMode.DETAIL.name
        )
        set(value) = prefs.edit().putString(KEY_LIST_DISPLAY_MODE, value.name).apply()

    /** 本棚モードで表紙の下にタイトルを表示するか（初期値は表紙のみ） */
    var shelfShowTitle: Boolean
        get() = prefs.getBoolean(KEY_SHELF_SHOW_TITLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHELF_SHOW_TITLE, value).apply()

    companion object {
        private const val KEY_HOME_FOLDER            = "home_folder_type"
        private const val KEY_HOME_FOLDER_SAF_URI    = "home_folder_saf_uri"
        private const val KEY_DOWNLOAD_FOLDER        = "download_folder_type"
        private const val KEY_DOWNLOAD_FOLDER_SAF_URI = "download_folder_saf_uri"
        private const val KEY_PAGE_DIRECTION         = "page_direction"
        private const val KEY_PAGE_ANIMATION         = "page_animation"
        private const val KEY_PAGE_TURN_ANIMATION    = "page_turn_animation"
        private const val KEY_VOLUME_KEY_PAGE_TURN   = "volume_key_page_turn"
        private const val KEY_ZOOM_BOUNCE            = "zoom_bounce"
        private const val KEY_DOUBLE_TAP_ZOOM        = "double_tap_zoom"
        private const val KEY_SPREAD_MODE            = "spread_mode"
        private const val KEY_SPREAD_COVER_SINGLE    = "spread_cover_single"
        private const val KEY_SPREAD_GUTTER          = "spread_gutter"
        private const val KEY_BACKGROUND_COLOR       = "background_color"
        private const val KEY_TRIM_MODE              = "trim_mode"
        private const val KEY_TRIM_KEEP_ASPECT       = "trim_keep_aspect"
        private const val KEY_LIST_DISPLAY_MODE      = "list_display_mode"
        private const val KEY_SHELF_SHOW_TITLE       = "shelf_show_title"

        fun getAppFolder(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "Comics")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }
}
