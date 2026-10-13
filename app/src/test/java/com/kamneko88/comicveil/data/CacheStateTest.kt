package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheStateTest {

    @Test
    fun `length equal to expected size is fully cached`() {
        assertEquals(true, isFullyCached(cachedLength = 100L, expectedSize = 100L))
    }

    @Test
    fun `length greater than expected size is fully cached`() {
        assertEquals(true, isFullyCached(cachedLength = 150L, expectedSize = 100L))
    }

    @Test
    fun `length less than expected size is not fully cached`() {
        assertEquals(false, isFullyCached(cachedLength = 50L, expectedSize = 100L))
    }

    @Test
    fun `unknown expected size with nonzero length is fully cached`() {
        assertEquals(true, isFullyCached(cachedLength = 1L, expectedSize = 0L))
        assertEquals(true, isFullyCached(cachedLength = 1L, expectedSize = -1L))
    }

    @Test
    fun `zero length is never fully cached`() {
        assertEquals(false, isFullyCached(cachedLength = 0L, expectedSize = 0L))
        assertEquals(false, isFullyCached(cachedLength = 0L, expectedSize = 100L))
    }

    @Test
    fun `nas image is cached when folder complete and file fully cached`() {
        assertEquals(
            true,
            isNasImageCachedInFolder(folderComplete = true, cachedLength = 100L, expectedSize = 100L)
        )
    }

    @Test
    fun `nas image is not cached when folder is not complete`() {
        assertEquals(
            false,
            isNasImageCachedInFolder(folderComplete = false, cachedLength = 100L, expectedSize = 100L)
        )
    }

    @Test
    fun `nas image is not cached when file length is short of expected size`() {
        assertEquals(
            false,
            isNasImageCachedInFolder(folderComplete = true, cachedLength = 50L, expectedSize = 100L)
        )
    }

    @Test
    fun `nas image with unknown expected size is cached when folder complete and length nonzero`() {
        assertEquals(
            true,
            isNasImageCachedInFolder(folderComplete = true, cachedLength = 1L, expectedSize = 0L)
        )
        assertEquals(
            true,
            isNasImageCachedInFolder(folderComplete = true, cachedLength = 1L, expectedSize = -1L)
        )
    }
}
