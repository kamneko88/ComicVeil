package com.kamneko88.comicveil.data

import android.util.Log
import com.github.junrar.Archive as JunrarArchive
import com.github.junrar.rarfile.FileHeader
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
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
     * @param password パスワード付きRARの場合に指定する（未指定ならnull）
     * @return 書き出したページ数
     */
    fun extractPages(
        file: File,
        targetEntries: List<ArchiveEntryInfo>,
        pageDir: File,
        maxPageBytes: Long,
        password: String? = null
    ): Int {
        val finalIndexByName = targetEntries.withIndex().associate { (i, info) -> info.name to i }
        var written = 0
        try {
            JunrarArchive(file, password).use { archive ->
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

    /**
     * RARがパスワードで保護されているか判定する。
     * バージョンに応じて検知方法を使い分ける（展開ライブラリの選択とは別枠）：
     * - RAR4：junrarでヘッダーを読み、FileHeader.isEncrypted（LHD_PASSWORDフラグ由来）を見る
     * - RAR5：junrarはRAR5ヘッダーを一切読めない（UnsupportedRarV5Exceptionを即座に投げる。
     *   junrar 7.5.5のArchive.java readHeaders()内、MarkHeaderのバージョン判定箇所で確認済み）ため、
     *   展開に使っているlibarchiveのreadHasEncryptedEntries()で代替する
     *
     * 判定できない場合はfalseを返す（誤って「暗号化あり」と過検知してユーザーの正常なファイルを
     * 開けなくすることを避けるため）。
     */
    fun isEncrypted(file: File): Boolean {
        return try {
            when (detectVersion(file)) {
                RarVersion.RAR4 -> junrarHeadersEncrypted(readRar4EncryptedFlags(file))
                else            -> libarchiveHasEncryptedEntries(readRar5EncryptedEntriesCode(file))
            }
        } catch (e: Exception) {
            logD("RAR暗号化チェック失敗: ${e.message}")
            false
        }
    }

    /** [isEncrypted]のRAR4判定ロジック（ピュア関数・テスト用に分離） */
    internal fun junrarHeadersEncrypted(flags: List<Boolean>): Boolean = flags.any { it }

    /** [isEncrypted]のRAR5判定ロジック（ピュア関数・テスト用に分離）。libarchiveのarchive_read_has_encrypted_entries()の戻り値を解釈する（1件以上あれば正の値） */
    internal fun libarchiveHasEncryptedEntries(code: Int): Boolean = code > 0

    /**
     * RAR5で日本語ファイル名を含む場合、libarchiveのUTF-16→UTF-8変換がAndroid上で失敗し、
     * ArchiveEntry.pathnameUtf8()が空文字列を返すことがある。この場合、名前が使えないため
     * エントリを丸ごと捨てるのではなく、「物理的な読み取り順のN番目＝Nページ目」とみなして
     * 位置だけから合成ファイル名を作り、代わりに使う（ZIPストリーミング展開の既存フォールバックと同じ考え方）。
     *
     * スキャン時・展開時・「表紙に設定」機能による再スキャン時のいずれでも、同じ物理インデックスに
     * 対して必ず同じ名前を返す必要があるため、[physicalIndex]以外の状態には一切依存しない。
     *
     * 命名規則（ArchiveScanner.isImage()を通過させるための制約）：
     * - `.jpg`で終わる（対応拡張子）
     * - `__`で始まらない
     * - `..`を含まない
     * - NaturalOrder.COMPARATORで物理順どおりにソートされるよう、インデックスを8桁ゼロ埋めする
     */
    fun rar5FallbackEntryName(physicalIndex: Int): String =
        "comicveil_rar5_fallback_%08d.jpg".format(physicalIndex)

    private fun readRar4EncryptedFlags(file: File): List<Boolean> {
        val flags = mutableListOf<Boolean>()
        JunrarArchive(file).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                flags.add(header.isEncrypted)
                header = archive.nextFileHeader()
            }
        }
        return flags
    }

    /** RAR5のヘッダーを1件だけ読み、libarchiveのarchive_read_has_encrypted_entries()相当の戻り値を取得する */
    private fun readRar5EncryptedEntriesCode(file: File): Int {
        var archive = 0L
        try {
            archive = Archive.readNew()
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 10240L)
            val entry = ArchiveEntry.new1()
            try {
                Archive.readNextHeader2(archive, entry)
            } catch (e: ArchiveException) {
                // パス名変換警告等でもヘッダー自体は読めているため無視して続行する
                logD("RAR5暗号化チェック: ヘッダー読み取り警告(code=${e.code}) ${e.message}")
            } finally {
                ArchiveEntry.free(entry)
            }
            return Archive.readHasEncryptedEntries(archive)
        } finally {
            if (archive != 0L) {
                runCatching { Archive.readClose(archive) }
                runCatching { Archive.readFree(archive) }
            }
        }
    }
}
