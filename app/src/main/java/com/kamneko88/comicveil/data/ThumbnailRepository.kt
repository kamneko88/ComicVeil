package com.kamneko88.comicveil.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.SmbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ThumbnailRepository(private val cacheDir: File, private val context: Context? = null) {

    private val smbRepository = SmbRepository()

    init {
        cacheDir.mkdirs()
    }

    /**
     * サムネイルを取得する。キャッシュがあれば即返し、なければ生成してから返す。
     * NASファイルは STRキャッシュ → 部分取得 の順で生成する
     * SAFファイルは 先頭部分取得（ZIPのみ）で生成する
     */
    suspend fun getOrGenerateThumbnail(fileItem: FileItem): File? =
        withContext(Dispatchers.IO) {
            if (!fileItem.isComic) return@withContext null

            if (fileItem.isNas) {
                return@withContext getOrGenerateNasThumbnail(fileItem)
            }

            if (fileItem.isSaf) {
                return@withContext getOrGenerateSafThumbnail(fileItem)
            }

            val file = fileItem.file ?: return@withContext null

            val cacheFile = getCacheFile(fileItem.path)
            val metaFile  = getMetaFile(fileItem.path)

            // キャッシュ有効確認
            if (cacheFile.exists()) {
                val cachedModified = runCatching { metaFile.readText().toLong() }.getOrNull()
                if (cachedModified == fileItem.lastModified) return@withContext cacheFile
                cacheFile.delete()
                metaFile.delete()
            }

            generateAndCache(file, fileItem.lastModified, cacheFile, metaFile)
        }

    /**
     * NASファイルのサムネイル生成
     * 1. STRキャッシュ済みならそこから生成
     * 2. 未キャッシュならNASから先頭2MBだけ取得して生成
     */
    private suspend fun getOrGenerateNasThumbnail(fileItem: FileItem): File? {
        val server  = fileItem.nasServer ?: return null
        val nasPath = fileItem.nasPath
        val ext     = fileItem.name.substringAfterLast(".").lowercase()

        // NASサムネイル用キャッシュキー（nasPathのハッシュ値を使用）
        val cacheFile = File(cacheDir, "nas_${nasPath.hashCode()}.jpg")
        val metaFile  = File(cacheDir, "nas_${nasPath.hashCode()}.meta")

        // キャッシュがあればそのまま返す
        if (cacheFile.exists()) return cacheFile

        // 1. STRキャッシュがあればそこから生成
        val strCacheFile = File(
            File(cacheDir.parentFile, "nas_cache"),
            "nas_${nasPath.hashCode()}.$ext"
        )
        if (strCacheFile.exists() && strCacheFile.length() > 0) {
            return generateAndCache(strCacheFile, 0L, cacheFile, metaFile)
        }

        // 2. NASから先頭部分だけ取得して生成
        if (ext !in setOf("zip", "cbz")) return null  // ZIPのみ対応（RARは全体必要なためスキップ）

        val partialBytes = smbRepository.fetchPartialBytes(server, nasPath) ?: return null
        val imageBytes   = extractFirstImageFromZipBytes(partialBytes) ?: return null

        return generateCacheFromBytes(imageBytes, cacheFile)
    }

    /**
     * SAFファイルのサムネイル生成
     * 1. すでに閲覧済み（app_cacheにコピー済み）ならそこから生成
     * 2. 未コピーならSAF経由で先頭2MBだけ読み取って生成（ZIPのみ対応）
     */
    private fun getOrGenerateSafThumbnail(fileItem: FileItem): File? {
        val uri = fileItem.uri ?: return null
        val ctx = context ?: return null
        val ext = fileItem.name.substringAfterLast(".").lowercase()

        val cacheFile = File(cacheDir, "saf_${uri.toString().hashCode()}.jpg")
        if (cacheFile.exists()) return cacheFile

        // 1. すでに閲覧用にキャッシュ済みならそこから生成（他形式も含めて対応可能）
        val readCacheFile = File(
            File(cacheDir.parentFile, "saf_cache"),
            "saf_${uri.toString().hashCode()}.$ext"
        )
        if (readCacheFile.exists() && readCacheFile.length() > 0) {
            return generateAndCache(readCacheFile, 0L, cacheFile, File(cacheDir, "saf_${uri.toString().hashCode()}.meta"))
        }

        // 2. 未キャッシュならSAF経由で先頭2MBだけ読み取る（ZIPのみ対応）
        if (ext !in setOf("zip", "cbz")) return null

        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                input.readNBytes(2 * 1024 * 1024)
            } ?: return null
            val imageBytes = extractFirstImageFromZipBytes(bytes) ?: return null
            generateCacheFromBytes(imageBytes, cacheFile)
        } catch (e: Exception) {
            null
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

    private fun generateAndCache(
        file: File,
        lastModified: Long,
        cacheFile: File,
        metaFile: File
    ): File? {
        return try {
            val imageBytes = when (file.extension.lowercase()) {
                "zip", "cbz" -> extractFirstImageFromZip(file)
                "rar", "cbr" -> extractFirstImageFromRar(file)
                else -> null
            } ?: return null

            generateCacheFromBytes(imageBytes, cacheFile)?.also {
                metaFile.writeText(lastModified.toString())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateCacheFromBytes(imageBytes: ByteArray, cacheFile: File): File? {
        return try {
            val original  = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
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
        return Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true)
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
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

private fun extractFirstImageFromZipBytes(bytes: ByteArray): ByteArray? {
    val candidates = mutableListOf<Pair<String, ByteArray>>()
    for (cs in listOf(kotlin.text.Charsets.UTF_8, charset("Shift_JIS"))) {
        candidates.clear()
        runCatching {
            ZipArchiveInputStream(
                ByteArrayInputStream(bytes), cs.name(), false, true
            ).use { zis ->
                var entry = zis.nextZipEntry
                while (entry != null) {
                    val name = entry.name
                    val ext  = name.substringAfterLast(".").lowercase()
                    if (!entry.isDirectory && ext in IMAGE_EXTENSIONS) {
                        val b = zis.readBytes()
                        if (b.isNotEmpty()) candidates.add(name to b)
                    }
                    entry = zis.nextZipEntry
                }
            }
        }
        if (candidates.isNotEmpty()) break
    }
    return candidates.minByOrNull { it.first.lowercase() }?.second
}

private fun extractFirstImageFromZip(file: File): ByteArray? {
    // Shift-JISエントリ名に対応するため Apache Commons Compress を使用
    val candidates = mutableListOf<Pair<String, ByteArray>>()
    try {
        fun openStream(cs: java.nio.charset.Charset) =
            ZipArchiveInputStream(FileInputStream(file), cs.name(), false, true)

        // UTF-8で試行、失敗したら Shift-JIS で再試
        for (cs in listOf(kotlin.text.Charsets.UTF_8, charset("Shift_JIS"))) {
            candidates.clear()
            runCatching {
                openStream(cs).use { zis ->
                    var entry = zis.nextZipEntry
                    while (entry != null) {
                        val name = entry.name
                        val ext  = name.substringAfterLast(".").lowercase()
                        if (!entry.isDirectory && ext in IMAGE_EXTENSIONS) {
                            val bytes = zis.readBytes()
                            if (bytes.isNotEmpty()) candidates.add(name to bytes)
                        }
                        entry = zis.nextZipEntry
                    }
                }
            }
            if (candidates.isNotEmpty()) break
        }
    } catch (e: Exception) {
        return null
    }
    return candidates.minByOrNull { it.first.lowercase() }?.second
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
