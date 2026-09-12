package com.kamneko88.comicveil.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isZipExtensionMismatch()の回帰テスト。
 *
 * NASストリーミングでzip/cbzとして開こうとしたファイルの、拡張子と実体（先頭署名判定結果）の
 * 突き合わせロジックを検証する。実際のSMB通信は伴わない純粋なロジックのみのテスト。
 */
class IsZipExtensionMismatchTest {

    @Test
    fun `detected is zip is not a mismatch`() {
        assertFalse(isZipExtensionMismatch("zip"))
    }

    @Test
    fun `detected is null (undetectable) is not a mismatch`() {
        // 先頭が読めない・シグネチャ不一致（スパースファイル等）の場合は誤検知を避けて不一致扱いしない
        assertFalse(isZipExtensionMismatch(null))
    }

    @Test
    fun `detected as rar is a mismatch`() {
        assertTrue(isZipExtensionMismatch("rar"))
    }

    @Test
    fun `detected as 7z is a mismatch`() {
        assertTrue(isZipExtensionMismatch("7z"))
    }

    @Test
    fun `detected as pdf is a mismatch`() {
        assertTrue(isZipExtensionMismatch("pdf"))
    }
}
