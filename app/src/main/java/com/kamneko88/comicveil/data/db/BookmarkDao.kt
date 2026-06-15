package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookmarkDao {

    /** ファイルのブックマーク一覧を登録順で取得 */
    @Query("SELECT * FROM bookmarks WHERE filePath = :filePath ORDER BY createdAt ASC")
    suspend fun getBookmarks(filePath: String): List<Bookmark>

    /** 指定ページがブックマーク済みか確認 */
    @Query("SELECT * FROM bookmarks WHERE filePath = :filePath AND page = :page LIMIT 1")
    suspend fun getBookmark(filePath: String, page: Int): Bookmark?

    /** ブックマーク登録（同じfilePath+pageは上書き） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    /** 指定ページのブックマーク削除 */
    @Query("DELETE FROM bookmarks WHERE filePath = :filePath AND page = :page")
    suspend fun deleteBookmark(filePath: String, page: Int)

    /** ファイルのブックマークを全削除 */
    @Query("DELETE FROM bookmarks WHERE filePath = :filePath")
    suspend fun deleteAllBookmarks(filePath: String)
}
