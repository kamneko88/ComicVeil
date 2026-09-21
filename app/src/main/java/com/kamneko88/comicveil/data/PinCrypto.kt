package com.kamneko88.comicveil.data

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * アプリロックのPINを平文保存しないためのソルト付きハッシュ化ユーティリティ。
 * SHA-256一回がけ（端末ローカル保存・端末外への流出を想定しない入口ロック用途のため、
 * PBKDF2等の反復ハッシュまでは行わない簡易実装）。
 */
object PinCrypto {

    /** 16バイトのランダムソルトを16進文字列で生成する */
    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** PINとソルト（16進文字列）からハッシュ値（16進文字列）を計算する */
    fun hash(pin: String, saltHex: String): String {
        val salt = hexToBytes(saltHex)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashed.joinToString("") { "%02x".format(it) }
    }

    /** 入力されたPINが、保存済みのソルト・ハッシュと一致するか検証する */
    fun verify(pin: String, saltHex: String, expectedHashHex: String): Boolean {
        return hash(pin, saltHex) == expectedHashHex
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
