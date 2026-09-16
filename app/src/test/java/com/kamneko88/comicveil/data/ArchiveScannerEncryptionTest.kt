package com.kamneko88.comicveil.data

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 課題1（ZIP）・課題2（7z）の暗号化検知テスト。
 * zip4j・commons-compressはどちらも純Javaで動くため、実際にパスワード付きアーカイブを
 * 生成してArchiveScannerに読ませる形で検証する（モックではなく実物の暗号化フラグを確認する）。
 */
class ArchiveScannerEncryptionTest {

    private fun tempDir(): File = File.createTempFile("archive_scan_test", "").apply {
        delete()
        mkdirs()
    }

    @Test
    fun `password protected zip is detected as encrypted`() {
        val dir = tempDir()
        try {
            val source = File(dir, "1.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
            val zip = File(dir, "protected.zip")
            Zip4jFile(zip, "secret".toCharArray()).addFile(
                source,
                ZipParameters().apply {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.ZIP_STANDARD
                }
            )

            val result = ArchiveScanner.scan(zip)

            assertTrue(result.encrypted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `plain zip is not detected as encrypted`() {
        val dir = tempDir()
        try {
            val source = File(dir, "1.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
            val zip = File(dir, "plain.zip")
            Zip4jFile(zip).addFile(source)

            val result = ArchiveScanner.scan(zip)

            assertFalse(result.encrypted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `password protected 7z is detected via is7zEncrypted`() {
        val dir = tempDir()
        try {
            val source = File(dir, "1.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
            val sevenZ = File(dir, "protected.7z")
            SevenZOutputFile(sevenZ, "secret".toCharArray()).use { out ->
                out.putArchiveEntry(out.createArchiveEntry(source, "1.jpg"))
                out.write(source.readBytes())
                out.closeArchiveEntry()
            }

            assertTrue(ArchiveScanner.is7zEncrypted(sevenZ))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `plain 7z is not detected via is7zEncrypted`() {
        val dir = tempDir()
        try {
            val source = File(dir, "1.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
            val sevenZ = File(dir, "plain.7z")
            SevenZOutputFile(sevenZ).use { out ->
                out.putArchiveEntry(out.createArchiveEntry(source, "1.jpg"))
                out.write(source.readBytes())
                out.closeArchiveEntry()
            }

            assertFalse(ArchiveScanner.is7zEncrypted(sevenZ))
        } finally {
            dir.deleteRecursively()
        }
    }
}
