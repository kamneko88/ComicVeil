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
                        onProgress?.invoke(downloaded, totalSize)
                    }
                }
            }
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

    /**
     * ZIPファイルをストリームで読み込み、ページ画像を逐次書き出す（Progressive Loading）
     * メモリには1ページ分のバイト列しか保持せず、書き出したら即座に破棄する。
     * 最終的な自然順の並びは、全ページ到着後にファイル名のリネームだけで確定する
     * （バイトの再書き込みは行わないため高速）。
     *
     * @param pageDir     ページ画像を保存するディレクトリ（00000.jpg, 00001.jpg...）
     * @param onPageReady ページを書き出すたびに呼ぶコールバック（引数：累計保存ページ数）
     * @param onComplete  全ページ保存完了時に呼ぶコールバック
     */
    suspend fun downloadZipProgressive(
        server: NasServer,
        nasPath: String,
        pageDir: File,
        onPageReady: suspend (savedCount: Int) -> Unit,
        onComplete: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
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

            pageDir.mkdirs()
            val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
            val arrivalNames = mutableListOf<String>()

            // Shift-JIS / UTF-8 両方を試行
            for (cs in listOf("UTF-8", "Shift_JIS")) {
                arrivalNames.clear()
                pageDir.listFiles { f -> f.name.startsWith("incoming_") }?.forEach { it.delete() }

                // 再接続が必要なため毎回ファイルを開き直す
                val smbFile = share.openFile(
                    smbPath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                runCatching {
                    org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
                        smbFile.inputStream, cs, false, true
                    ).use { zis ->
                        var entry = zis.nextZipEntry
                        while (entry != null && isActive) {
                            val name = entry.name
                            val ext  = name.substringAfterLast(".").lowercase()
                            if (!entry.isDirectory && ext in imageExtensions) {
                                val bytes = zis.readBytes()
                                if (bytes.isNotEmpty()) {
                                    // 到着順の一時名で即座に書き出し、バイト列はこの場で破棄（メモリに溜め込まない）
                                    File(pageDir, "incoming_%05d.jpg".format(arrivalNames.size)).writeBytes(bytes)
                                    arrivalNames.add(name)
                                    withContext(Dispatchers.Main) {
                                        onPageReady(arrivalNames.size)
                                    }
                                }
                            }
                            entry = zis.nextZipEntry
                        }
                    }
                }
                if (arrivalNames.isNotEmpty()) break
            }

            // 到着順ファイルを、自然順の最終位置へリネーム（バイトコピーなし・高速）
            val order = arrivalNames.withIndex()
                .sortedWith(compareBy(com.kamneko88.comicveil.data.NaturalOrder.COMPARATOR) { it.value })
            order.forEachIndexed { finalIdx, (arrivalIdx, _) ->
                val src = File(pageDir, "incoming_%05d.jpg".format(arrivalIdx))
                val dst = File(pageDir, "%05d_tmp.jpg".format(finalIdx))
                src.renameTo(dst)
            }
            (arrivalNames.indices).forEach { idx ->
                File(pageDir, "%05d_tmp.jpg".format(idx)).renameTo(File(pageDir, "%05d.jpg".format(idx)))
            }

            // 完了マーカー（ページ数を記録）
            File(pageDir, "complete").writeText(arrivalNames.size.toString())

            withContext(Dispatchers.Main) {
                onComplete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        } finally {
            runCatching { client.close() }
        }
    }
}
