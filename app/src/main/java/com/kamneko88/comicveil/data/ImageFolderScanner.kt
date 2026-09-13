package com.kamneko88.comicveil.data

import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/**
 * 非圧縮の画像ファイルが直接並んでいるフォルダ（ComicGlassでの「画像フォルダ」）を扱うユーティリティ。
 * フォルダ直下の画像ファイルだけを対象とし、サブフォルダの中までは見に行かない
 * （サブフォルダは複数巻扱いせず、通常のフォルダとしてナビゲーションする）。
 */
object ImageFolderScanner {

    /** フォルダ直下（再帰しない）の画像ファイルを自然順ソートして返す */
    fun scan(folder: File): List<File> {
        val files = folder.listFiles { f -> f.isFile && isImage(f.name) } ?: return emptyList()
        return files.sortedWith(compareBy(NaturalOrder.COMPARATOR) { it.name })
    }

    /** 拡張子が対象の画像形式か（大文字小文字問わず） */
    fun isImage(name: String): Boolean =
        name.substringAfterLast(".", "").lowercase() in IMAGE_EXTENSIONS
}
