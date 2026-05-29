package com.kamneko88.comicveil.data.nas

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.kamneko88.comicveil.data.FileItem
import kotlinx.coroutines.Dispatchers
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
                val share = session.connectShare(server.shareName) as DiskShare

                val smbPath = nasPath.replace("/", "\\")

                share.list(smbPath)
                    .filter { entry ->
                        entry.fileName != "." &&
                                entry.fileName != ".." &&
                                !entry.fileName.startsWith(".")
                    }
                    .map { entry ->
                        // ★ ビットマスクでディレクトリ判定（0x10 = FILE_ATTRIBUTE_DIRECTORY）
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

    suspend fun downloadFile(server: NasServer, nasPath: String, destFile: File): File =
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

                destFile.parentFile?.mkdirs()
                smbFile.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
                destFile
            } finally {
                runCatching { client.close() }
            }
        }
}