package com.kamneko88.comicveil.data

import android.util.Log
import com.github.junrar.Archive
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
    val volumes: List<String>?           // 複数巻フォルダが検出された場合、その一覧（自然順）。単巻ならnull
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
        val names = try {
            when (file.extension.lowercase()) {
                "zip", "cbz" -> scanZip(file)
                "rar", "cbr" -> scanRar(file)
                "7z"         -> scan7z(file)
                else         -> emptyList()
            }
        } catch (e: Exception) {
            Log.e("ComicVeil", "ArchiveScanner.scan失敗: ${e.message}", e)
            emptyList()
        }
        return buildResult(names)
    }

    /**
     * ZIPスキャン。
     * まず高速なランダムアクセス方式（ZipFile）を試し、
     * 一部の非標準なZIPで失敗する場合は、より緩い逐次読み込み方式にフォールバックする。
     */
    private fun scanZip(file: File): List<String> {
        return try {
            val names = mutableListOf<String>()
            ZipFile.builder().setFile(file).get().use { zip ->
                zip.entries.iterator().forEach { entry ->
                    if (!entry.isDirectory && isImage(entry.name)) names.add(entry.name)
                }
            }
            if (names.isEmpty()) throw IllegalStateException("エントリが0件")
            names
        } catch (e: Exception) {
            Log.d("ComicVeil", "ZipFileランダムアクセス失敗、逐次読み込みにフォールバック: ${e.message}")
            scanZipSequential(file)
        }
    }

    /**
     * ZIPの緩い逐次スキャン（末尾の管理情報が壊れている/非標準なZIPでも読める）。
     * nextZipEntry()だけに頼ると、非標準なZIPでは正しく次のエントリへ進めないことがあるため、
     * 各エントリのデータを実際に読んで消費してから次へ進む（展開処理と同じ考え方）。
     */
    private fun scanZipSequential(file: File): List<String> {
        val names = mutableListOf<String>()
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
            if (names.isNotEmpty()) break
        }
        return names
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

    private fun scanRar(file: File): List<String> {
        val names = mutableListOf<String>()
        Archive(file).use { archive ->
            archive.fileHeaders.forEach { header ->
                if (!header.isDirectory && isImage(header.fileName)) names.add(header.fileName)
            }
        }
        return names
    }

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

    private fun isImage(name: String): Boolean {
        val fileName = name.substringAfterLast("/")
        if (fileName.startsWith(".") || name.startsWith("__") || name.contains("..")) return false
        return name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
    }

    private fun buildResult(rawNames: List<String>): ArchiveScanResult {
        val sortedNames = rawNames.sortedWith(compareBy(NaturalOrder.COMPARATOR) { it })
        val entries = sortedNames.map { name ->
            val hasFolder = name.contains("/")
            val volume = if (hasFolder) name.substringBefore("/") else null
            ArchiveEntryInfo(name, volume)
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
