package com.kamneko88.comicveil.data.nas

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.kamneko88.comicveil.data.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SmbRepository {

    suspend fun listDirectory(server: NasServer, nasPath: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            val client = SMBClient()
            try {
                val connection = client.connect(server.host)
                val auth = AuthenticationContext(
                    server.username,
                    server.password.toCharArray(),
                    null
                )
                val session = connection.authenticate(auth)

                // shareName が空 → エラーを返す（将来のショートカット実装時に対応予定）
                if (server.shareName.isEmpty() && nasPath.isEmpty()) {
                    throw IllegalStateException("共有フォルダ名が未設定です。\nリモートの編集から共有フォルダ名を設定してください。")
                }

                val share = session.connectShare(server.shareName) as DiskShare
                val smbPath = nasPath.replace("/", "\\")

                share.list(smbPath)
                    .filter { entry ->
                        entry.fileName != "." &&
                                entry.fileName != ".." &&
                                !entry.fileName.startsWith(".")
                    }
                    .map { entry ->
                        val isDir = entry.fileAttributes and 0x10L != 0L
                        val entryNasPath = if (nasPath.isEmpty()) entry.fileName
                        else "$nasPath/${entry.fileName}"
                        FileItem.fromNas(
                            name        = entry.fileName,
                            nasPath     = entryNasPath,
                            isDirectory = isDir,
                            size        = entry.endOfFile,
                            server      = server
                        )
                    }
                    .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
            } finally {
                runCatching { client.close() }
            }
        }

    /**
     * ファイルをダウンロードする
     *
     * @param server     NASサーバー情報
     * @param nasPath    NAS上のファイルパス
     * @param destFile   保存先ファイル
     * @param onProgress 進捗コールバック (downloadedBytes, totalBytes)
     *                   totalBytes が -1 の場合はファイルサイズ不明
     */
    suspend fun downloadFile(
        server: NasServer,
        nasPath: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            val connection = client.connect(server.host)
            val auth = AuthenticationContext(
                server.username,
                server.password.toCharArray(),
                null
            )
            val session = connection.authenticate(auth)
            val share = session.connectShare(server.shareName) as DiskShare

            val smbPath = nasPath.replace("/", "\\")
            val smbFile = share.openFile(
                smbPath,
                setOf(AccessMask.GENERIC_READ),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            // ファイルサイズを取得（取れない場合は -1）
            val totalSize: Long = runCatching {
                smbFile.getFileInformation().standardInformation.endOfFile
            }.getOrDefault(-1L)

            destFile.parentFile?.mkdirs()

            val buffer = ByteArray(256 * 1024) // 256KB バッファ
            var downloaded = 0L

            smbFile.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        // キャンセル確認：コルーチンがキャンセルされていたら中断
                        if (!isActive) {
                            throw CancellationException("ダウンロードがキャンセルされました")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, totalSize)
                    }
                }
            }
            destFile
        } finally {
            runCatching { client.close() }
        }
    }
}