package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheEvictionTest {

    @Test
    fun `total within limit evicts nothing`() {
        val entries = listOf(
            CacheDirInfo("a", sizeBytes = 100L, lastModified = 1L),
            CacheDirInfo("b", sizeBytes = 100L, lastModified = 2L)
        )
        assertEquals(emptyList<String>(), selectDirsToEvict(entries, limitBytes = 500L))
    }

    @Test
    fun `over limit evicts only the excess`() {
        val entries = listOf(
            CacheDirInfo("old", sizeBytes = 100L, lastModified = 1L),
            CacheDirInfo("new", sizeBytes = 100L, lastModified = 2L)
        )
        // total=200, limit=150 -> removing "old"(100) brings total to 100 <= 150
        assertEquals(listOf("old"), selectDirsToEvict(entries, limitBytes = 150L))
    }

    @Test
    fun `oldest is evicted first and newest survives`() {
        val entries = listOf(
            CacheDirInfo("newest", sizeBytes = 100L, lastModified = 300L),
            CacheDirInfo("oldest", sizeBytes = 100L, lastModified = 100L),
            CacheDirInfo("middle", sizeBytes = 100L, lastModified = 200L)
        )
        // total=300, limit=250 -> removing "oldest"(100) brings total to 200 <= 250
        assertEquals(listOf("oldest"), selectDirsToEvict(entries, limitBytes = 250L))
    }

    @Test
    fun `zero limit means unlimited and evicts nothing`() {
        val entries = listOf(CacheDirInfo("a", sizeBytes = 1_000_000L, lastModified = 1L))
        assertEquals(emptyList<String>(), selectDirsToEvict(entries, limitBytes = 0L))
    }

    @Test
    fun `single directory alone over limit is evicted`() {
        val entries = listOf(CacheDirInfo("huge", sizeBytes = 1000L, lastModified = 1L))
        assertEquals(listOf("huge"), selectDirsToEvict(entries, limitBytes = 500L))
    }

    @Test
    fun `empty entries evicts nothing`() {
        assertEquals(emptyList<String>(), selectDirsToEvict(emptyList(), limitBytes = 100L))
    }
}
