package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * RAR5＋日本語ファイル名でlibarchiveのUTF-16→UTF-8変換が失敗し、
 * ArchiveEntry.pathnameUtf8()が空文字を返す場合に使う、位置ベースの
 * 合成ファイル名生成ロジック（[RarSupport.rar5FallbackEntryName]）のテスト。
 */
class RarSupportFallbackNameTest {

    @Test
    fun `fallback name passes ArchiveScanner isImage check`() {
        assertTrue(ArchiveScanner.isImage(RarSupport.rar5FallbackEntryName(0)))
        assertTrue(ArchiveScanner.isImage(RarSupport.rar5FallbackEntryName(12345)))
    }

    @Test
    fun `fallback name does not start with double underscore and has no double dot`() {
        val name = RarSupport.rar5FallbackEntryName(3)
        assertTrue(!name.startsWith("__"))
        assertTrue(!name.contains(".."))
    }

    @Test
    fun `fallback names sort in physical order under NaturalOrder comparator`() {
        val inOrder = (0..20).map { RarSupport.rar5FallbackEntryName(it) }
        val shuffled = inOrder.shuffled(Random(42))
        val sorted = shuffled.sortedWith(NaturalOrder.COMPARATOR)
        assertEquals(inOrder, sorted)
    }

    @Test
    fun `fallback name is deterministic for the same physical index`() {
        assertEquals(RarSupport.rar5FallbackEntryName(7), RarSupport.rar5FallbackEntryName(7))
    }
}
