package com.kamneko88.comicveil.data

import android.util.Log
import com.github.junrar.Archive as JunrarArchive
import com.github.junrar.rarfile.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File

/** RARのフォーマット世代 */
enum class RarVersion { RAR4, RAR5, UNKNOWN }

/**
 * RARの読み取り補助。
 *
 * 【なぜ2つのライブラリを使い分けるか】
 * - RAR4：junrar（Java製）で読む。junrarはファイル名を自前でデコードするため、
 *   日本語フォルダ名も正しく取得できる。
 *   （libarchiveはAndroidのロケール制約で、UTF-16の日本語名を取得しようとすると
 *    code=84/EILSEQ で失敗し、pathnameUtf8()も空文字になる）
 * - RAR5：junrarは非対応（UnsupportedRarV5Exception）。libarchive側で扱う。
 *   ただしRAR5に日本語名がある場合はlibarchiveでも名前を取得できない（既知の制約）。
 */
object RarSupport {

    private fun logD(msg: String) {
        if (com.kamneko88.comicveil.BuildConfig.DEBUG) Log.d("ComicVeil", msg)
    }

    /** RARのバージョンをファイル先頭のシグネチャで判定する */
    fun detectVersion(file: File): RarVersion {
        return try {
            val sig = ByteArray(8)
            file.inputStream().use { input ->
                var off = 0
                while (off < sig.size) {
                    val n = input.read(sig, off, sig.size - off)
                    if (n < 0) break
                    off += n
                }
                if (off < 7) return RarVersion.UNKNOWN
            }
            // "Rar!" 0x1A 0x07 で始まるのがRARの共通シグネチャ
            val head = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
            for (i in head.indices) if (sig[i] != head[i]) return RarVersion.UNKNOWN
            when (sig[6].toInt()) {
                0x00 -> RarVersion.RAR4   // Rar!\x1A\x07\x00
                0x01 -> RarVersion.RAR5   // Rar!\x1A\x07\x01\x00
                else -> RarVersion.UNKNOWN
            }
        } catch (e: Exception) {
            RarVersion.UNKNOWN
        }
    }

    /** junrarのファイル名を取得する（Unicode名を優先し、区切りを / に統一する） */
    private fun headerName(header: FileHeader): String {
        val raw = try {
            if (header.isUnicode) header.fileNameW else header.fileNameString
        } catch (e: Exception) {
            runCatching { header.fileNameString }.getOrNull()
        }
        return raw?.replace('\\', '/') ?: ""
    }

    /** RAR4をjunrarでスキャンして、全ファイルのパス名を集める（区切りは / 、日本語名対応） */
    fun scanNames(file: File): List<String> {
        val names = mutableListOf<String>()
        try {
            JunrarArchive(file).use { archive ->
                var header = archive.nextFileHeader()
                while (header != null) {
                    if (!header.isDirectory) {
                        val name = headerName(header)
                        if (name.isNotEmpty()) names.add(name)
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            Log.e("ComicVeil", "junrarスキャン失敗: ${e::class.simpleName}: ${e.message}", e)
        }
        logD("junrarスキャン完了: ${names.size}件")
        return names
    }

    /**
     * RAR4をjunrarで展開する。
     * targetEntriesで指定されたページを、それぞれ最終的なページ番号の位置（%05d.jpg）へ書き出す。
     * アーカイブに現れる順に処理するため、ソリッド書庫でも正しく展開できる。
     *
     * @return 書き出したページ数
     */
    fun extractPages(
        file: File,
        targetEntries: List<ArchiveEntryInfo>,
        pageDir: File,
        maxPageBytes: Long
    ): Int {
        val finalIndexByName = targetEntries.withIndex().associate { (i, info) -> info.name to i }
        var written = 0
        try {
            JunrarArchive(file).use { archive ->
                var header = archive.nextFileHeader()
                while (header != null) {
                    if (!header.isDirectory) {
                        val name     = headerName(header)
                        val finalIdx = finalIndexByName[name]
                        if (finalIdx != null) {
                            val out = ByteArrayOutputStream()
                            archive.extractFile(header, out)
                            val bytes = out.toByteArray()
                            when {
                                bytes.isEmpty() -> { /* 空エントリはスキップ */ }
                                bytes.size > maxPageBytes ->
                                    Log.e("ComicVeil", "RARページが上限を超えたためスキップ: $name (${bytes.size} bytes)")
                                else -> {
                                    File(pageDir, "%05d.jpg".format(finalIdx)).writeBytes(bytes)
                                    written++
                                }
                            }
                        }
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            Log.e("ComicVeil", "junrar展開失敗: ${e::class.simpleName}: ${e.message}", e)
        }
        logD("junrar展開完了: ${written}ページ / 対象${targetEntries.size}件")
        return written
    }
}
