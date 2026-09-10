package com.kamneko88.comicveil.data.nas

import android.net.Uri
import java.util.UUID

/** 転送アイテムのステータス */
enum class TransferStatus {
    /** 待機中（キュー内で順番待ち） */
    WAITING,
    /** 転送中 */
    TRANSFERRING,
    /** 完了 */
    COMPLETED,
    /** キャンセル済み */
    CANCELLED,
    /** エラー */
    ERROR
}

/** 転送元の種類（NASサーバー／外部ファイル）を表す */
sealed class TransferSource {
    data class Nas(val server: NasServer, val nasPath: String) : TransferSource()
    data class External(val uri: Uri) : TransferSource()
}

/**
 * 転送アイテム1件分のデータ
 *
 * @param id          一意ID（自動生成）
 * @param fileName    ファイル名（保存先での実際のファイル名でもある。表示にも使う）
 * @param source      転送元（NASサーバー／外部ファイル）
 * @param destPath    保存先のローカルパス（アプリキャッシュ内の作業用パス）
 * @param safTargetUri DL保存先がSAFフォルダの場合、コピー先のツリーURI（不要ならnull）
 * @param isStreaming  ストリーミング再生用の一時DLか（true=本を閉じたら中止して破棄してよい）
 * @param totalBytes  ファイルサイズ（不明時は -1）
 * @param downloadedBytes ダウンロード済みバイト数
 * @param status      転送ステータス
 * @param errorMessage エラー時のメッセージ
 * @param createdAt   キューに追加された時刻（ミリ秒）
 * @param completedAt 完了・キャンセル・エラーになった時刻（ミリ秒）
 */
data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val source: TransferSource,
    val destPath: String,
    val safTargetUri: String? = null,
    val isStreaming: Boolean = false,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.WAITING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    /** 転送元を示す短い表示名（履歴の行などで使う） */
    val sourceLabel: String
        get() = when (source) {
            is TransferSource.Nas      -> source.server.displayName
            is TransferSource.External -> "外部ファイル"
        }

    /** 転送中であることを示す文言（NAS由来か外部取り込みかで言い回しを変える） */
    val activityLabel: String
        get() = when (source) {
            is TransferSource.Nas      -> "${source.server.displayName} からダウンロード中"
            is TransferSource.External -> "外部ファイルを取り込み中"
        }

    /** 待機中であることを示す文言 */
    val waitingLabel: String
        get() = when (source) {
            is TransferSource.Nas      -> "${source.server.displayName} からのダウンロード待機中"
            is TransferSource.External -> "外部ファイルの取り込み待機中"
        }
    /** 進捗率 0.0f〜1.0f（ファイルサイズ不明時は null）*/
    val fraction: Float?
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else null

    /** 表示用の進捗テキスト（例：「12.3 MB / 45.6 MB」）*/
    val progressText: String
        get() = when {
            totalBytes > 0 -> "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
            else           -> formatBytes(downloadedBytes)
        }

    /** 転送が終了した状態か（完了・キャンセル・エラー）*/
    val isFinished: Boolean
        get() = status == TransferStatus.COMPLETED ||
                status == TransferStatus.CANCELLED ||
                status == TransferStatus.ERROR

    /** 履歴から再転送できる状態か（キャンセル・エラー）*/
    val canRetry: Boolean
        get() = status == TransferStatus.CANCELLED || status == TransferStatus.ERROR

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
        else                    -> "$bytes B"
    }
}
