package com.kamneko88.comicveil.data.db

class BookmarkRepository(private val dao: BookmarkDao) {

    suspend fun getBookmarks(filePath: String): List<Bookmark> =
        dao.getBookmarks(filePath)

    suspend fun isBookmarked(filePath: String, page: Int): Boolean =
        dao.getBookmark(filePath, page) != null

    suspend fun toggleBookmark(filePath: String, page: Int) {
        if (isBookmarked(filePath, page)) {
            dao.deleteBookmark(filePath, page)
        } else {
            dao.insertBookmark(Bookmark(filePath = filePath, page = page))
        }
    }

    suspend fun deleteAllBookmarks(filePath: String) =
        dao.deleteAllBookmarks(filePath)
}
