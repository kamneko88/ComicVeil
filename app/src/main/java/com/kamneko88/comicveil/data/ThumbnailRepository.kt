package com.kamneko88.comicveil.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.kamneko88.comicveil.data.nas.NasServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ThumbnailRepository(private val cacheDir: File) {

    init {
        cacheDir.mkdirs()
    }

    /**
     * サムネイルを取得する。キャッシュがあれば即返し、なければ生成してから返す。
     * NASファイルは Phase 1 では非対応（nullを返してアイコン表示）
     */
    suspend fun getOrGenerateThumbnail(fileItem: FileItem): File? =
        withContext(Dispatchers.IO) {
            if (!fileItem.isComic) return@withContext null
            if (fileItem.isNas) return@withContext null      // ★ NASは非対応
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

    fun deleteThumbnail(filePath: String) {
        getCacheFile(filePath).delete()
        getMetaFile(filePath).delete()
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

            val original  = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val thumbnail = createScaledBitmap(original, TARGET_WIDTH, TARGET_HEIGHT)
            original.recycle()

            FileOutputStream(cacheFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbnail.recycle()
            metaFile.writeText(lastModified.toString())

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

private fun extractFirstImageFromZip(file: File): ByteArray? {
    return ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS }
            .minByOrNull { it.name.lowercase() }
            ?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    }
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