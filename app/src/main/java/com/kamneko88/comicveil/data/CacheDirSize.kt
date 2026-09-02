package com.kamneko88.comicveil.data

import java.io.File

/** ディレクトリ配下の全ファイルサイズの合計（再帰）。存在しない・空なら0。 */
fun calcDirSize(dir: File): Long =
    dir.listFiles()?.sumOf { if (it.isDirectory) calcDirSize(it) else it.length() } ?: 0L
