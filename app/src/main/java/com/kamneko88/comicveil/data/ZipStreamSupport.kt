package com.kamneko88.comicveil.data

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * ZIPのストリーミング再生（ダウンロードしながら読む）を支える仕組み。
 *
 * 【なぜ「目次の先読み」が必要か】
 * ZIPは「目次」（中央ディレクトリ）がファイルの**末尾**に置かれている。
 * 先頭から順にダウンロードすると、目次が届くのは最後。それでは総ページ数が分からず、
 * ページ移動バーも作れない。
 * そこで、ダウンロードを始める前に**末尾だけ**を読んで目次を手に入れる。
 *
 * 【中身の実現方法】
 * 実サイズと同じ「空っぽのファイル」を作り、末尾の位置にだけ取得したバイト列を書き込む。
 * こうすると、通常のZIPライブラリが末尾の目次を正しく読める（中身のデータは未取得でも、
 * 目次を読むだけなら問題ない）。ファイルは中身が空の領域を持つ「スパースファイル」なので
 * 実際のディスク使用量はごくわずかで済む。
 */
object ZipStreamSupport {

    /** ストリーミング情報を書き出すサイドカーファイルの拡張子 */
    private const val SIDECAR_SUFFIX = ".stream"

    private fun logD(message: String) {
        if (com.kamneko88.comicveil.BuildConfig.DEBUG) Log.d("ComicVeil", message)
    }

    /**
     * 末尾のバイト列からZIPの目次を読み取る。
     *
     * @param workDir  作業用ディレクトリ（キャッシュ）
     * @param fileSize ファイル全体のサイズ
     * @param tail     ファイル末尾のバイト列
     * @return 走査結果。目次が壊れている・巻フォルダ構成などで扱えない場合は null
     */
    fun probeFromTail(workDir: File, fileSize: Long, tail: ByteArray): ArchiveScanResult? {
        workDir.mkdirs()
        val probeFile = File(workDir, "probe_${System.nanoTime()}.zip")
        return try {
            RandomAccessFile(probeFile, "rw").use { raf ->
                raf.setLength(fileSize)
                raf.seek(fileSize - tail.size)
                raf.write(tail)
            }
            val scan = ArchiveScanner.scan(probeFile)
            if (scan.entries.isEmpty()) {
                logD("ストリーミング判定: 目次からページを取得できませんでした")
                null
            } else {
                logD("ストリーミング判定: 目次から${scan.entries.size}ページを検出 (charset=${scan.zipCharset})")
                scan
            }
        } catch (e: Exception) {
            // 中央ディレクトリが壊れている等。従来どおり全ダウンロードしてから開く。
            logD("ストリーミング判定: 目次を読めませんでした（${e.message}）")
            null
        } finally {
            runCatching { probeFile.delete() }
        }
    }

    /** ストリーミング情報を記録するサイドカーファイルのパス */
    fun sidecarFor(destFile: File): File = File(destFile.absolutePath + SIDECAR_SUFFIX)

    /**
     * ストリーミング情報（文字コード・全体サイズ・ページのエントリ名）を保存する。
     * ビューワー側はこれを読んで、未完成のZIPからでもページを順に取り出せる。
     */
    fun writeSidecar(destFile: File, scan: ArchiveScanResult, expectedSize: Long) {
        val sidecar = sidecarFor(destFile)
        runCatching {
            sidecar.parentFile?.mkdirs()
            sidecar.bufferedWriter().use { writer ->
                writer.write(scan.zipCharset ?: "UTF-8")
                writer.newLine()
                writer.write(expectedSize.toString())
                writer.newLine()
                scan.entries.forEach { entry ->
                    writer.write(entry.name)
                    writer.newLine()
                }
            }
        }
    }

    data class StreamInfo(
        val charset: String,
        val expectedSize: Long,
        val entryNames: List<String>
    )

    /** ストリーミング情報を読み込む（無ければ null＝通常の開き方をする） */
    fun readSidecar(destFile: File): StreamInfo? {
        val sidecar = sidecarFor(destFile)
        if (!sidecar.exists()) return null
        return runCatching {
            val lines = sidecar.readLines()
            if (lines.size < 3) return null
            StreamInfo(
                charset      = lines[0],
                expectedSize = lines[1].toLong(),
                entryNames   = lines.drop(2).filter { it.isNotEmpty() }
            )
        }.getOrNull()
    }

    fun deleteSidecar(destFile: File) {
        runCatching { sidecarFor(destFile).delete() }
    }
}

/**
 * ダウンロード中のファイルを、書き込まれるそばから読み進めるための入力ストリーム。
 *
 * まだ書き込まれていない位置まで読み進めたら、その場で少し待って再挑戦する。
 * ダウンロードが終わっていれば、そこで終端（-1）を返す。
 *
 * これにより「先頭から順に届いたページだけ展開する」ことができる。
 *
 * @param file             ダウンロード先のファイル（書き込まれながら大きくなる）
 * @param isDownloading    まだダウンロード中かどうかを返す関数
 * @param expectedSize     最終的なファイルサイズ
 */
class GrowingFileInputStream(
    private val file: File,
    private val isDownloading: () -> Boolean,
    private val expectedSize: Long
) : InputStream() {

    private val raf = RandomAccessFile(file, "r")
    private var position = 0L

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n == -1) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val available = file.length() - position

            if (available > 0) {
                val toRead = minOf(len.toLong(), available).toInt()
                raf.seek(position)
                val read = raf.read(b, off, toRead)
                if (read > 0) position += read
                return read
            }

            // まだ届いていない
            if (position >= expectedSize) return -1          // 最後まで読み切った
            if (!isDownloading()) {
                // ダウンロードが止まっている（完了 or 中断）
                return if (file.length() > position) continue else -1
            }
            Thread.sleep(WAIT_MS)
        }
    }

    override fun available(): Int =
        (file.length() - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        private const val WAIT_MS = 120L
    }
}
