package com.kamneko88.comicveil.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * サムネイルの生成・ディスクキャッシュ管理
 *
 * キャッシュ構造：
 *   thumbnails/{filePathのhash}.jpg   ← サムネイル画像
 *   thumbnails/{filePathのhash}.meta  ← ファイルの lastModified（キャッシュ有効性チェック用）
 *
 * @param cacheDir サムネイルを保存するディレクトリ（通常 context.cacheDir/thumbnails）
 */
class ThumbnailRepository(private val cacheDir: File) {

    init {
        cacheDir.mkdirs()
    }

    /**
     * サムネイルを取得する。キャッシュがあれば即返し、なければ生成してから返す。
     * @return サムネイルのキャッシュFile。生成に失敗した場合はnull
     */
    suspend fun getOrGenerateThumbnail(fileItem: FileItem): File? =
        withContext(Dispatchers.IO) {
            if (!fileItem.isComic) return@withContext null

            val cacheFile = getCacheFile(fileItem.path)
            val metaFile = getMetaFile(fileItem.path)

            // キャッシュが存在し、かつファイルの更新日と一致していれば有効
            if (cacheFile.exists()) {
                val cachedModified = runCatching {
                    metaFile.readText().toLong()
                }.getOrNull()
                if (cachedModified == fileItem.lastModified) {
                    return@withContext cacheFile
                }
                // 古いキャッシュを削除して再生成
                cacheFile.delete()
                metaFile.delete()
            }

            // 新規生成
            generateAndCache(fileItem, cacheFile, metaFile)
        }

    /** 特定ファイルのサムネイルキャッシュを削除 */
    fun deleteThumbnail(filePath: String) {
        getCacheFile(filePath).delete()
        getMetaFile(filePath).delete()
    }

    /** 全サムネイルキャッシュを削除 */
    fun deleteAllThumbnails() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ─── プライベート ─────────────────────────────────────────────────────

    private fun generateAndCache(
        fileItem: FileItem,
        cacheFile: File,
        metaFile: File
    ): File? {
        return try {
            // アーカイブから1枚目の画像バイト列を取得
            val imageBytes = when (fileItem.file.extension.lowercase()) {
                "zip", "cbz" -> extractFirstImageFromZip(fileItem.file)
                "rar", "cbr" -> extractFirstImageFromRar(fileItem.file)
                else -> null
            } ?: return null

            // バイト列 → Bitmap → 縮小 → JPEGとしてディスクに保存
            val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return null
            val thumbnail = createScaledBitmap(original, TARGET_WIDTH, TARGET_HEIGHT)
            original.recycle()

            FileOutputStream(cacheFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbnail.recycle()

            // キャッシュ有効性確認用にlastModifiedを保存
            metaFile.writeText(fileItem.lastModified.toString())

            cacheFile
        } catch (e: Exception) {
            // 生成失敗（破損ファイルなど）は null を返してアイコン表示にフォールバック
            null
        }
    }

    /**
     * アスペクト比を保ちながら縮小する。
     * targetWidth・targetHeightのどちらかに合わせて縮小（はみ出さない）。
     */
    private fun createScaledBitmap(
        original: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val ratio = minOf(
            targetWidth.toFloat() / original.width,
            targetHeight.toFloat() / original.height
        )
        val scaledWidth = (original.width * ratio).toInt().coerceAtLeast(1)
        val scaledHeight = (original.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true)
    }

    private fun getCacheFile(filePath: String): File =
        File(cacheDir, "${filePath.hashCode()}.jpg")

    private fun getMetaFile(filePath: String): File =
        File(cacheDir, "${filePath.hashCode()}.meta")

    companion object {
        // サムネイルの最大サイズ（px）。マンガ表紙の縦長比率を想定
        private const val TARGET_WIDTH = 240
        private const val TARGET_HEIGHT = 340
    }
}

// ─── アーカイブから1枚目の画像を取得（トップレベル関数）───────────────────

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/** ZIP/CBZから1枚目の画像を取得（ファイル名昇順の先頭） */
private fun extractFirstImageFromZip(file: File): ByteArray? {
    return ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { entry ->
                entry.name.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
            }
            .minByOrNull { it.name.lowercase() }
            ?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            }
    }
}

/** RAR/CBRから1枚目の画像を取得（ファイル名昇順の先頭） */
private fun extractFirstImageFromRar(file: File): ByteArray? {
    return Archive(file).use { archive ->
        archive.fileHeaders
            .filter { header ->
                header.fileName.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS
            }
            .minByOrNull { it.fileName.lowercase() }
            ?.let { header ->
                val out = ByteArrayOutputStream()
                archive.extractFile(header, out)
                out.toByteArray()
            }
    }
}