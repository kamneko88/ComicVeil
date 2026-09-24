package com.kamneko88.comicveil.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.nas.NasServerPrefs
import com.kamneko88.comicveil.data.nas.RemoteBookmarkPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RestoreSummary(
    val readingProgressCount: Int = 0,
    val fileTitleCount: Int = 0,
    val fileCount: Int = 0,
    val bookmarkCount: Int = 0,
    val nasServerCount: Int = 0,
    val remoteBookmarkCount: Int = 0,
    val settingsRestored: Boolean = false,
    val serverIdsNeedingPassword: List<String> = emptyList()
) {
    /** キャッシュ済みの状態（ソート条件・NASサーバー一覧など）を持つ画面があるため再起動が必要 */
    val needsAppRestart: Boolean
        get() = settingsRestored || nasServerCount > 0 || remoteBookmarkCount > 0
}

/**
 * バックアップの読み込み・検証・復元。
 */
class BackupRestorer(
    private val context: Context,
    private val destination: BackupDestination = LocalFileBackupDestination()
) {

    /** ファイルを読み込みサイズ上限を確認した上でJSONとして検証する */
    suspend fun loadAndValidate(uri: Uri): Result<BackupPayload> = withContext(Dispatchers.IO) {
        try {
            val declaredSize = querySize(uri)
            if (declaredSize != null && declaredSize > BackupJsonCodec.MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(
                    BackupParseException("バックアップファイルが大きすぎます（上限50MB）")
                )
            }
            val bytes = destination.read(context, uri)
            if (bytes.size > BackupJsonCodec.MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(
                    BackupParseException("バックアップファイルが大きすぎます（上限50MB）")
                )
            }
            val payload = BackupJsonCodec.fromJson(String(bytes, Charsets.UTF_8))
            Result.success(payload)
        } catch (e: BackupParseException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(BackupParseException("バックアップファイルを読み込めませんでした"))
        }
    }

    private fun querySize(uri: Uri): Long? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else null
            }
    }

    /**
     * 復元を実行する。
     * NASサーバーのパスワードが暗号化されていて含めたい場合は passphrase を渡す。
     * passphrase が誤っている場合は [BackupPassphraseException] で失敗する。
     */
    suspend fun restore(
        payload: BackupPayload,
        selectedItems: Set<BackupItemType>,
        includeNasPasswords: Boolean,
        passphrase: CharArray?
    ): Result<RestoreSummary> = withContext(Dispatchers.IO) {
        try {
            val effectiveItems = selectedItems.filter { it in payload.items }.toSet()

            var passwords: Map<String, String> = emptyMap()
            if (BackupItemType.NAS_SERVERS in effectiveItems && includeNasPasswords) {
                val nas = payload.nasServers
                if (nas != null && nas.passwordsEncrypted && nas.nasPasswordsEncrypted != null) {
                    if (passphrase == null) {
                        return@withContext Result.failure(
                            BackupPassphraseException("パスフレーズが違います")
                        )
                    }
                    val decrypted = BackupCrypto.decrypt(nas.nasPasswordsEncrypted, passphrase)
                    val json = JSONObject(String(decrypted, Charsets.UTF_8))
                    val map = mutableMapOf<String, String>()
                    json.keys().forEach { key -> map[key] = json.getString(key) }
                    passwords = map
                }
            }

            // ─── DB側の復元（1トランザクション） ───────────────────────────
            val db = ComicVeilDatabase.getDatabase(context)
            val dbRestorer = BackupDatabaseRestorer(db)
            val dbCounts = dbRestorer.restore(
                readingProgress = if (BackupItemType.READING_HISTORY in effectiveItems) payload.readingHistory?.progress else null,
                fileTitles = if (BackupItemType.READING_HISTORY in effectiveItems) payload.readingHistory?.titles else null,
                files = if (BackupItemType.RATINGS_LABELS in effectiveItems) payload.files else null,
                bookmarks = if (BackupItemType.BOOKMARKS in effectiveItems) payload.bookmarks else null
            )

            // ─── SharedPreferences側の復元（DB成功後のみ） ─────────────────
            var settingsRestored = false
            if (BackupItemType.SETTINGS in effectiveItems) {
                payload.settingsApp?.let { BackupPrefsIo.writeAppSettings(context, it) }
                payload.settingsSort?.let { BackupPrefsIo.writeSortSettings(context, it) }
                settingsRestored = true
            }

            var nasServerCount = 0
            var remoteBookmarkCount = 0
            var serverIdsNeedingPassword: List<String> = emptyList()
            if (BackupItemType.NAS_SERVERS in effectiveItems && payload.nasServers != null) {
                val nasServerPrefs = NasServerPrefs(context)
                val remoteBookmarkPrefs = RemoteBookmarkPrefs(context)

                val mergeResult = BackupMerge.mergeNasServers(
                    existing = nasServerPrefs.getServers(),
                    incoming = payload.nasServers.servers,
                    passwords = passwords
                )
                mergeResult.toUpsert.forEach { nasServerPrefs.saveServer(it) }
                nasServerCount = mergeResult.toUpsert.size
                serverIdsNeedingPassword = mergeResult.newServerIdsWithEmptyPassword

                val newBookmarks = BackupMerge.filterNewRemoteBookmarks(
                    existing = remoteBookmarkPrefs.getBookmarks(),
                    incoming = payload.nasServers.remoteBookmarks
                )
                newBookmarks.forEach { remoteBookmarkPrefs.add(it) }
                remoteBookmarkCount = newBookmarks.size
            }

            Result.success(
                RestoreSummary(
                    readingProgressCount = dbCounts.readingProgress,
                    fileTitleCount = dbCounts.fileTitles,
                    fileCount = dbCounts.files,
                    bookmarkCount = dbCounts.bookmarks,
                    nasServerCount = nasServerCount,
                    remoteBookmarkCount = remoteBookmarkCount,
                    settingsRestored = settingsRestored,
                    serverIdsNeedingPassword = serverIdsNeedingPassword
                )
            )
        } catch (e: BackupPassphraseException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
