package com.kamneko88.comicveil.data

import java.io.File

enum class FileItemType {
    FOLDER,
    COMIC_FILE,
    IMAGE_FILE
}

data class FileItem(
    val file: File,
    val type: FileItemType,
    val name: String = file.name,
    val path: String = file.absolutePath,
    val size: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified()
) {
    val isComic: Boolean get() = type == FileItemType.COMIC_FILE
    val isFolder: Boolean get() = type == FileItemType.FOLDER

    companion object {
        val COMIC_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf")

        fun fromFile(file: File): FileItem {
            val type = when {
                file.isDirectory -> FileItemType.FOLDER
                file.extension.lowercase() in COMIC_EXTENSIONS -> FileItemType.COMIC_FILE
                else -> FileItemType.IMAGE_FILE
            }
            return FileItem(file = file, type = type)
        }
    }
}