package com.kamneko88.comicveil.data.backup

import android.content.Context
import android.net.Uri
import com.kamneko88.comicveil.BuildConfig
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.nas.NasServerPrefs
import com.kamneko88.comicveil.data.nas.RemoteBookmarkPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BackupCreateSummary(
    val settings: Boolean,
    val readingProgressCount: Int,
    val fileTitleCount: Int,
    val fileCount: Int,
    val bookmarkCount: Int,
    val nasServerCount: Int,
    val remoteBookmarkCount: Int
)

/**
 * バックアップ作成（DB・SharedPreferencesから読み出し → JSON化 → 保存先へ書き込み）。
 */
class BackupExecutor(
    private val context: Context,
    private val destination: BackupDestination = LocalFileBackupDestination()
) {

    suspend fun createBackup(
        selectedItems: Set<BackupItemType>,
        includeNasPasswords: Boolean,
        passphrase: CharArray?,
        destinationUri: Uri
    ): Result<BackupCreateSummary> = withContext(Dispatchers.IO) {
        try {
            val db = ComicVeilDatabase.getDatabase(context)

            var settingsApp: Map<String, Any>? = null
            var settingsSort: Map<String, Any>? = null
            if (BackupItemType.SETTINGS in selectedItems) {
                settingsApp = BackupPrefsIo.readAppSettings(context)
                settingsSort = BackupPrefsIo.readSortSettings(context)
            }

            var readingHistory: BackupReadingHistoryPayload? = null
            var progressCount = 0
            var titleCount = 0
            if (BackupItemType.READING_HISTORY in selectedItems) {
                val progress = db.readingProgressDao().getAll().map {
                    BackupProgressRecord(it.filePath, it.currentPage, it.totalPages, it.lastReadAt)
                }
                val titles = db.fileTitleDao().getAll().map {
                    BackupTitleRecord(it.filePath, it.originalName)
                }
                progressCount = progress.size
                titleCount = titles.size
                readingHistory = BackupReadingHistoryPayload(progress, titles)
            }

            var files: List<BackupFileRecord>? = null
            if (BackupItemType.RATINGS_LABELS in selectedItems) {
                files = db.comicFileDao().getAll().map {
                    BackupFileRecord(it.filePath, it.status, it.rating, it.colorLabel, it.registeredAt)
                }
            }

            var bookmarks: List<BackupBookmarkRecord>? = null
            if (BackupItemType.BOOKMARKS in selectedItems) {
                bookmarks = db.bookmarkDao().getAll().map {
                    BackupBookmarkRecord(it.filePath, it.page, it.createdAt)
                }
            }

            var nasServersPayload: BackupNasServersPayload? = null
            var nasServerCount = 0
            var remoteBookmarkCount = 0
            if (BackupItemType.NAS_SERVERS in selectedItems) {
                val servers = NasServerPrefs(context).getServers()
                val remoteBookmarks = RemoteBookmarkPrefs(context).getBookmarks()
                nasServerCount = servers.size
                remoteBookmarkCount = remoteBookmarks.size

                val serverRecords = servers.map {
                    BackupNasServerRecord(it.id, it.displayName, it.host, it.shareName, it.username)
                }
                val bookmarkRecords = remoteBookmarks.map {
                    BackupRemoteBookmarkRecord(it.id, it.serverId, it.nasPath, it.name, it.isFolder)
                }

                nasServersPayload = if (includeNasPasswords && passphrase != null) {
                    val passwordsJson = JSONObject()
                    servers.forEach { passwordsJson.put(it.id, it.password) }
                    val encrypted = BackupCrypto.encrypt(
                        passwordsJson.toString().toByteArray(Charsets.UTF_8),
                        passphrase
                    )
                    BackupNasServersPayload(
                        servers = serverRecords,
                        remoteBookmarks = bookmarkRecords,
                        passwordsEncrypted = true,
                        nasPasswordsEncrypted = encrypted
                    )
                } else {
                    BackupNasServersPayload(
                        servers = serverRecords,
                        remoteBookmarks = bookmarkRecords,
                        passwordsEncrypted = false
                    )
                }
            }

            val payload = BackupPayload(
                schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
                appVersionName = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis(),
                items = BackupItemType.entries.filter { it in selectedItems },
                settingsApp = settingsApp,
                settingsSort = settingsSort,
                readingHistory = readingHistory,
                files = files,
                bookmarks = bookmarks,
                nasServers = nasServersPayload
            )

            val json = BackupJsonCodec.toJson(payload)
            destination.write(context, destinationUri, json.toByteArray(Charsets.UTF_8))

            Result.success(
                BackupCreateSummary(
                    settings = BackupItemType.SETTINGS in selectedItems,
                    readingProgressCount = progressCount,
                    fileTitleCount = titleCount,
                    fileCount = files?.size ?: 0,
                    bookmarkCount = bookmarks?.size ?: 0,
                    nasServerCount = nasServerCount,
                    remoteBookmarkCount = remoteBookmarkCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
