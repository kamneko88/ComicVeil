package com.kamneko88.comicveil.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * バックアップのJSON形式との相互変換。
 * Android非依存（org.jsonのみ）なのでJVM単体テストで検証できる。
 */
object BackupJsonCodec {

    /** これより大きいバックアップファイルは読み込み前に拒否する */
    const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

    fun toJson(payload: BackupPayload): String {
        val root = JSONObject()
        root.put("schemaVersion", payload.schemaVersion)
        root.put("appVersionName", payload.appVersionName)
        root.put("createdAt", payload.createdAt)
        root.put("items", JSONArray(payload.items.map { it.jsonKey }))

        payload.settingsApp?.let { app ->
            val settingsObj = JSONObject()
            val appObj = JSONObject()
            app.forEach { (k, v) -> appObj.put(k, v) }
            settingsObj.put("app", appObj)
            payload.settingsSort?.let { sort ->
                val sortObj = JSONObject()
                sort.forEach { (k, v) ->
                    when (v) {
                        is Set<*> -> sortObj.put(k, JSONArray(v))
                        else -> sortObj.put(k, v)
                    }
                }
                settingsObj.put("sort", sortObj)
            }
            root.put("settings", settingsObj)
        }

        payload.readingHistory?.let { rh ->
            val obj = JSONObject()
            obj.put("progress", JSONArray(rh.progress.map { p ->
                JSONObject().apply {
                    put("filePath", p.filePath)
                    put("currentPage", p.currentPage)
                    put("totalPages", p.totalPages)
                    put("lastReadAt", p.lastReadAt)
                }
            }))
            obj.put("titles", JSONArray(rh.titles.map { t ->
                JSONObject().apply {
                    put("filePath", t.filePath)
                    put("originalName", t.originalName)
                }
            }))
            root.put("readingHistory", obj)
        }

        payload.files?.let { files ->
            val obj = JSONObject()
            obj.put("files", JSONArray(files.map { f ->
                JSONObject().apply {
                    put("filePath", f.filePath)
                    put("status", f.status)
                    put("rating", f.rating)
                    put("colorLabel", f.colorLabel)
                    put("registeredAt", f.registeredAt)
                }
            }))
            root.put("ratingsLabels", obj)
        }

        payload.bookmarks?.let { bookmarks ->
            val obj = JSONObject()
            obj.put("bookmarks", JSONArray(bookmarks.map { b ->
                JSONObject().apply {
                    put("filePath", b.filePath)
                    put("page", b.page)
                    put("createdAt", b.createdAt)
                }
            }))
            root.put("bookmarks", obj)
        }

        payload.nasServers?.let { nas ->
            val obj = JSONObject()
            obj.put("servers", JSONArray(nas.servers.map { s ->
                JSONObject().apply {
                    put("id", s.id)
                    put("displayName", s.displayName)
                    put("host", s.host)
                    put("shareName", s.shareName)
                    put("username", s.username)
                }
            }))
            obj.put("remoteBookmarks", JSONArray(nas.remoteBookmarks.map { b ->
                JSONObject().apply {
                    put("id", b.id)
                    put("serverId", b.serverId)
                    put("nasPath", b.nasPath)
                    put("name", b.name)
                    put("isFolder", b.isFolder)
                }
            }))
            obj.put("passwordsEncrypted", nas.passwordsEncrypted)
            nas.nasPasswordsEncrypted?.let { enc ->
                obj.put("nasPasswordsEncrypted", JSONObject().apply {
                    put("cipherText", enc.cipherText)
                    put("salt", enc.salt)
                    put("iv", enc.iv)
                    put("iterations", enc.iterations)
                })
            }
            root.put("nasServers", obj)
        }

        return root.toString()
    }

    /**
     * JSON文字列を検証しつつ解析する。
     * 壊れたJSON・対応より新しいschemaVersion・必須項目の欠落は [BackupParseException] を投げる。
     */
    fun fromJson(jsonText: String): BackupPayload {
        val root = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw BackupParseException("バックアップファイルの形式が正しくありません")
        }

