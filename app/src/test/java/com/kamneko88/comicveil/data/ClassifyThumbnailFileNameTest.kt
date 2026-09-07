package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifyThumbnailFileNameTest {

    @Test
    fun `nas prefixed jpg is classified as NAS`() {
        assertEquals(ThumbnailOrigin.NAS, classifyThumbnailFileName("nas_123.jpg"))
    }

    @Test
    fun `saf prefixed jpg is classified as SAF`() {
        assertEquals(ThumbnailOrigin.SAF, classifyThumbnailFileName("saf_-456.jpg"))
    }

    @Test
    fun `unprefixed jpg is classified as LOCAL`() {
        assertEquals(ThumbnailOrigin.LOCAL, classifyThumbnailFileName("1437845141.jpg"))
    }

    @Test
    fun `leading minus sign is still classified as LOCAL`() {
        assertEquals(ThumbnailOrigin.LOCAL, classifyThumbnailFileName("-1420566014.jpg"))
    }

    @Test
    fun `meta file without prefix is not classified`() {
        assertNull(classifyThumbnailFileName("1437845141.meta"))
    }

    @Test
    fun `meta file with nas prefix is not classified`() {
        assertNull(classifyThumbnailFileName("nas_123.meta"))
    }
}
