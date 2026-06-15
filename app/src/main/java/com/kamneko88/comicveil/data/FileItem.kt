package com.kamneko88.comicveil.data

import com.kamneko88.comicveil.data.nas.NasServer
import java.io.File

enum class FileItemType {
    FOLDER,
    COMIC_FILE,
    IMAGE_FILE
}

/**
 * ローカル・NASどちらのファイルも表現できる汎用クラス
 *
 * ローカルファイル：file が非null、nasServer は null
 * NASファイル    ：file が null、nasServer・nasPath が非null
 */
data class FileItem(
    val file: File? = null,
    val type: FileItemType,
    val name: String        = file?.name ?: "",
    val path: String        = file?.absolutePath ?: "",
    val size: Long          = if (file?.isFile == true) file.length() else 0L,
    val lastModified: Long  = file?.lastModified() ?: 0L,
    // NAS専用フィールド
    val nasServer: NasServer? = null,
    val nasPath: String       = ""   // 共有フォルダ内の相対パス（/ 区切り）
) {
    val isComic: Boolean  get() = type == FileItemType.COMIC_FILE
    val isFolder: Boolean get() = type == FileItemType.FOLDER
    val isNas: Boolean    get() = nasServer != null
    val extension: String get() = name.substringAfterLast(".", "")

    companion object {
        val COMIC_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf")

        /** ローカルファイルから生成 */
        fun fromFile(file: File): FileItem {
            val type = when {
                file.isDirectory -> FileItemType.FOLDER
                file.extension.lowercase() in COMIC_EXTENSIONS -> FileItemType.COMIC_FILE
                else -> FileItemType.IMAGE_FILE
            }
            return FileItem(file = file, type = type)
        }

        /** NASファイルから生成 */
        fun fromNas(
            name: String,
            nasPath: String,
            isDirectory: Boolean,
            size: Long,
            server: NasServer,
            lastModifiedMs: Long = 0L
        ): FileItem {
            val ext = name.substringAfterLast(".").lowercase()
            val type = when {
                isDirectory -> FileItemType.FOLDER
                ext in COMIC_EXTENSIONS -> FileItemType.COMIC_FILE
                else -> FileItemType.IMAGE_FILE
            }
            return FileItem(
                file      = null,
                type      = type,
                name      = name,
                path      = "smb://${server.host}/${server.shareName}/$nasPath",
                size      = size,
                lastModified = lastModifiedMs,
                nasServer = server,
                nasPath   = nasPath
            )
        }
    }
}