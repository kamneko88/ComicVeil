package com.kamneko88.comicveil.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.github.junrar.Archive
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.NasStreamCache
import com.kamneko88.comicveil.data.nas.SmbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * サムネイル取得の結果。
 *
 * 暗号化されたアーカイブ・PDFはバックグラウンド生成中にパスワードを聞けないため、
 * 復号を試みず[Encrypted]として呼び出し元（HomeScreen）に伝える（鍵アイコン表示用）。
 */
sealed class ThumbnailOutcome {
    data class Ready(val file: File) : ThumbnailOutcome()
    data object Encrypted : ThumbnailOutcome()
    data object Unavailable : ThumbnailOutcome()
}

private fun File?.toThumbnailOutcome(): ThumbnailOutcome =
    this?.let { ThumbnailOutcome.Ready(it) } ?: ThumbnailOutcome.Unavailable

class ThumbnailRepository(private val cacheDir: File, private val context: Context? = null) {

    private val smbRepository = SmbRepository()

    /**
     * あつのりさんが手動で選んだ表紙の保存先。
     *
     * 【なぜcacheDir配下（サムネイルキャッシュ）に置かないか】
     * 設定画面の「サムネイルキャッシュを削除」で自動生成のサムネイルは消えてよいが、
     * カスタム表紙は意図して選んだデータであり、キャッシュではないため消えてはいけない。
     * NasStreamCacheと同じ「システムのキャッシュ領域を使わない」パターンに合わせ、
     * getExternalFilesDir(null)配下の専用ディレクトリに保存する。
     */
    private val customCoversDir: File =
        File(context?.getExternalFilesDir(null) ?: cacheDir, "custom_covers")

    init {
        cacheDir.mkdirs()
        customCoversDir.mkdirs()
    }

    /** カスタム表紙が設定済みか */
    fun hasCustomCover(item: FileItem): Boolean = customCoverFile(item).exists()

    /** カスタム表紙の保存先ファイル（存在確認・削除用に公開） */
    fun customCoverFile(item: FileItem): File =
        File(customCoversDir, "cover_${item.canonicalStatusKey().hashCode()}.jpg")

