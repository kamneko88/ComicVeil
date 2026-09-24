package com.kamneko88.comicveil.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * バックアップの保存先の種類。
 * 今回実装するのは LOCAL_FILE のみ。将来 Google Drive 等を追加する場合はここに列挙を足し、
 * [BackupDestination] を実装するクラスを追加する（UIの「準備中」項目は実装されるまで出さない）。
 */
enum class BackupDestinationType(val label: String) {
    LOCAL_FILE("この端末（ファイル）")
}

/** バックアップの読み書き先を抽象化するインターフェース */
interface BackupDestination {
    val type: BackupDestinationType
    suspend fun write(context: Context, uri: Uri, bytes: ByteArray)
    suspend fun read(context: Context, uri: Uri): ByteArray
}

/**
 * この端末上のファイル（SAFのCreateDocument/OpenDocumentで得たUri）への読み書き。
 */
class LocalFileBackupDestination : BackupDestination {

    override val type = BackupDestinationType.LOCAL_FILE

    override suspend fun write(context: Context, uri: Uri, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("保存先を開けませんでした")
            stream.use { it.write(bytes) }
        }
    }

    override suspend fun read(context: Context, uri: Uri): ByteArray {
        return withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("ファイルを開けませんでした")
            stream.use { it.readBytes() }
        }
    }
}
