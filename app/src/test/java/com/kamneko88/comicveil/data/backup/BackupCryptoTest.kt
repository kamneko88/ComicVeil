package com.kamneko88.comicveil.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `encrypt then decrypt with correct passphrase returns original plaintext`() {
        val plaintext = "{\"server-1\":\"hunter2\"}".toByteArray(Charsets.UTF_8)
        val passphrase = "correct-horse".toCharArray()

        val blob = BackupCrypto.encrypt(plaintext, passphrase)
        val decrypted = BackupCrypto.decrypt(blob, "correct-horse".toCharArray())

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test(expected = BackupPassphraseException::class)
    fun `decrypt with wrong passphrase throws BackupPassphraseException`() {
        val plaintext = "secret-data".toByteArray(Charsets.UTF_8)
        val blob = BackupCrypto.encrypt(plaintext, "correct-horse".toCharArray())

        BackupCrypto.decrypt(blob, "wrong-passphrase".toCharArray())
    }

    @Test
    fun `same plaintext and passphrase produce different ciphertext each time`() {
        val plaintext = "same-plaintext".toByteArray(Charsets.UTF_8)
        val passphrase = "same-passphrase".toCharArray()

        val blob1 = BackupCrypto.encrypt(plaintext, passphrase)
        val blob2 = BackupCrypto.encrypt(plaintext, "same-passphrase".toCharArray())

        assertNotEquals(blob1.cipherText, blob2.cipherText)
        assertNotEquals(blob1.salt, blob2.salt)
        assertNotEquals(blob1.iv, blob2.iv)
    }

    @Test
    fun `iterations value is carried in the blob`() {
        val blob = BackupCrypto.encrypt("x".toByteArray(), "passphrase123".toCharArray())
        assertEquals(BackupCrypto.DEFAULT_ITERATIONS, blob.iterations)
    }

    @Test
    fun `tampered ciphertext fails GCM tag verification`() {
        val blob = BackupCrypto.encrypt("payload".toByteArray(), "passphrase123".toCharArray())
        val tampered = blob.copy(cipherText = blob.cipherText.dropLast(4) + "abcd")

        var failed = false
        try {
            BackupCrypto.decrypt(tampered, "passphrase123".toCharArray())
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(tampered.cipherText == blob.cipherText)
    }
}
