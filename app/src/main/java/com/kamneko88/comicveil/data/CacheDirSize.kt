package com.kamneko88.comicveil.data

import java.io.File

/** ディレクトリ配下の全ファイルサイズの合計（再帰）。存在しない・空なら0。 */
fun calcDirSize(dir: File): Long =
    dir.listFiles()?.sumOf { if (it.isDirectory) calcDirSize(it) else it.length() } ?: 0L

/** サムネイルキャッシュの集計結果。 */
data class ThumbnailCacheStats(
    val totalBytes: Long,
    val nasCount: Int,
    val safCount: Int,
    val localCount: Int
) {
    val totalCount: Int get() = nasCount + safCount + localCount
}

internal enum class ThumbnailOrigin { NAS, SAF, LOCAL }

private const val NAS_PREFIX = "nas_"
private const val SAF_PREFIX = "saf_"
private const val JPG_EXTENSION = ".jpg"

/** ファイル名から生成経路を判定する。`.jpg` 以外は null。判定は大文字小文字を区別しない。 */
internal fun classifyThumbnailFileName(name: String): ThumbnailOrigin? {
    if (!name.endsWith(JPG_EXTENSION, ignoreCase = true)) return null
    return when {
        name.startsWith(NAS_PREFIX, ignoreCase = true) -> ThumbnailOrigin.NAS
        name.startsWith(SAF_PREFIX, ignoreCase = true) -> ThumbnailOrigin.SAF
        else -> ThumbnailOrigin.LOCAL
    }
}

/**
 * サムネイルキャッシュディレクトリの合計サイズと、`.jpg` の枚数を生成経路別に集計する。
 * `listFiles()` は1回だけ呼び出し、サイズと件数を同じループで求める。
 * ディレクトリが存在しない・空ならすべて0。
 */
fun calcThumbnailCacheStats(dir: File): ThumbnailCacheStats {
    val files = dir.listFiles() ?: return ThumbnailCacheStats(0L, 0, 0, 0)

    var totalBytes = 0L
    var nasCount = 0
    var safCount = 0
    var localCount = 0

    for (file in files) {
        if (file.isDirectory) {
            totalBytes += calcDirSize(file)
            continue
        }
        totalBytes += file.length()
        when (classifyThumbnailFileName(file.name)) {
            ThumbnailOrigin.NAS -> nasCount++
            ThumbnailOrigin.SAF -> safCount++
            ThumbnailOrigin.LOCAL -> localCount++
            null -> Unit
        }
    }

    return ThumbnailCacheStats(totalBytes, nasCount, safCount, localCount)
}
