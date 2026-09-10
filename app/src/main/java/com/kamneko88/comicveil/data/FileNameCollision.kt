package com.kamneko88.comicveil.data

/**
 * 保存先フォルダに希望のファイル名(desiredName)が既にある場合、常に連番付きの別名を返す。
 * 中身が同じでも違っても絶対に上書きしない（ComicGlassの実機挙動を参考にした方式）。
 *
 * 例：「foo.zip」が既にあれば「foo_2.zip」、それも既にあれば「foo_3.zip」……と
 * 空いている番号まで進める。
 */
fun resolveUniqueFileName(desiredName: String, existingNames: Set<String>): String {
    if (desiredName !in existingNames) return desiredName

    val dotIndex = desiredName.lastIndexOf('.')
    val base = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
    val ext  = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

    var n = 2
    while ("${base}_$n$ext" in existingNames) n++
    return "${base}_$n$ext"
}
