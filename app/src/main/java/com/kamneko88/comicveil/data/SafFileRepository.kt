package com.kamneko88.comicveil.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF（Storage Access Framework）で許可されたフォルダの中身を取得するリポジトリ。
 * MANAGE_EXTERNAL_STORAGE権限を使わずに、ユーザーが選んだフォルダのみを扱う。
 */
class SafFileRepository {

    /** 指定したフォルダURI直下のファイル・フォルダ一覧を取得する */
    fun getFiles(context: Context, folderUri: Uri): List<FileItem> {
        val doc = DocumentFile.fromTreeUri(context, folderUri)
        if (doc == null || !doc.isDirectory) return emptyList()

        return doc.listFiles()
            .filter { !(it.name ?: "").startsWith(".") }
            .map { FileItem.fromDocumentFile(it) }
            .sortedWith(
                compareBy(
                    { !it.isFolder },
                    { it.name.lowercase() }
                )
            )
    }

    /** 指定URIの親フォルダを取得する（ルートまで戻ったらnull） */
    fun getParent(context: Context, folderUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, folderUri)?.parentFile

    /** フォルダの表示名を取得する */
    fun getDisplayName(context: Context, folderUri: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, folderUri)
        return doc?.name ?: "フォルダ"
    }
}
