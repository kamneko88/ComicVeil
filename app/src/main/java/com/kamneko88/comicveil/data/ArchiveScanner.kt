package com.kamneko88.comicveil.data

import android.util.Log
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/** スキャン時にエントリを読み飛ばす際の上限（この上限に達したら異常とみなし中断する） */
private const val SCAN_SKIP_MAX_BYTES = 200L * 1024 * 1024

/** アーカイブ内の1エントリ（画像ファイル）の情報 */
data class ArchiveEntryInfo(
    val name: String,       // アーカイブ内の元のパス（例: "01巻/001.jpg"）
    val volumeName: String? // 先頭フォルダ名（巻フォルダ）。ルート直下ならnull
)

/** アーカイブのスキャン結果 */
data class ArchiveScanResult(
    val entries: List<ArchiveEntryInfo>, // 自然順ソート済みの画像エントリ一覧
    val volumes: List<String>?,          // 複数巻フォルダが検出された場合、その一覧（自然順）。単巻ならnull
    val zipCharset: String? = null       // ZIP展開時に使うべき文字コード名（スキャンで採用したもの）。RAR/7zはnull
)

/**
 * アーカイブ（ZIP/RAR/7z）の中身を、画像を展開せずに高速に一覧するユーティリティ。
 * 「複数の巻フォルダが入っているか」の判定と、ページの表示順決定に使う。
 *
 * どの内部処理で失敗しても例外を投げず、空の結果を返す（呼び出し元をクラッシュさせない）。
 */
object ArchiveScanner {

    /** 拡張子からアーカイブ形式を判定してスキャンする。失敗しても空リストを返す（クラッシュしない） */
    fun scan(file: File): ArchiveScanResult {
        return try {
            when (file.extension.lowercase()) {
                "zip", "cbz" -> {
                    val (names, charsetName) = scanZip(file)
                    buildResult(names).copy(zipCharset = charsetName)
                }
                "rar", "cbr" -> buildResult(scanRar(file))
                "7z"         -> buildResult(scan7z(file))
                else         -> buildResult(emptyList())
            }
        } catch (e: Exception) {
            Log.e("ComicVeil", "ArchiveScanner.scan失敗: ${e.message}", e)
            buildResult(emptyList())
        }
    }

    /**
     * ZIPスキャン。
     * まず高速なランダムアクセス方式（ZipFile）をUTF-8とShift-JISの両方で試し、文字化けしていない方を採用する。
     * どちらも失敗する場合は、より緩い逐次読み込み方式にフォールバックする。
     * （libarchiveではAndroid上で日本語パス名のUTF-8取得が安定しなかったため、ZIPはCommons Compressに戻している）
     */
    private fun scanZip(file: File): Pair<List<String>, String?> {
        for (csName in listOf("UTF-8", "Shift_JIS")) {
            try {
                val names = mutableListOf<String>()
                ZipFile.builder().setFile(file).setCharset(charset(csName)).get().use { zip ->
                    zip.entries.iterator().forEach { entry ->
                        if (!entry.isDirectory && isImage(entry.name)) names.add(entry.name)
                    }
                }
                if (names.isNotEmpty() && !looksGarbled(names)) return names to csName
                if (names.isNotEmpty() && csName == "Shift_JIS") {
                    // 最後の候補。これ以上試す手段がないのでこのまま採用する
                    return names to csName
                }
            } catch (e: Exception) {
                Log.d("ComicVeil", "ZipFileランダムアクセス失敗（$csName）: ${e.message}")
            }
        }
        Log.d("ComicVeil", "ZipFileランダムアクセス失敗、逐次読み込みにフォールバック")
        return scanZipSequential(file)
    }

    /** デコード結果が文字化けしていそうか判定する（置換文字や？が目立つ場合） */
    private fun looksGarbled(names: List<String>): Boolean =
        names.any { it.contains('\uFFFD') || it.count { c -> c == '?' } >= 2 }

