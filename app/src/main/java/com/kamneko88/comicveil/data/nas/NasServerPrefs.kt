package com.kamneko88.comicveil.data.nas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * NASサーバー設定をSharedPreferencesに保存・読み込みする
 * Roomは使わず（DB migration不要のため）SharedPreferences + JSONで管理
 */
class NasServerPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("nas_servers", Context.MODE_PRIVATE)

    fun getServers(): List<NasServer> {
        val json = prefs.getString("servers", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                NasServer(
                    id          = obj.getString("id"),
                    displayName = obj.getString("displayName"),
                    host        = obj.getString("host"),
                    shareName   = obj.getString("shareName"),
                    username    = obj.getString("username"),
                    password    = obj.getString("password")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServer(server: NasServer) {
        val list = getServers().toMutableList()
        val index = list.indexOfFirst { it.id == server.id }
        if (index >= 0) list[index] = server else list.add(server)
        persist(list)
    }

    fun deleteServer(id: String) {
        persist(getServers().filter { it.id != id })
    }

    private fun persist(servers: List<NasServer>) {
        val array = JSONArray()
        servers.forEach { s ->
            array.put(JSONObject().apply {
                put("id",          s.id)
                put("displayName", s.displayName)
                put("host",        s.host)
                put("shareName",   s.shareName)
                put("username",    s.username)
                put("password",    s.password)
            })
        }
        prefs.edit().putString("servers", array.toString()).apply()
    }
}