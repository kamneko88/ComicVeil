package com.kamneko88.comicveil.data.nas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * HOMEに登録するリモートのブックマーク（ショートカット）。
 * リモートのフォルダ、または本（ファイル）を指す。
 *
 * サーバーそのものの情報は持たず serverId で参照する（表示時にNasServerへ解決する）。
 * 参照先サーバーが削除されていたら、そのブックマークは表示しない。
 */
data class RemoteBookmark(
    val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val nasPath: String,
    val name: String,
    val isFolder: Boolean
)

/**
 * リモートブックマークをSharedPreferences + JSONで保存・読み込みする。
 * NasServerPrefsと同じ方式（Roomは使わずマイグレーション不要）。
 */
class RemoteBookmarkPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("remote_bookmarks", Context.MODE_PRIVATE)

    fun getBookmarks(): List<RemoteBookmark> {
        val json = prefs.getString("bookmarks", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RemoteBookmark(
                    id       = obj.getString("id"),
                    serverId = obj.getString("serverId"),
                    nasPath  = obj.getString("nasPath"),
                    name     = obj.getString("name"),
                    isFolder = obj.getBoolean("isFolder")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 同じサーバー・同じパスのブックマークがあるか */
    fun exists(serverId: String, nasPath: String): Boolean =
        getBookmarks().any { it.serverId == serverId && it.nasPath == nasPath }

    fun add(bookmark: RemoteBookmark) {
        if (exists(bookmark.serverId, bookmark.nasPath)) return
        persist(getBookmarks() + bookmark)
    }

    /** サーバー・パスが一致するブックマークを削除する */
    fun removeByTarget(serverId: String, nasPath: String) {
        persist(getBookmarks().filterNot { it.serverId == serverId && it.nasPath == nasPath })
    }

    private fun persist(bookmarks: List<RemoteBookmark>) {
        val array = JSONArray()
        bookmarks.forEach { b ->
            array.put(JSONObject().apply {
                put("id",       b.id)
                put("serverId", b.serverId)
                put("nasPath",  b.nasPath)
                put("name",     b.name)
                put("isFolder", b.isFolder)
            })
        }
        prefs.edit().putString("bookmarks", array.toString()).apply()
    }
}