        if (!root.has("schemaVersion") || !root.has("appVersionName") ||
            !root.has("createdAt") || !root.has("items")
        ) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }

        try {
            return parseBody(root)
        } catch (e: BackupParseException) {
            throw e
        } catch (e: JSONException) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }
    }

    private fun parseBody(root: JSONObject): BackupPayload {
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion <= 0) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }
        if (schemaVersion > BackupPayload.CURRENT_SCHEMA_VERSION) {
            throw BackupParseException("このバックアップは新しいバージョンのアプリで作成されたため復元できません")
        }

        val appVersionName = root.optString("appVersionName", "")
        val createdAt = root.optLong("createdAt", -1L)
        if (createdAt < 0) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }

        val itemsArray = try {
            root.getJSONArray("items")
        } catch (e: JSONException) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }
        val items = (0 until itemsArray.length()).mapNotNull { i ->
            val key = itemsArray.optString(i, "")
            BackupItemType.entries.firstOrNull { it.jsonKey == key }
        }
        if (items.isEmpty()) {
            throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
        }

        var settingsApp: Map<String, Any>? = null
        var settingsSort: Map<String, Any>? = null
        if (BackupItemType.SETTINGS in items) {
            val settingsObj = root.optJSONObject("settings")
                ?: throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
            val appObj = settingsObj.optJSONObject("app") ?: JSONObject()
            settingsApp = jsonObjectToRawMap(appObj)
            val sortObj = settingsObj.optJSONObject("sort")
            if (sortObj != null) {
                val sortMap = mutableMapOf<String, Any>()
                sortObj.keys().forEach { k ->
                    val v = sortObj.get(k)
                    if (v is JSONArray) {
                        sortMap[k] = (0 until v.length()).map { v.optString(it, "") }.toSet()
                    } else {
                        sortMap[k] = v
                    }
                }
                settingsSort = sortMap
            }
        }

        var readingHistory: BackupReadingHistoryPayload? = null
        if (BackupItemType.READING_HISTORY in items) {
            val obj = root.optJSONObject("readingHistory")
                ?: throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
            val progressArray = obj.optJSONArray("progress") ?: JSONArray()
            val progress = (0 until progressArray.length()).map { i ->
                val p = progressArray.getJSONObject(i)
                BackupProgressRecord(
                    filePath = p.getString("filePath"),
                    currentPage = p.getInt("currentPage"),
                    totalPages = p.getInt("totalPages"),
                    lastReadAt = p.getLong("lastReadAt")
                )
            }
            val titlesArray = obj.optJSONArray("titles") ?: JSONArray()
            val titles = (0 until titlesArray.length()).map { i ->
                val t = titlesArray.getJSONObject(i)
                BackupTitleRecord(
                    filePath = t.getString("filePath"),
                    originalName = t.getString("originalName")
                )
            }
            readingHistory = BackupReadingHistoryPayload(progress, titles)
        }

        var files: List<BackupFileRecord>? = null
        if (BackupItemType.RATINGS_LABELS in items) {
            val obj = root.optJSONObject("ratingsLabels")
                ?: throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
            val filesArray = obj.optJSONArray("files") ?: JSONArray()
            files = (0 until filesArray.length()).map { i ->
                val f = filesArray.getJSONObject(i)
                BackupFileRecord(
                    filePath = f.getString("filePath"),
                    status = f.getInt("status"),
                    rating = f.getInt("rating"),
                    colorLabel = f.getInt("colorLabel"),
                    registeredAt = f.getLong("registeredAt")
                )
            }
        }

        var bookmarks: List<BackupBookmarkRecord>? = null
        if (BackupItemType.BOOKMARKS in items) {
            val obj = root.optJSONObject("bookmarks")
                ?: throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
            val bookmarksArray = obj.optJSONArray("bookmarks") ?: JSONArray()
            bookmarks = (0 until bookmarksArray.length()).map { i ->
                val b = bookmarksArray.getJSONObject(i)
                BackupBookmarkRecord(
                    filePath = b.getString("filePath"),
                    page = b.getInt("page"),
                    createdAt = b.getLong("createdAt")
                )
            }
        }

        var nasServers: BackupNasServersPayload? = null
        if (BackupItemType.NAS_SERVERS in items) {
            val obj = root.optJSONObject("nasServers")
                ?: throw BackupParseException("バックアップファイルに必要な情報が含まれていません")
            val serversArray = obj.optJSONArray("servers") ?: JSONArray()
            val servers = (0 until serversArray.length()).map { i ->
                val s = serversArray.getJSONObject(i)
                BackupNasServerRecord(
                    id = s.getString("id"),
                    displayName = s.getString("displayName"),
                    host = s.getString("host"),
                    shareName = s.optString("shareName", ""),
                    username = s.optString("username", "")
                )
            }
            val bookmarksArray = obj.optJSONArray("remoteBookmarks") ?: JSONArray()
            val remoteBookmarks = (0 until bookmarksArray.length()).map { i ->
                val b = bookmarksArray.getJSONObject(i)
                BackupRemoteBookmarkRecord(
                    id = b.getString("id"),
                    serverId = b.getString("serverId"),
                    nasPath = b.getString("nasPath"),
                    name = b.getString("name"),
                    isFolder = b.getBoolean("isFolder")
                )
            }
            val passwordsEncrypted = obj.optBoolean("passwordsEncrypted", false)
            val nasPasswordsEncrypted = obj.optJSONObject("nasPasswordsEncrypted")?.let { eObj ->
                EncryptedBlob(
                    cipherText = eObj.getString("cipherText"),
                    salt = eObj.getString("salt"),
                    iv = eObj.getString("iv"),
                    iterations = eObj.getInt("iterations")
                )
            }
            nasServers = BackupNasServersPayload(
                servers = servers,
                remoteBookmarks = remoteBookmarks,
                passwordsEncrypted = passwordsEncrypted,
                nasPasswordsEncrypted = nasPasswordsEncrypted
            )
        }

        return BackupPayload(
            schemaVersion = schemaVersion,
            appVersionName = appVersionName,
            createdAt = createdAt,
            items = items,
            settingsApp = settingsApp,
            settingsSort = settingsSort,
            readingHistory = readingHistory,
            files = files,
            bookmarks = bookmarks,
            nasServers = nasServers
        )
    }

    private fun jsonObjectToRawMap(obj: JSONObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        obj.keys().forEach { k -> result[k] = obj.get(k) }
        return result
    }
}
