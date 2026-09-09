package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatDetectorTest {

    @Test
    fun `zip signature is detected`() {
        val head = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals("zip", FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `rar4 signature is detected`() {
        val head = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        assertEquals("rar", FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `rar5 signature is detected`() {
        val head = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        assertEquals("rar", FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `7z signature is detected`() {
        val head = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        assertEquals("7z", FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `pdf signature is detected`() {
        val head = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        assertEquals("pdf", FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `unknown bytes return null`() {
        val head = ByteArray(8) { 0x00 }
        assertNull(FormatDetector.detectFromBytes(head))
    }

    @Test
    fun `empty array returns null`() {
        assertNull(FormatDetector.detectFromBytes(ByteArray(0)))
    }
}
