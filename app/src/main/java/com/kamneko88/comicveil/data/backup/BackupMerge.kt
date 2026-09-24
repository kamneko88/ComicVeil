package com.kamneko88.comicveil.data.backup

import com.kamneko88.comicveil.data.db.Bookmark
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.RemoteBookmark

/**
 * 復元時のマージ判定ロジック（Android非依存の純粋関数）。
 * DB本体への書き込みは呼び出し側（BackupRestorer）が行う。ここでは
 * 「何を書き込むべきか」だけを決める。
 */
object BackupMerge {

    /**
     * しおりの重複判定：filePath+page の組が既存に無いものだけを残す。
     * バックアップ内で同じ組み合わせが複数あっても1件にまとめる。
     */
    fun filterNewBookmarks(
        existing: List<Bookmark>,
        incoming: List<BackupBookmarkRecord>
    ): List<Bookmark> {
        val existingKeys = existing.map { it.filePath to it.page }.toMutableSet()
        val result = mutableListOf<Bookmark>()
        for (b in incoming) {
            val key = b.filePath to b.page
            if (existingKeys.add(key)) {
                result.add(Bookmark(filePath = b.filePath, page = b.page, createdAt = b.createdAt))
            }
        }
        return result
    }

    data class NasServerMergeResult(
        val toUpsert: List<NasServer>,
        /** パスワードが空のまま追加された新規サーバーのID（復元完了案内で注意喚起する） */
        val newServerIdsWithEmptyPassword: List<String>
    )

    /**
     * NASサーバー設定のマージ：idが既存にあれば情報を更新（パスワードは明示的に渡されない限り保持）、
     * 無ければ新規追加する（パスワードは渡されなければ空文字）。
     *
     * @param passwords サーバーID → 復元後に設定すべきパスワード（含まれないIDはパスワードを変更しない）
     */
    fun mergeNasServers(
        existing: List<NasServer>,
        incoming: List<BackupNasServerRecord>,
        passwords: Map<String, String>
    ): NasServerMergeResult {
        val existingById = existing.associateBy { it.id }
        val newEmptyPasswordIds = mutableListOf<String>()
        val result = incoming.map { rec ->
            val current = existingById[rec.id]
            val password = when {
                passwords.containsKey(rec.id) -> passwords.getValue(rec.id)
                current != null -> current.password
                else -> {
                    newEmptyPasswordIds.add(rec.id)
                    ""
                }
            }
            NasServer(
                id = rec.id,
                displayName = rec.displayName,
                host = rec.host,
                shareName = rec.shareName,
                username = rec.username,
                password = password
            )
        }
        return NasServerMergeResult(result, newEmptyPasswordIds)
    }

    /**
     * リモートブックマーク（HOME登録ショートカット）の重複判定：
     * serverId+nasPath の組が既存に無いものだけを残す（idはバックアップのものを保つ）。
     */
    fun filterNewRemoteBookmarks(
        existing: List<RemoteBookmark>,
        incoming: List<BackupRemoteBookmarkRecord>
    ): List<RemoteBookmark> {
        val existingKeys = existing.map { it.serverId to it.nasPath }.toMutableSet()
        val result = mutableListOf<RemoteBookmark>()
        for (b in incoming) {
            val key = b.serverId to b.nasPath
            if (existingKeys.add(key)) {
                result.add(
                    RemoteBookmark(
                        id = b.id,
                        serverId = b.serverId,
                        nasPath = b.nasPath,
                        name = b.name,
                        isFolder = b.isFolder
                    )
                )
            }
        }
        return result
    }
}
