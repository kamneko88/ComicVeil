package com.kamneko88.comicveil.data

import java.io.File

/**
 * 本棚モードのフォルダタイルに表示する、フォルダ内コミックの表紙（最大4枚）を選ぶロジック。
 *
 * 表紙は「フォルダ内のコミックを名前の自然順で先頭から見て、サムネイルが取得できたものだけを
 * 最大4枚集める」というルール。暗号化・生成失敗などで取得できなかった候補は読み飛ばして次を見る。
 * 見る候補数そのものにも上限を設け、巨大フォルダで時間がかかりすぎないようにする。
 */
object ShelfFolderCovers {
    const val MAX_FILES_TO_SCAN = 12
    const val MAX_COVERS = 4

    /** フォルダ直下のコミックだけを対象に、名前の自然順で先頭からmaxFiles件に絞る */
    fun candidateComics(items: List<FileItem>, maxFiles: Int = MAX_FILES_TO_SCAN): List<FileItem> =
        items.filter { it.isComic }
            .sortedWith(compareBy(NaturalOrder.COMPARATOR) { it.name })
            .take(maxFiles)

    /**
     * 候補を自然順で先頭から見て、サムネイルが取得できた(Ready)ものだけを最大maxCovers枚集める。
     * 取得できなかった候補は読み飛ばして次を見る（maxCovers枚集まるか、候補を使い切ったら終了）。
     */
    suspend fun collectCovers(
        items: List<FileItem>,
        maxFiles: Int = MAX_FILES_TO_SCAN,
        maxCovers: Int = MAX_COVERS,
        fetchThumbnail: suspend (FileItem) -> ThumbnailOutcome
    ): List<File> {
        val covers = mutableListOf<File>()
        for (candidate in candidateComics(items, maxFiles)) {
            if (covers.size >= maxCovers) break
            val outcome = fetchThumbnail(candidate)
            if (outcome is ThumbnailOutcome.Ready) covers += outcome.file
        }
        return covers
    }
}
