package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailRepositoryTest {

    @Test
    fun `large image is downsampled to smallest power of two above target`() {
        assertEquals(4, calculateInSampleSize(1600, 2300, 240, 340))
    }

    @Test
    fun `exact target size needs no downsampling`() {
        assertEquals(1, calculateInSampleSize(240, 340, 240, 340))
    }

    @Test
    fun `image smaller than target needs no downsampling`() {
        assertEquals(1, calculateInSampleSize(100, 150, 240, 340))
    }

    @Test
    fun `decode failure dimensions fall back to no downsampling`() {
        assertEquals(1, calculateInSampleSize(0, 0, 240, 340))
    }
}
