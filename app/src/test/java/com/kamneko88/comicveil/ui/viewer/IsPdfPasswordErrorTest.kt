package com.kamneko88.comicveil.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 課題3（パスワード付きPDF）の判定ロジックのテスト。
 *
 * 【なぜピュア関数のテストに留めるか】
 * android.graphics.pdf.PdfRendererはAndroidフレームワーク実装（ネイティブ依存）のクラスであり、
 * 実際にパスワード付きPDFを開いてSecurityExceptionが投げられることをJVMユニットテストで
 * 再現することはできない。そのため、判定ロジック本体（[isPdfPasswordError]）のみを
 * 切り出してテストする。実際のPdfRenderer連携は実機・エミュレータでの確認が必要（未確認）。
 */
class IsPdfPasswordErrorTest {

    @Test
    fun `security exception is treated as a password error`() {
        assertTrue(isPdfPasswordError(SecurityException("password required")))
    }

    @Test
    fun `other exceptions are not treated as a password error`() {
        assertFalse(isPdfPasswordError(IllegalStateException("corrupt pdf")))
    }
}
