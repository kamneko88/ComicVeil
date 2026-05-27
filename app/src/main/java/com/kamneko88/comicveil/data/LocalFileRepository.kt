package com.kamneko88.comicveil.data

import java.io.File

class LocalFileRepository {

    fun getFiles(directory: File): List<FileItem> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles()
            ?.filter { file -> !file.name.startsWith(".") }
            ?.map { file -> FileItem.fromFile(file) }
            ?.sortedWith(
                compareBy(
                    { !it.isFolder },
                    { it.name.lowercase() }
                )
            )
            ?: emptyList()
    }

    fun parseFileName(fileName: String): Pair<String, String?> {
        val nameWithoutExtension = fileName.substringBeforeLast(".")
        val authorRegex = Regex("""\[([^\]]+)\]""")
        val authorMatch = authorRegex.find(nameWithoutExtension)
        val author = authorMatch?.groupValues?.get(1)
        val title = nameWithoutExtension
            .replace(Regex("""^\([^)]*\)\s*"""), "")
            .replace(Regex("""\[[^\]]*\]\s*"""), "")
            .trim()
        return Pair(title.ifEmpty { nameWithoutExtension }, author)
    }
}