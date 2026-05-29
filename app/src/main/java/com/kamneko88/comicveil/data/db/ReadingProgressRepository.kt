package com.kamneko88.comicveil.data.db

/**
 * 読書位置のRepository
 * ViewModelはDAOに直接触れず、必ずRepositoryを経由する
 */
class ReadingProgressRepository(private val dao: ReadingProgressDao) {

    /** 読書位置を取得。未登録なら null */
    suspend fun getProgress(filePath: String): ReadingProgress? {
        return dao.getProgress(filePath)
    }

    /** 読書位置を保存 */
    suspend fun saveProgress(filePath: String, currentPage: Int, totalPages: Int) {
        dao.saveProgress(
            ReadingProgress(
                filePath = filePath,
                currentPage = currentPage,
                totalPages = totalPages
            )
        )
    }

    /** 読書位置を削除（将来のキャッシュ消去機能で使用） */
    suspend fun deleteProgress(filePath: String) {
        dao.deleteProgress(filePath)
    }
}