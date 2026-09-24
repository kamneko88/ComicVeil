package com.kamneko88.comicveil.data.backup

/** バックアップに含められる項目。JSON上のキー名（jsonKey）は将来も変更しない。 */
enum class BackupItemType(val jsonKey: String, val label: String, val description: String) {
    SETTINGS(
        "settings",
        "設定",
        "読む・見た目・キャッシュ上限などの設定（ホームフォルダ・DLフォルダ・アプリロックは含みません）"
    ),
    READING_HISTORY(
        "readingHistory",
        "読書記録",
        "各本の読書位置・総ページ数・最終閲覧日時"
    ),
    RATINGS_LABELS(
        "ratingsLabels",
        "評価・カラーラベル・既読状態",
        "★評価・カラーラベル・未読/読書中/既読の状態"
    ),
    BOOKMARKS(
        "bookmarks",
        "しおり",
        "本の中に挟んだしおり"
    ),
    NAS_SERVERS(
        "nasServers",
        "NASサーバー設定",
        "登録したNASサーバーと、HOMEに登録したNASのショートカット"
    )
}

data class BackupFileRecord(
    val filePath: String,
    val status: Int,
    val rating: Int,
    val colorLabel: Int,
    val registeredAt: Long
)

data class BackupProgressRecord(
    val filePath: String,
    val currentPage: Int,
    val totalPages: Int,
    val lastReadAt: Long
)

data class BackupTitleRecord(
    val filePath: String,
    val originalName: String
)

data class BackupBookmarkRecord(
    val filePath: String,
    val page: Int,
    val createdAt: Long
)

data class BackupNasServerRecord(
    val id: String,
    val displayName: String,
    val host: String,
    val shareName: String,
    val username: String
)

data class BackupRemoteBookmarkRecord(
    val id: String,
    val serverId: String,
    val nasPath: String,
    val name: String,
    val isFolder: Boolean
)

/** PBKDF2＋AES-256-GCMで暗号化した値。すべてBase64文字列としてJSONに入れる。 */
data class EncryptedBlob(
    val cipherText: String,
    val salt: String,
    val iv: String,
    val iterations: Int
)

data class BackupReadingHistoryPayload(
    val progress: List<BackupProgressRecord>,
    val titles: List<BackupTitleRecord>
)

data class BackupNasServersPayload(
    val servers: List<BackupNasServerRecord>,
    val remoteBookmarks: List<BackupRemoteBookmarkRecord>,
    /**
     * true の場合、サーバーごとのパスワードは nasPasswordsEncrypted に暗号化して含まれる。
     * パスワードを含める場合は常に暗号化する（平文で保存する経路は存在しない）。
     */
    val passwordsEncrypted: Boolean,
    /** サーバーID → パスワードのJSONオブジェクトを暗号化したもの（passwordsEncrypted が true の場合のみ） */
    val nasPasswordsEncrypted: EncryptedBlob? = null
)

/** バックアップファイル全体（JSONのトップレベルに対応） */
data class BackupPayload(
    val schemaVersion: Int,
    val appVersionName: String,
    val createdAt: Long,
    val items: List<BackupItemType>,
    val settingsApp: Map<String, Any>? = null,
    val settingsSort: Map<String, Any>? = null,
    val readingHistory: BackupReadingHistoryPayload? = null,
    val files: List<BackupFileRecord>? = null,
    val bookmarks: List<BackupBookmarkRecord>? = null,
    val nasServers: BackupNasServersPayload? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class BackupParseException(message: String) : Exception(message)

class BackupPassphraseException(message: String) : Exception(message)
