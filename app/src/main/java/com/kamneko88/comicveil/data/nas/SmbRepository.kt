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
import java.io.ByteArrayOutputStream
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
                        val lastModifiedMs = runCatching {
                            (entry.lastWriteTime.windowsTimeStamp - 116444736000000000L) / 10000L
                        }.getOrDefault(0L)
                        FileItem.fromNas(
                            name           = entry.fileName,
                            nasPath        = entryNasPath,
                            isDirectory    = isDir,
                            size           = entry.endOfFile,
                            server         = server,
                            lastModifiedMs = lastModifiedMs
                        )
                    }
                    .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
            } finally {
                runCatching { client.close() }
            }
        }

    /**
     * ファイルをダウンロードする
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

            val totalSize: Long = runCatching {
                smbFile.getFileInformation().standardInformation.endOfFile
            }.getOrDefault(-1L)

            destFile.parentFile?.mkdirs()

            val buffer = ByteArray(256 * 1024)
            var downloaded = 0L
            var lastReportedAt = 0L

            smbFile.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        if (!isActive) {
                            throw CancellationException("ダウンロードがキャンセルされました")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 進捗はIOスレッドから直接通知する（StateFlowの更新はスレッドセーフ）。
                        // 毎チャンクMainへ切り替えると、大きいファイルでMainスレッドが詰まり
                        // 進捗バーが固まるため、約100msごとに間引いて通知する。
                        val now = System.currentTimeMillis()
                        if (now - lastReportedAt >= 100L) {
                            lastReportedAt = now
                            onProgress?.invoke(downloaded, totalSize)
                        }
                    }
                }
            }
            // 完了時は必ず最終値（100%）を通知する
            onProgress?.invoke(downloaded, totalSize)
            destFile
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * サムネイル生成用に先頭部分だけ取得する
     * ZIPの先頭エントリは先頭部分にあるため、大部分のケースでサムネイル取得が可能
     * 失敗時は null を返す
     */
    suspend fun fetchPartialBytes(
        server: NasServer,
        nasPath: String,
        maxBytes: Long = 2 * 1024 * 1024L
    ): ByteArray? = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            val connection = client.connect(server.host)
            val auth = AuthenticationContext(
                server.username,
                server.password.toCharArray(),
                null
            )
            val session = connection.authenticate(auth)
            val share   = session.connectShare(server.shareName) as DiskShare
            val smbPath = nasPath.replace("/", "\\")
            val smbFile = share.openFile(
                smbPath,
                setOf(AccessMask.GENERIC_READ),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val baos  = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            smbFile.inputStream.use { input ->
                while (total < maxBytes) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    baos.write(chunk, 0, read)
                    total += read
                }
            }
            baos.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { client.close() }
        }
    }

    // ※ downloadZipProgressive（NAS上でZIPを逐次ストリーミング展開する旧ロジック）は削除済み。
    // NASコミックは全体ダウンロード後にArchiveScannerで巻検出する方式に統一したため、
    // downloadFile()だけで十分となった（HomeViewModel.downloadThenOpenNasComic参照）。
}
