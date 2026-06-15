package com.kamneko88.comicveil.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * filesテーブルのRepository
 * ViewModelからDBアクセスを隠蔽する
 */
class ComicFileRepository(private val dao: ComicFileDao) {

    /** 読書状態を取得。未登録なら UNREAD を返す */
    suspend fun getStatus(filePath: String): ReadStatus = withContext(Dispatchers.IO) {
        val record = dao.getComicFile(filePath)
        ReadStatus.fromValue(record?.status ?: ComicFile.STATUS_UNREAD)
    }

    /**
     * 読書状態を更新する
     * レコードが存在しない場合は新規作成してから更新
     */
    suspend fun updateStatus(filePath: String, status: ReadStatus) = withContext(Dispatchers.IO) {
        val existing = dao.getComicFile(filePath)
        if (existing == null) {
            dao.upsertComicFile(ComicFile(filePath = filePath, status = status.value))
        } else {
            dao.updateStatus(filePath, status.value)
        }
    }

    /** ファイル情報を取得（将来のタグ・評価取得に拡張予定） */
    suspend fun getComicFile(filePath: String): ComicFile? = withContext(Dispatchers.IO) {
        dao.getComicFile(filePath)
    }

    /** レーティングを更新（レコードがなければ新規作成） */
    suspend fun updateRating(filePath: String, rating: Int) = withContext(Dispatchers.IO) {
        val existing = dao.getComicFile(filePath)
        if (existing == null) {
            dao.upsertComicFile(ComicFile(filePath = filePath, rating = rating))
        } else {
            dao.updateRating(filePath, rating)
        }
    }

    /** カラーラベルを更新（レコードがなければ新規作成） */
    suspend fun updateColorLabel(filePath: String, colorLabel: Int) = withContext(Dispatchers.IO) {
        val existing = dao.getComicFile(filePath)
        if (existing == null) {
            dao.upsertComicFile(ComicFile(filePath = filePath, colorLabel = colorLabel))
        } else {
            dao.updateColorLabel(filePath, colorLabel)
        }
    }

    /** レコードを削除（ファイル削除時） */
    suspend fun delete(filePath: String) = withContext(Dispatchers.IO) {
        dao.deleteComicFile(filePath)
    }
}