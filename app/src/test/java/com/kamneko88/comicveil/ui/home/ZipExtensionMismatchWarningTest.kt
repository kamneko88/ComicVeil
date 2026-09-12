package com.kamneko88.comicveil.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * zipExtensionMismatchWarning()の回帰テスト。
 *
 * NASストリーミングでzip/cbzとして開こうとしたファイルの、拡張子と実体（先頭署名判定結果）の
 * 突き合わせロジックを検証する。実際のSMB通信は伴わない純粋なロジックのみのテスト。
 */
class ZipExtensionMismatchWarningTest {

    @Test
    fun `detected is zip returns no warning`() {
        assertNull(zipExtensionMismatchWarning("book.cbz", "cbz", "zip"))
    }

    @Test
    fun `detected is null (undetectable) returns no warning`() {
        // 先頭が読めない・シグネチャ不一致（スパースファイル等）の場合は誤検知を避けて警告しない
        assertNull(zipExtensionMismatchWarning("book.cbz", "cbz", null))
    }

    @Test
    fun `detected as rar returns a mismatch warning`() {
        val warning = zipExtensionMismatchWarning("book.cbz", "cbz", "rar")
        assertEquals(
            "「book.cbz」は拡張子と実際のファイル形式が一致していません" +
                "（拡張子: cbz、実際の形式: rar）。ファイル名の拡張子を正しいものに直してください。",
            warning
        )
    }

    @Test
    fun `detected as 7z returns a mismatch warning`() {
        val warning = zipExtensionMismatchWarning("book.zip", "zip", "7z")
        assertEquals(
            "「book.zip」は拡張子と実際のファイル形式が一致していません" +
                "（拡張子: zip、実際の形式: 7z）。ファイル名の拡張子を正しいものに直してください。",
            warning
        )
    }

    @Test
    fun `detected as pdf returns a mismatch warning`() {
        val warning = zipExtensionMismatchWarning("book.cbz", "cbz", "pdf")
        assertEquals(
            "「book.cbz」は拡張子と実際のファイル形式が一致していません" +
                "（拡張子: cbz、実際の形式: pdf）。ファイル名の拡張子を正しいものに直してください。",
            warning
        )
    }
}
