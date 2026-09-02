package com.kamneko88.comicveil.data

/** archive_pages 配下の1ディレクトリぶんの情報 */
data class CacheDirInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * 合計が上限を超えているとき、削除すべきディレクトリ名を古い順に返す。
 * limitBytes <= 0 は「無制限」とみなし、常に空リストを返す。
 */
fun selectDirsToEvict(entries: List<CacheDirInfo>, limitBytes: Long): List<String> {
    if (limitBytes <= 0) return emptyList()

    val totalBytes = entries.sumOf { it.sizeBytes }
    if (totalBytes <= limitBytes) return emptyList()

    val toEvict = mutableListOf<String>()
    var remaining = totalBytes
    for (entry in entries.sortedBy { it.lastModified }) {
        if (remaining <= limitBytes) break
        toEvict.add(entry.name)
        remaining -= entry.sizeBytes
    }
    return toEvict
}