    /**
     * ZIPの緩い逐次スキャン（末尾の管理情報が壊れている/非標準なZIPでも読める）。
     * nextZipEntry()だけに頼ると、非標準なZIPでは正しく次のエントリへ進めないことがあるため、
     * 各エントリのデータを実際に読んで消費してから次へ進む（展開処理と同じ考え方）。
     */
    private fun scanZipSequential(file: File): Pair<List<String>, String?> {
        val names = mutableListOf<String>()
        var usedCharset: String? = null
        for (cs in listOf("UTF-8", "Shift_JIS")) {
            names.clear()
            try {
                ZipArchiveInputStream(file.inputStream().buffered(), cs, false, true).use { zis ->
                    var entry = zis.nextZipEntry
                    while (entry != null) {
                        val isDir = entry.isDirectory
                        val name  = entry.name
                        // データを読み切って消費する（読み飛ばしだけだと次のエントリ位置がズレることがある）
                        val consumed = consumeEntry(zis)
                        if (!consumed) break
                        if (!isDir && isImage(name)) names.add(name)
                        entry = zis.nextZipEntry
                    }
                }
            } catch (e: Exception) {
                Log.d("ComicVeil", "ZIP逐次読み込み失敗（$cs）: ${e.message}")
            }
            if (names.isNotEmpty()) { usedCharset = cs; break }
        }
        return names to usedCharset
    }

