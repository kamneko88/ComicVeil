package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameCollisionTest {

    @Test
    fun `no collision returns name as-is`() {
        assertEquals("foo.zip", resolveUniqueFileName("foo.zip", emptySet()))
    }

    @Test
    fun `single collision appends _2`() {
        assertEquals("foo_2.zip", resolveUniqueFileName("foo.zip", setOf("foo.zip")))
    }

    @Test
    fun `advances to first free number`() {
        val existing = setOf("foo.zip", "foo_2.zip", "foo_3.zip")
        assertEquals("foo_4.zip", resolveUniqueFileName("foo.zip", existing))
    }

    @Test
    fun `unrelated existing names do not affect result`() {
        val existing = setOf("bar.zip", "baz.rar")
        assertEquals("foo.zip", resolveUniqueFileName("foo.zip", existing))
    }

    @Test
    fun `name without extension still gets suffixed`() {
        assertEquals("foo_2", resolveUniqueFileName("foo", setOf("foo")))
    }

    @Test
    fun `hidden file with leading dot is treated as no extension`() {
        // lastIndexOf('.') == 0 なので拡張子扱いしない
        assertEquals(".gitignore_2", resolveUniqueFileName(".gitignore", setOf(".gitignore")))
    }

    @Test
    fun `multiple dots only splits at the last one`() {
        assertEquals("archive.tar_2.gz", resolveUniqueFileName("archive.tar.gz", setOf("archive.tar.gz")))
    }
}
