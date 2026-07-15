package com.kamneko88.comicveil.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kamneko88.comicveil.data.nas.NasServer
import java.io.File

enum class FileItemType {
    FOLDER,
    COMIC_FILE,
    IMAGE_FILE
}

/**
 * ローカル(File)・SAF(Uri)・NASどれでも表現できる汎用クラス
 *
 * ローカルファイル：file が非null
 * SAFファイル    ：uri が非null（file・nasServerはnull）
 * NASファイル    ：nasServer・nasPath が非null
 */
data class FileItem(
    val file: File? = null,
    val uri: Uri? = null,
    val type: FileItemType,
    val name: String        = file?.name ?: "",
    val path: String        = file?.absolutePath ?: uri?.toString() ?: "",
    val size: Long          = if (file?.isFile == true) file.length() else 0L,
    val lastModified: Long  = file?.lastModified() ?: 0L,
    // NAS専用フィールド
    val nasServer: NasServer? = null,
    val nasPath: String       = "",  // 共有フォルダ内の相対パス（/ 区切り）
    /** HOMEに登録したリモートブックマークか（表示でバッジを付けて区別する） */
    val isRemoteBookmark: Boolean = false
) {
    val isComic: Boolean  get() = type == FileItemType.COMIC_FILE
    val isFolder: Boolean get() = type == FileItemType.FOLDER
    val isNas: Boolean    get() = nasServer != null
    val isSaf: Boolean    get() = uri != null
    val extension: String get() = name.substringAfterLast(".", "")

    companion object {
        val COMIC_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf")

        private fun typeFor(name: String, isDirectory: Boolean): FileItemType = when {
            isDirectory -> FileItemType.FOLDER
            name.substringAfterLast(".").lowercase() in COMIC_EXTENSIONS -> FileItemType.COMIC_FILE
            else -> FileItemType.IMAGE_FILE
        }

        /** ローカルファイルから生成 */
        fun fromFile(file: File): FileItem =
            FileItem(file = file, type = typeFor(file.name, file.isDirectory))

        /** SAF(DocumentFile)から生成 */
        fun fromDocumentFile(doc: DocumentFile): FileItem {
            val name = doc.name ?: ""
            return FileItem(
                uri          = doc.uri,
                type         = typeFor(name, doc.isDirectory),
                name         = name,
                path         = doc.uri.toString(),
                size         = doc.length(),
                lastModified = doc.lastModified()
            )
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
            val type = typeFor(name, isDirectory)
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