    /** ストリームからエントリのデータを最後まで読んで消費する（上限を超えたら異常とみなしfalse） */
    private fun consumeEntry(input: java.io.InputStream): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) return true
            total += read
            if (total > SCAN_SKIP_MAX_BYTES) return false
        }
    }

    /** RAR：libarchiveで逐次読み込みして画像エントリ名を収集する（RAR5対応） */
    private fun scanRar(file: File): List<String> {
        return scanWithLibarchive(file) { archive ->
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
        }
    }

    /** 7z：Commons Compressで逐次読み込みして画像エントリ名を収集する */
    private fun scan7z(file: File): List<String> {
        val names = mutableListOf<String>()
        SevenZFile.builder().setFile(file).get().use { sevenZFile ->
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val name = entry.name ?: ""
                if (!entry.isDirectory && isImage(name)) names.add(name)
                entry = sevenZFile.nextEntry
            }
        }
        return names
    }

    /** libarchiveで逐次読み込みして画像エントリ名を収集する共通処理（ZIP・RAR・7z共用） */
    private fun scanWithLibarchive(file: File, configureFormat: (archive: Long) -> Unit): List<String> {
        val names = mutableListOf<String>()
        var archive = 0L
        try {
            archive = Archive.readNew()
            configureFormat(archive)
            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 10240L)

            var index = 0
            while (true) {
                val entry = ArchiveEntry.new1()
                var isEof = false
                var isFatal = false
                try {
                    val ret = Archive.readNextHeader2(archive, entry)
                    if (ret.toInt() == Archive.ERRNO_EOF) isEof = true
                } catch (e: ArchiveException) {
                    when {
                        e.code == Archive.ERRNO_WARN -> {
                            // パス名のロケール変換警告など。pathnameUtf8は引き続き取得できるため処理を続行する
                            Log.w("ComicVeil", "scan警告(index=$index): ${e.message}")
                        }
                        e.message?.contains("eof", ignoreCase = true) == true -> {
                            // 本来EOFで終了すべきところを例外経由で通知してくるケース（並の終了として扱う）
                            isEof = true
                        }
                        isPathnameConversionWarning(e) -> {
                            // パス名の文字コード変換警告（UTF-16→端末ロケールへの変換失敗・code=84/EILSEQ）。
                            // Androidはロケール変換が弱いためこの警告が出るが、ヘッダの読み取り自体は
                            // 成功しており、pathnameUtf8() ならUTF-8で正しく名前を取得できる。
                            // 致命的ではないので中断せず続行する（UTF-16BE名のRARが読めない問題の修正）。
                            Log.w("ComicVeil", "scanパス名変換警告(index=$index, code=${e.code}) 続行します")
                        }
                        else -> {
                            Log.e("ComicVeil", "スキャン中断(index=$index, code=${e.code}): ${e.message}")
                            isFatal = true
                        }
                    }
                }
                if (isEof || isFatal) {
                    ArchiveEntry.free(entry)
                    break
                }
                val name  = ArchiveEntry.pathnameUtf8(entry) ?: ""
                val isDir = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                Log.d("ComicVeil", "scan[$index] $name dir=$isDir")
                if (!isDir && isImage(name)) names.add(name)
                // 次のreadNextHeader2呼び出し時に未消費データは自動でスキップされるため、
                // 明示的なreadDataSkipは不要（ディレクトリエントリで呼ぶとZIP/7zで不安定化する事例があったため削除）
                ArchiveEntry.free(entry)
                index++
            }
        } catch (e: Exception) {
            Log.e("ComicVeil", "scanWithLibarchive失敗: ${e::class.simpleName}: ${e.message}", e)
        } finally {
            if (archive != 0L) {
                runCatching { Archive.readClose(archive) }
                runCatching { Archive.readFree(archive) }
            }
        }
        Log.d("ComicVeil", "scanWithLibarchive完了: 画像${names.size}件検出")
        return names
    }

    private fun isImage(name: String): Boolean {
        val fileName = name.substringAfterLast("/")
        if (fileName.startsWith(".") || name.startsWith("__") || name.contains("..")) return false
        return name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
    }

    /**
     * libarchiveのパス名変換警告か（UTF-16→端末ロケールへの変換失敗）。
     * Androidはロケール変換が弱いため、UTF-16で名前を持つRAR/7zでこの警告が出る。
     * ヘッダの読み取り自体は成功しており、pathnameUtf8()でUTF-8名を取得できるため、
     * これは致命的エラーではなく続行してよい。
     * code=84 は EILSEQ（不正なバイト列）。
     */
    private fun isPathnameConversionWarning(e: ArchiveException): Boolean {
        if (e.code == 84) return true
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("pathname") && (msg.contains("convert") || msg.contains("locale"))
    }

    private fun buildResult(rawNames: List<String>): ArchiveScanResult {
        val sortedNames = rawNames.sortedWith(compareBy(NaturalOrder.COMPARATOR) { it })

        // 全エントリで共通する先頭フォルダ（単なる外側のラッパーフォルダ）を必要なだけ剥がしてから巻名を判定する。
        // 例：「作品名.zip/作品名フォルダ/第01巻/001.jpg」のように、
        // 全ページ共通のラッパーフォルダがあると、それ自体が先頭フォルダとして検出されて
        // 「巻が1つしかない」と誤判定されるのを防ぐ。
        var effectiveNames = sortedNames
        while (effectiveNames.isNotEmpty()) {
            val firstSegments = effectiveNames.map { it.substringBefore("/", "") }
            if (firstSegments.any { it.isEmpty() }) break        // ルート直下に画像がある：これ以上剥がせない
            if (firstSegments.distinct().size != 1) break         // 先頭フォルダが割れている：ここが巻フォルダの階層
            effectiveNames = effectiveNames.map { it.substringAfter("/") }
        }

        val entries = sortedNames.indices.map { i ->
            val effective = effectiveNames[i]
            val hasFolder = effective.contains("/")
            val volume = if (hasFolder) effective.substringBefore("/") else null
            ArchiveEntryInfo(sortedNames[i], volume)
        }

        // 複数の異なる巻フォルダが存在し、かつルート直下に画像が無い場合のみ「複数巻」とみなす
        val volumeNames = entries.mapNotNull { it.volumeName }.distinct()
        val hasRootLevelImages = entries.any { it.volumeName == null }
        val volumes = if (volumeNames.size >= 2 && !hasRootLevelImages) {
            volumeNames.sortedWith(NaturalOrder.COMPARATOR)
        } else {
            null
        }

        return ArchiveScanResult(entries, volumes)
    }
}

/** フォルダ名・ファイル名中の数字を数値として比較する自然順ソート */
object NaturalOrder {
    val COMPARATOR = Comparator<String> { a, b -> compare(a.lowercase(), b.lowercase()) }

    private fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i; while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j; while (j2 < b.length && b[j2].isDigit()) j2++
                val numA = a.substring(i, i2).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(j, j2).trimStart('0').ifEmpty { "0" }
                val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = i2; j = j2
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
