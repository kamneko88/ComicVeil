package com.kamneko88.comicveil.data

import android.util.Log
import java.io.File

/**
 * ファイル先頭の署名（マジックバイト）から実際のアーカイブ形式を判定するユーティリティ。
 *
 * 拡張子だけで形式を判定すると、RARファイルに .cbz という名前が付いている等、
 * 拡張子と中身が食い違う自炊ファイルを正しく開けない。
 * 署名を読めない場合（NASストリーミングのスパースファイルで先頭が0x00の場合を含む）は
 * 必ず拡張子へフォールバックする。RAR4/RAR5の世代判定は行わない（[RarSupport.detectVersion]が担当）。
 */
object FormatDetector {

    private const val SIGNATURE_READ_BYTES = 16

    private val ZIP_SIG_LOCAL_FILE   = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_SIG_EMPTY        = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SIG_SPANNED      = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val RAR_SIG              = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) // RAR4/RAR5共通
    private val SEVEN_Z_SIG          = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val PDF_SIG              = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"

    private fun logD(msg: String) {
        if (com.kamneko88.comicveil.BuildConfig.DEBUG) Log.d("ComicVeil", msg)
    }

    /**
     * ファイル先頭の署名から「実効拡張子」を返す。
     * 判定できない場合は、引数のファイルの拡張子（小文字）をそのまま返す。
     */
    fun effectiveExtension(file: File): String {
        val fallback = file.extension.lowercase()

        val head = try {
            val buf = ByteArray(SIGNATURE_READ_BYTES)
            file.inputStream().use { input ->
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
        } catch (e: Exception) {
            return fallback
        }

        val detected = detectFromBytes(head) ?: return fallback
        if (detected != fallback) {
            logD("拡張子と中身が不一致: ${file.name} は実際には $detected")
        }
        return detected
    }

    /**
     * バイト列の先頭から形式を判定する純粋関数。
     * 判定できなければ null を返す。
     */
    internal fun detectFromBytes(head: ByteArray): String? {
        return when {
            startsWith(head, ZIP_SIG_LOCAL_FILE) -> "zip"
            startsWith(head, ZIP_SIG_EMPTY)      -> "zip"
            startsWith(head, ZIP_SIG_SPANNED)    -> "zip"
            startsWith(head, RAR_SIG)            -> "rar"
            startsWith(head, SEVEN_Z_SIG)        -> "7z"
            startsWith(head, PDF_SIG)            -> "pdf"
            else -> null
        }
    }

    private fun startsWith(head: ByteArray, signature: ByteArray): Boolean {
        if (head.size < signature.size) return false
        for (i in signature.indices) {
            if (head[i] != signature[i]) return false
        }
        return true
    }
}
