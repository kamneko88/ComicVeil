package com.kamneko88.comicveil.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップ内のNASパスワードを保護するための暗号化ユーティリティ。
 * PBKDF2WithHmacSHA256で鍵導出し、AES-256-GCMで暗号化する。
 * Android非依存（javax.crypto・java.security のみ）なのでJVM単体テストで検証できる。
 */
object BackupCrypto {

    /** PBKDF2の反復回数。将来変更してもソルトと一緒に古いバックアップ側の値を使うため復元できる。 */
    const val DEFAULT_ITERATIONS = 310_000

    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_LENGTH_BITS = 256

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
        val keyBytes = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    /** 平文をパスフレーズで暗号化する。呼び出し毎にソルト・IVが新しく生成される。 */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): EncryptedBlob {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plaintext)

        return EncryptedBlob(
            cipherText = Base64.getEncoder().encodeToString(cipherText),
            salt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(iv),
            iterations = iterations
        )
    }

    /**
     * 暗号化データをパスフレーズで復号する。
     * パスフレーズが誤っている場合は [BackupPassphraseException] を投げる。
     */
    fun decrypt(blob: EncryptedBlob, passphrase: CharArray): ByteArray {
        val salt = Base64.getDecoder().decode(blob.salt)
        val iv = Base64.getDecoder().decode(blob.iv)
        val cipherText = Base64.getDecoder().decode(blob.cipherText)
        val key = deriveKey(passphrase, salt, blob.iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            cipher.doFinal(cipherText)
        } catch (e: AEADBadTagException) {
            throw BackupPassphraseException("パスフレーズが違います")
        }
    }
}
