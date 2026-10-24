package com.kamneko88.comicveil.data

import com.kamneko88.comicveil.data.db.NasFolderListingComic
import com.kamneko88.comicveil.data.nas.NasServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NASフォルダのタイルの表紙選び（保存する一覧の作り方・保存済み一覧からの表紙集め）の回帰テスト。
 */
class ShelfFolderCoversNasTest {

    private val server = NasServer(displayName = "nas", host = "host", shareName = "share", username = "u", password = "p")

    private fun nasItem(name: String, isDirectory: Boolean = false): FileItem =
        FileItem.fromNas(name, "dir/$name", isDirectory, 0L, server)

    private fun saved(vararg names: String): List<NasFolderListingComic> =
        names.mapIndexed { i, n -> NasFolderListingComic("dir", i, "dir/$n", n) }

    // ─── 保存する一覧の作り方 ───

    @Test
    fun `listingComics keeps only comics in natural order`() {
        val items = listOf(
            nasItem("第10巻.zip"), nasItem("sub", isDirectory = true),
            nasItem("第2巻.zip"), nasItem("memo.txt"), nasItem("第1巻.zip")
        )

        val result = ShelfFolderCovers.listingComics("dir", items)

        assertEquals(listOf("第1巻.zip", "第2巻.zip", "第10巻.zip"), result.map { it.name })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
        assertEquals(listOf("dir/第1巻.zip", "dir/第2巻.zip", "dir/第10巻.zip"), result.map { it.comicNasPath })
        assertTrue(result.all { it.folderPath == "dir" })
    }

    @Test
    fun `listingComics truncates to the first twelve`() {
        val items = (1..20).map { nasItem("第${it}巻.zip") }

        val result = ShelfFolderCovers.listingComics("dir", items)

        assertEquals(ShelfFolderCovers.MAX_FILES_TO_SCAN, result.size)
        assertEquals("第1巻.zip", result.first().name)
        assertEquals("第12巻.zip", result.last().name)
    }

    @Test
    fun `listingComics is empty for a folder without comics`() {
        val items = listOf(nasItem("sub", isDirectory = true), nasItem("memo.txt"))

        assertTrue(ShelfFolderCovers.listingComics("dir", items).isEmpty())
    }

    // ─── 保存済み一覧からの表紙集め ───

    @Test
    fun `collectNasCovers skips unavailable and takes up to four`() = runBlocking {
        val listing = saved("第1巻.zip", "第2巻.zip", "第3巻.zip", "第4巻.zip", "第5巻.zip", "第6巻.zip", "第7巻.zip")
        val noCover = setOf("第1巻.zip", "第3巻.zip")

        val covers = ShelfFolderCovers.collectNasCovers(listing, server) { candidate ->
            if (candidate.name in noCover) ThumbnailOutcome.Unavailable
            else ThumbnailOutcome.Ready(File("/thumb/${candidate.name}.jpg"))
        }

        assertEquals(
            listOf("第2巻.zip.jpg", "第4巻.zip.jpg", "第5巻.zip.jpg", "第6巻.zip.jpg"),
            covers.map { it.name }
        )
    }

    @Test
    fun `collectNasCovers follows saved position order`() = runBlocking {
        val listing = saved("a.zip", "b.zip", "c.zip").reversed()

        val covers = ShelfFolderCovers.collectNasCovers(listing, server) {
            ThumbnailOutcome.Ready(File("/thumb/${it.name}.jpg"))
        }

        assertEquals(listOf("a.zip.jpg", "b.zip.jpg", "c.zip.jpg"), covers.map { it.name })
    }

    @Test
    fun `collectNasCovers returns empty when nothing is obtainable`() = runBlocking {
        val covers = ShelfFolderCovers.collectNasCovers(saved("a.zip", "b.zip"), server) { ThumbnailOutcome.Unavailable }

        assertTrue(covers.isEmpty())
    }

    @Test
    fun `collectNasCovers returns empty without fetching when folder is not saved`() = runBlocking {
        var fetchCount = 0

        val notOpened = ShelfFolderCovers.collectNasCovers(null, server) { fetchCount++; ThumbnailOutcome.Unavailable }
        val noComics = ShelfFolderCovers.collectNasCovers(emptyList(), server) { fetchCount++; ThumbnailOutcome.Unavailable }

        assertTrue(notOpened.isEmpty())
        assertTrue(noComics.isEmpty())
        assertEquals(0, fetchCount)
    }
}
