package com.kamneko88.comicveil.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本棚モードのフォルダタイルの表紙選び（[ShelfFolderCovers]）の回帰テスト。
 */
class ShelfFolderCoversTest {

    private fun comic(name: String): FileItem =
        FileItem(file = File("/root/$name"), type = FileItemType.COMIC_FILE, name = name)

    private fun folder(name: String): FileItem =
        FileItem(file = File("/root/$name"), type = FileItemType.FOLDER, name = name)

    private fun imageFile(name: String): FileItem =
        FileItem(file = File("/root/$name"), type = FileItemType.IMAGE_FILE, name = name)

    @Test
    fun `candidateComics ignores non-comic items and sorts in natural order`() {
        val items = listOf(
            comic("第10巻.zip"),
            folder("サブフォルダ"),
            comic("第2巻.zip"),
            imageFile("cover.jpg"),
            comic("第1巻.zip")
        )

        val result = ShelfFolderCovers.candidateComics(items).map { it.name }

        assertEquals(listOf("第1巻.zip", "第2巻.zip", "第10巻.zip"), result)
    }

    @Test
    fun `candidateComics caps scanned candidates at maxFiles`() {
        val items = (1..20).map { comic("第${it}巻.zip") }

        val result = ShelfFolderCovers.candidateComics(items, maxFiles = 12)

        assertEquals(12, result.size)
        assertEquals("第1巻.zip", result.first().name)
        assertEquals("第12巻.zip", result.last().name)
    }

    @Test
    fun `collectCovers stops once four covers are collected`() = runBlocking {
        val items = (1..10).map { comic("第${it}巻.zip") }
        var fetchCount = 0

        val covers = ShelfFolderCovers.collectCovers(items) { candidate ->
            fetchCount++
            ThumbnailOutcome.Ready(File("/thumb/${candidate.name}.jpg"))
        }

        assertEquals(4, covers.size)
        assertEquals(4, fetchCount)
        assertEquals(
            listOf("第1巻.zip.jpg", "第2巻.zip.jpg", "第3巻.zip.jpg", "第4巻.zip.jpg"),
            covers.map { it.name }
        )
    }

    @Test
    fun `collectCovers skips candidates that fail and keeps looking`() = runBlocking {
        val items = listOf(
            comic("第1巻.zip"),  // Encrypted
            comic("第2巻.zip"),  // Unavailable
            comic("第3巻.zip"),  // Ready
            comic("第4巻.zip"),  // Ready
            comic("第5巻.zip")   // Ready
        )

        val covers = ShelfFolderCovers.collectCovers(items) { candidate ->
            when (candidate.name) {
                "第1巻.zip" -> ThumbnailOutcome.Encrypted
                "第2巻.zip" -> ThumbnailOutcome.Unavailable
                else        -> ThumbnailOutcome.Ready(File("/thumb/${candidate.name}.jpg"))
            }
        }

        assertEquals(
            listOf("第3巻.zip.jpg", "第4巻.zip.jpg", "第5巻.zip.jpg"),
            covers.map { it.name }
        )
    }

    @Test
    fun `collectCovers returns empty list when nothing is ready`() = runBlocking {
        val items = listOf(comic("第1巻.zip"), comic("第2巻.zip"))

        val covers = ShelfFolderCovers.collectCovers(items) { ThumbnailOutcome.Unavailable }

        assertTrue(covers.isEmpty())
    }

    @Test
    fun `collectCovers only fetches up to maxFiles candidates even if none are ready`() = runBlocking {
        val items = (1..20).map { comic("第${it}巻.zip") }
        var fetchCount = 0

        val covers = ShelfFolderCovers.collectCovers(items) {
            fetchCount++
            ThumbnailOutcome.Unavailable
        }

        assertTrue(covers.isEmpty())
        assertEquals(ShelfFolderCovers.MAX_FILES_TO_SCAN, fetchCount)
    }
}
