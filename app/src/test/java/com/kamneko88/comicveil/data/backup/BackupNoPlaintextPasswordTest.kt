package com.kamneko88.comicveil.data.backup

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NASのパスワードを含めたバックアップJSONに、平文パスワードが絶対に出力されないことを確認する。
 */
class BackupNoPlaintextPasswordTest {

    @Test
    fun `serialized backup JSON never contains the plaintext NAS password`() {
        val plainPassword = "sUpEr-secret-test-password-42"
        val passwordsJson = JSONObject().apply { put("server-1", plainPassword) }
        val encrypted = BackupCrypto.encrypt(
            passwordsJson.toString().toByteArray(Charsets.UTF_8),
            "correct-horse-battery-staple".toCharArray()
        )

        val payload = BackupPayload(
            schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
            appVersionName = "1.17.0",
            createdAt = 1L,
            items = listOf(BackupItemType.NAS_SERVERS),
            nasServers = BackupNasServersPayload(
                servers = listOf(
                    BackupNasServerRecord(
                        id = "server-1",
                        displayName = "自宅NAS",
                        host = "192.168.1.10",
                        shareName = "comics",
                        username = "reader"
                    )
                ),
                remoteBookmarks = emptyList(),
                passwordsEncrypted = true,
                nasPasswordsEncrypted = encrypted
            )
        )

        val json = BackupJsonCodec.toJson(payload)

        assertFalse("平文パスワードがJSONに含まれてはいけない", json.contains(plainPassword))
        // 暗号文（Base64）は含まれてよい
        assertTrue(json.contains(encrypted.cipherText))
    }
}
