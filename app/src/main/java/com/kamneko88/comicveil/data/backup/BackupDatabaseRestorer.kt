package com.kamneko88.comicveil.data.backup

import androidx.room.withTransaction
import com.kamneko88.comicveil.data.db.ComicFile
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import com.kamneko88.comicveil.data.db.FileTitle
import com.kamneko88.comicveil.data.db.ReadingProgress

data class RestoreDbCounts(
    val readingProgress: Int = 0,
    val fileTitles: Int = 0,
    val files: Int = 0,
    val bookmarks: Int = 0
)

/**
 * バックアップのDB側（Room）復元を1つのトランザクションで行う。
 * 途中で失敗した場合、withTransactionにより変更はロールバックされる。
 */
class BackupDatabaseRestorer(private val db: ComicVeilDatabase) {

    suspend fun restore(
        readingProgress: List<BackupProgressRecord>?,
        fileTitles: List<BackupTitleRecord>?,
        files: List<BackupFileRecord>?,
        bookmarks: List<BackupBookmarkRecord>?
    ): RestoreDbCounts {
        return db.withTransaction {
            var progressCount = 0
            var titleCount = 0
            var fileCount = 0
            var bookmarkCount = 0

            if (readingProgress != null) {
                val entities = readingProgress.map {
                    ReadingProgress(
                        filePath = it.filePath,
                        currentPage = it.currentPage,
                        totalPages = it.totalPages,
                        lastReadAt = it.lastReadAt
                    )
                }
                db.readingProgressDao().saveAll(entities)
                progressCount = entities.size
            }

            if (fileTitles != null) {
                val entities = fileTitles.map {
                    FileTitle(filePath = it.filePath, originalName = it.originalName)
                }
                db.fileTitleDao().saveAll(entities)
                titleCount = entities.size
            }

            if (files != null) {
                val entities = files.map {
                    ComicFile(
                        filePath = it.filePath,
                        status = it.status,
                        rating = it.rating,
                        colorLabel = it.colorLabel,
                        registeredAt = it.registeredAt
                    )
                }
                db.comicFileDao().upsertAll(entities)
                fileCount = entities.size
            }

            if (bookmarks != null) {
                val existing = db.bookmarkDao().getAll()
                val newOnes = BackupMerge.filterNewBookmarks(existing, bookmarks)
                if (newOnes.isNotEmpty()) {
                    db.bookmarkDao().insertAll(newOnes)
                }
                bookmarkCount = newOnes.size
            }

            RestoreDbCounts(
                readingProgress = progressCount,
                fileTitles = titleCount,
                files = fileCount,
                bookmarks = bookmarkCount
            )
        }
    }
}