    /** 表紙として選ばれた画像を、既存サムネイルと同じサイズ・形式で保存する */
    fun saveCustomCover(item: FileItem, bitmap: Bitmap): Boolean {
        return try {
            val scaled = createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT)
            FileOutputStream(customCoverFile(item)).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            true
        } catch (e: Exception) {
            Log.w("ComicVeil", "saveCustomCover失敗: ${e::class.simpleName}: ${e.message}", e)
            false
        }
    }

    /**
     * サムネイルを取得する。キャッシュがあれば即返し、なければ生成してから返す。
     * カスタム表紙が設定済みならそれを最優先で返す（生成処理は一切通らない）。
     * NASファイルは STRキャッシュ → 部分取得 の順で生成する
     * SAFファイルは 先頭部分取得（ZIPのみ）で生成する
     */
    suspend fun getOrGenerateThumbnail(fileItem: FileItem): ThumbnailOutcome =
        withContext(Dispatchers.IO) {
            // 非圧縮の画像ファイル単体（ローカルのみ）も表紙候補にする。NAS・SAFはスコープ外
            val isLocalImageFile = fileItem.type == FileItemType.IMAGE_FILE && fileItem.file != null
            if (!fileItem.isComic && !isLocalImageFile) return@withContext ThumbnailOutcome.Unavailable

            if (hasCustomCover(fileItem)) return@withContext ThumbnailOutcome.Ready(customCoverFile(fileItem))

            if (fileItem.isNas) {
                return@withContext getOrGenerateNasThumbnail(fileItem)
            }

            if (fileItem.isSaf) {
                return@withContext getOrGenerateSafThumbnail(fileItem)
            }

            val file = fileItem.file ?: return@withContext ThumbnailOutcome.Unavailable

            val cacheFile = getCacheFile(fileItem.path)
            val metaFile  = getMetaFile(fileItem.path)

            // キャッシュ有効確認
            if (cacheFile.exists()) {
                val cachedModified = runCatching { metaFile.readText().toLong() }.getOrNull()
                if (cachedModified == fileItem.lastModified) return@withContext ThumbnailOutcome.Ready(cacheFile)
                cacheFile.delete()
                metaFile.delete()
            }

            generationSemaphore.withPermit {
                if (isLocalImageFile) {
                    generateAndCacheImageFile(file, fileItem.lastModified, cacheFile, metaFile)
                } else {
                    generateAndCache(file, fileItem.lastModified, cacheFile, metaFile)
                }
            }
        }

    /**
     * NASファイルのサムネイル生成
     * 1. STRキャッシュ済みならそこから生成
     * 2. 未キャッシュならNASから先頭2MBだけ取得して生成
     */
    private suspend fun getOrGenerateNasThumbnail(fileItem: FileItem): ThumbnailOutcome {
        val server  = fileItem.nasServer ?: return ThumbnailOutcome.Unavailable
        val nasPath = fileItem.nasPath
        val ext     = fileItem.name.substringAfterLast(".").lowercase()

        // NASサムネイル用キャッシュキー（nasPathのハッシュ値を使用）
        val cacheFile = File(cacheDir, "nas_${nasPath.hashCode()}.jpg")
        val metaFile  = File(cacheDir, "nas_${nasPath.hashCode()}.meta")

        // キャッシュがあればそのまま返す
        if (cacheFile.exists()) return ThumbnailOutcome.Ready(cacheFile)

        return generationSemaphore.withPermit {
            // 1. NASストリーミングキャッシュがあればそこから生成
            val strCacheFile = context?.let { NasStreamCache.destFile(it, nasPath, ext) }
            if (strCacheFile != null && strCacheFile.exists() && strCacheFile.length() > 0) {
                return@withPermit generateAndCache(strCacheFile, 0L, cacheFile, metaFile)
            }

            // 2. NASから先頭部分だけ取得して生成
            if (ext !in setOf("zip", "cbz")) return@withPermit ThumbnailOutcome.Unavailable  // ZIPのみ対応（RARは全体必要なためスキップ）

            val partialBytes = smbRepository.fetchPartialBytes(server, nasPath) ?: return@withPermit ThumbnailOutcome.Unavailable
            val scan = extractFirstImageFromZipBytes(partialBytes)
            if (scan.encrypted) return@withPermit ThumbnailOutcome.Encrypted
            val imageBytes = scan.bytes ?: return@withPermit ThumbnailOutcome.Unavailable

            generateCacheFromBytes(imageBytes, cacheFile).toThumbnailOutcome()
        }
    }

    /**
     * SAFファイルのサムネイル生成
     * 1. すでに閲覧済み（app_cacheにコピー済み）ならそこから生成
     * 2. 未コピーならSAF経由で先頭2MBだけ読み取って生成（ZIPのみ対応）
     */
    private suspend fun getOrGenerateSafThumbnail(fileItem: FileItem): ThumbnailOutcome {
        val uri = fileItem.uri ?: return ThumbnailOutcome.Unavailable
        val ctx = context ?: return ThumbnailOutcome.Unavailable
        val ext = fileItem.name.substringAfterLast(".").lowercase()

        val cacheFile = File(cacheDir, "saf_${uri.toString().hashCode()}.jpg")
        if (cacheFile.exists()) return ThumbnailOutcome.Ready(cacheFile)

        return generationSemaphore.withPermit {
            // 1. すでに閲覧用にキャッシュ済みならそこから生成（他形式も含めて対応可能）
            val readCacheFile = File(
                File(cacheDir.parentFile, "saf_cache"),
                "saf_${uri.toString().hashCode()}.$ext"
            )
            if (readCacheFile.exists() && readCacheFile.length() > 0) {
                return@withPermit generateAndCache(readCacheFile, 0L, cacheFile, File(cacheDir, "saf_${uri.toString().hashCode()}.meta"))
            }

            // 2. 未キャッシュならSAF経由で先頭2MBだけ読み取る（ZIPのみ対応）
            if (ext !in setOf("zip", "cbz")) return@withPermit ThumbnailOutcome.Unavailable

            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    input.readUpTo(2 * 1024 * 1024)
                } ?: return@withPermit ThumbnailOutcome.Unavailable
                val scan = extractFirstImageFromZipBytes(bytes)
                if (scan.encrypted) return@withPermit ThumbnailOutcome.Encrypted
                val imageBytes = scan.bytes ?: return@withPermit ThumbnailOutcome.Unavailable
                generateCacheFromBytes(imageBytes, cacheFile).toThumbnailOutcome()
            } catch (e: Exception) {
                ThumbnailOutcome.Unavailable
            }
        }
    }

    fun deleteThumbnail(filePath: String) {
        cacheDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension.contains(filePath.hashCode().toString())) {
                file.delete()
            }
        }
    }

    fun deleteAllThumbnails() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ─── プライベート ─────────────────────────────────────────────────────

    /**
     * InputStream#readNBytes(int) はAPI 33以降でしか使えない（minSdk=26のため直接使用不可）。
     * 同等の動作（最大maxBytesまで読み取る。ストリーム終端ならそこで打ち切る）を自前で実装。
     */
    private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
        val out   = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(chunk.size, maxBytes - total)
            val n = read(chunk, 0, toRead)
            if (n == -1) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun generateAndCache(
        file: File,
        lastModified: Long,
        cacheFile: File,
        metaFile: File
    ): ThumbnailOutcome {
        return try {
            when (FormatDetector.effectiveExtension(file)) {
                "zip", "cbz" -> {
                    val scan = extractFirstImageFromZip(file)
                    if (scan.encrypted) return ThumbnailOutcome.Encrypted
                    val bytes = scan.bytes ?: return ThumbnailOutcome.Unavailable
                    cacheBytes(bytes, lastModified, cacheFile, metaFile)
                }
                "rar", "cbr" -> {
                    if (RarSupport.isEncrypted(file)) return ThumbnailOutcome.Encrypted
                    val bytes = when (RarSupport.detectVersion(file)) {
                        RarVersion.RAR4 -> extractFirstImageFromRar(file)
                        else            -> RarSupport.extractFirstImageRar5(file)
                    } ?: return ThumbnailOutcome.Unavailable
                    cacheBytes(bytes, lastModified, cacheFile, metaFile)
                }
                "7z" -> {
                    if (ArchiveScanner.is7zEncrypted(file)) return ThumbnailOutcome.Encrypted
                    val bytes = extractFirstImageFrom7z(file) ?: return ThumbnailOutcome.Unavailable
                    cacheBytes(bytes, lastModified, cacheFile, metaFile)
                }
                "pdf" -> {
                    val bitmap = try {
                        renderFirstPdfPage(file)
                    } catch (e: SecurityException) {
                        return ThumbnailOutcome.Encrypted
                    } ?: return ThumbnailOutcome.Unavailable
                    cacheBitmap(bitmap, lastModified, cacheFile, metaFile)
                }
                else -> ThumbnailOutcome.Unavailable
            }
        } catch (e: Exception) {
            ThumbnailOutcome.Unavailable
        }
    }

    /** 画像ファイル単体（非圧縮画像フォルダ内の1枚）用の軽量なサムネイル生成。アーカイブ展開は不要 */
    private fun generateAndCacheImageFile(
        file: File,
        lastModified: Long,
        cacheFile: File,
        metaFile: File
    ): ThumbnailOutcome {
        return try {
            cacheBytes(file.readBytes(), lastModified, cacheFile, metaFile)
        } catch (e: Exception) {
            ThumbnailOutcome.Unavailable
        }
    }

    /** バイト列からサムネイルを生成し、成功したらmetaFileに更新日時を記録する */
    private fun cacheBytes(bytes: ByteArray, lastModified: Long, cacheFile: File, metaFile: File): ThumbnailOutcome {
        val result = generateCacheFromBytes(bytes, cacheFile) ?: return ThumbnailOutcome.Unavailable
        metaFile.writeText(lastModified.toString())
        return ThumbnailOutcome.Ready(result)
    }

    /** デコード済みBitmapからサムネイルを生成し、成功したらmetaFileに更新日時を記録する */
    private fun cacheBitmap(bitmap: Bitmap, lastModified: Long, cacheFile: File, metaFile: File): ThumbnailOutcome {
        val result = generateCacheFromBitmap(bitmap, cacheFile) ?: return ThumbnailOutcome.Unavailable
        metaFile.writeText(lastModified.toString())
        return ThumbnailOutcome.Ready(result)
    }

    private fun generateCacheFromBytes(imageBytes: ByteArray, cacheFile: File): File? {
        return try {
            // 1回目：inJustDecodeBoundsで寸法だけ取得し、原寸展開を避ける
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOptions)

            val sampleSize = calculateInSampleSize(
                boundsOptions.outWidth, boundsOptions.outHeight, TARGET_WIDTH, TARGET_HEIGHT
            )

            // 2回目：inSampleSizeで縮小しながら実際にデコード
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val original  = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions) ?: return null
            val thumbnail = createScaledBitmap(original, TARGET_WIDTH, TARGET_HEIGHT)
            original.recycle()
            FileOutputStream(cacheFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbnail.recycle()
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    /** デコード済みBitmap（PDFページ等）から、既存のJPEGキャッシュと同じダウンスケール処理でサムネイルを保存する */
    private fun generateCacheFromBitmap(original: Bitmap, cacheFile: File): File? {
        return try {
            val thumbnail = createScaledBitmap(original, TARGET_WIDTH, TARGET_HEIGHT)
            original.recycle()
            FileOutputStream(cacheFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbnail.recycle()
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    private fun createScaledBitmap(original: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val ratio       = minOf(targetWidth.toFloat() / original.width, targetHeight.toFloat() / original.height)
        val scaledWidth = (original.width  * ratio).toInt().coerceAtLeast(1)
        val scaledHeight= (original.height * ratio).toInt().coerceAtLeast(1)
        return original.scale(scaledWidth, scaledHeight)
    }

    private fun getCacheFile(filePath: String): File {
        // ファイル名+サイズでキャッシュキーを生成する（パス変更に強い）
        val file = File(filePath)
        val key  = "${file.name}_${file.length()}".hashCode()
        return File(cacheDir, "$key.jpg")
    }
    private fun getMetaFile(filePath: String): File {
        val file = File(filePath)
        val key  = "${file.name}_${file.length()}".hashCode()
        return File(cacheDir, "$key.meta")
    }

    companion object {
        private const val TARGET_WIDTH  = 240
        private const val TARGET_HEIGHT = 340

        // サムネイル「生成」の同時実行数を制限する（全インスタンス共有）。
        // キャッシュヒット時はこのセマフォを取らない。
        private val generationSemaphore = Semaphore(3)
    }
}

/**
 * デコード時の縮小率（2のべき乗）を計算する。BitmapFactoryは2のべき乗にしか切り下げないため、
 * reqWidth/reqHeightを下回らない最大の値を返す（下回ると後で拡大することになり画質が落ちる）。
 * srcWidth/srcHeightが0以下（デコード失敗でoutWidth/outHeightが-1になるケース）は1を返す。
 */
internal fun calculateInSampleSize(
    srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int
): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1

    var inSampleSize = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
        val halfHeight = srcHeight / 2
        val halfWidth  = srcWidth / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/** サムネイル1枚の上限サイズ（これを超える画像は表紙候補から外す） */
private const val MAX_THUMBNAIL_SOURCE_BYTES = 30L * 1024 * 1024  // 30MB

/**
 * 表紙を探す際に見るエントリ数の上限。
 * 表紙はアーカイブの先頭付近にあるので、全体を読み切る必要はない。
 * （中央ディレクトリが壊れたZIPは先頭から順に読むしかなく、
     数百MBを毎回読み切るとサムネイルがいつまでも完成しない）
 */
private const val MAX_ENTRIES_TO_SCAN = 40

/**
 * ストリームから上限付きで読む。上限を超えたら nullを返す。
 *
 * 【なぜ必要か】壊れたZIPではエントリの申告サイズが取れない（-1）ことがあり、
 * readBytes()をそのまま使うとファイル終端まで読み続けてしまう。
 * （実際、713MBのアーカイブで OutOfMemoryError が発生していた）
 */
private fun readBounded(input: java.io.InputStream, cap: Long): ByteArray? {
    val out   = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        if (total > cap) return null   // 大きすぎる＝表紙としては異常
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

/**
 * ZIPスキャン1回分の結果。
 * [encrypted]は、走査した画像エントリの中に暗号化されたものが1件でもあったか
 * （ArchiveScanner.scanZipのgeneralPurposeBit.usesEncryption()判定と同じ考え方）。
 * 暗号化エントリはバイト列を読まず（読んでも復号されない生データにしかならないため）スキップする。
 */
private data class ZipCoverScan(val bytes: ByteArray?, val encrypted: Boolean)

/**
 * ZIPから「名前順で一番若い画像」（＝表紙）を１枚だけ取り出す。
 *
 * 【重要】以前は全ページをメモリに溜めてから名前順で選んでいたため、
 * 大きなアーカイブ（例：713MB）で OutOfMemoryError が発生していた。
 * また、壊れたZIPはエントリの申告サイズが信用できないため、
 * 読みながら上限で打ち切る。
 *
 * 表紙は先頭付近にあるので、先頭の数十エントリだけ見て打ち切る。
 */
private fun pickCoverImage(
    openStream: (charsetName: String) -> ZipArchiveInputStream
): ZipCoverScan {
    var anyEncrypted = false
    for (cs in listOf(kotlin.text.Charsets.UTF_8.name(), "Shift_JIS")) {
        var bestName : String? = null
        var bestBytes: ByteArray? = null
        var examined = 0

        runCatching {
            openStream(cs).use { zis ->
                var entry = zis.nextZipEntry
                while (entry != null && examined < MAX_ENTRIES_TO_SCAN) {
                    val name = entry.name
                    val ext  = name.substringAfterLast(".").lowercase()
                    if (!entry.isDirectory && ext in IMAGE_EXTENSIONS) {
                        examined++
                        if (entry.generalPurposeBit.usesEncryption()) {
                            // 暗号化エントリは復号できないため読み飛ばす（呼び出し元にはencryptedで伝える）
                            anyEncrypted = true
                        } else {
                            val current = bestName
                            // 名前順でこれまでより若ければ、この1枚だけ読む（上限付き）
                            if (current == null || name.lowercase() < current) {
                                val bytes = readBounded(zis, MAX_THUMBNAIL_SOURCE_BYTES)
                                if (bytes != null && bytes.isNotEmpty()) {
                                    bestName  = name.lowercase()
                                    bestBytes = bytes
                                }
                            }
                        }
                    }
                    entry = zis.nextZipEntry
                }
            }
        }
        if (bestBytes != null) return ZipCoverScan(bestBytes, encrypted = false)
    }
    return ZipCoverScan(null, encrypted = anyEncrypted)
}

private fun extractFirstImageFromZipBytes(bytes: ByteArray): ZipCoverScan =
    pickCoverImage { cs ->
        ZipArchiveInputStream(ByteArrayInputStream(bytes), cs, false, true)
    }

private fun extractFirstImageFromZip(file: File): ZipCoverScan =
    // Shift-JISエントリ名に対応するため Apache Commons Compress を使用
    pickCoverImage { cs ->
        ZipArchiveInputStream(FileInputStream(file), cs, false, true)
    }

private fun extractFirstImageFromRar(file: File): ByteArray? {
    return Archive(file).use { archive ->
        archive.fileHeaders
            .filter { it.fileName.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS }
            .minByOrNull { it.fileName.lowercase() }
            ?.let { header ->
                val out = ByteArrayOutputStream()
                archive.extractFile(header, out)
                out.toByteArray()
            }
    }
}

/**
 * 7zから「名前順で一番若い画像」（＝表紙）を１枚だけ取り出す。
 * 7zは先頭に完全なヘッダー（エントリ一覧）を持つため、ZIP/RAR5と違い
 * SevenZFile#getEntries()で一覧を先に取得してからgetInputStream()でランダムアクセスできる
 * （2パス方式にする必要が無い）。
 */
private fun extractFirstImageFrom7z(file: File): ByteArray? {
    return SevenZFile.builder().setFile(file).get().use { sevenZFile ->
        val cover = sevenZFile.entries
            .filter { !it.isDirectory && it.hasStream() && (it.name ?: "").substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS }
            .minByOrNull { (it.name ?: "").lowercase() }
            ?: return@use null
        sevenZFile.getInputStream(cover).use { input ->
            readBounded(input, MAX_THUMBNAIL_SOURCE_BYTES)
        }
    }
}

/**
 * PDFの1ページ目をレンダリングする。
 * パスワード付きPDFはPdfRenderer生成時にSecurityExceptionを投げる
 * （ViewerViewModel.isPdfPasswordErrorと同じ挙動）ため、呼び出し元でcatchして暗号化扱いにする。
 */
private fun renderFirstPdfPage(file: File): Bitmap? {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    var result: Bitmap? = null
    PdfRenderer(pfd).use { renderer ->
        if (renderer.pageCount == 0) return@use
        renderer.openPage(0).use { page ->
            val scale = minOf(
                THUMBNAIL_PDF_TARGET_WIDTH.toFloat()  / page.width,
                THUMBNAIL_PDF_TARGET_HEIGHT.toFloat() / page.height
            )
            val width  = (page.width  * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            result = bitmap
        }
    }
    return result
}

/** PDFページ描画時の目標サイズ（サムネイル目標サイズの2倍で描画し、後段でTARGET_WIDTH/HEIGHTへ収める） */
private const val THUMBNAIL_PDF_TARGET_WIDTH  = 480
private const val THUMBNAIL_PDF_TARGET_HEIGHT = 680
