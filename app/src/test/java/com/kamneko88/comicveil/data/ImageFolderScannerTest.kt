package com.kamneko88.comicveil.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageFolderScannerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("image_folder_scanner_test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun touch(name: String): File = File(root, name).apply { writeText("x") }

    @Test
    fun `images are sorted in natural order regardless of creation order`() {
        touch("page10.jpg")
        touch("page2.jpg")
        touch("page1.jpg")

        val result = ImageFolderScanner.scan(root).map { it.name }
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), result)
    }

    @Test
    fun `only jpg jpeg png webp extensions are included regardless of case`() {
        touch("a.JPG")
        touch("b.jpeg")
        touch("c.PNG")
        touch("d.WebP")
        touch("e.gif")
        touch("f.txt")
        touch("g.zip")

        val result = ImageFolderScanner.scan(root).map { it.name }.toSet()
        assertEquals(setOf("a.JPG", "b.jpeg", "c.PNG", "d.WebP"), result)
    }

    @Test
    fun `files in subfolders are not included`() {
        touch("top.jpg")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "nested.jpg").writeText("x")

        val result = ImageFolderScanner.scan(root).map { it.name }
        assertEquals(listOf("top.jpg"), result)
    }

    @Test
    fun `empty folder returns empty list`() {
        assertEquals(emptyList<File>(), ImageFolderScanner.scan(root))
    }

    @Test
    fun `folder containing only subfolders returns empty list`() {
        File(root, "sub1").mkdirs()
        File(root, "sub2").mkdirs()

        assertEquals(emptyList<File>(), ImageFolderScanner.scan(root))
    }
}
