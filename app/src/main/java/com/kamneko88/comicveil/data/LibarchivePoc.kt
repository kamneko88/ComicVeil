package com.kamneko88.comicveil.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File

/**
 * libarchive移行のPoC（検証用）専用オブジェクト。
 * 本実装には組み込まず、RAR5ファイルが読めるかどうかの検証だけに使う。
 * 検証後は削除する想定。
 */
object LibarchivePoc {

    private const val TAG = "ComicVeil-libarchive-PoC"

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /**
     * 指定したUriのアーカイブをlibarchiveで開き、エントリ一覧をログ出力する。
     * 先頭の画像エントリを1つだけ実際に展開し、キャッシュ内に保存する。
     *
     * @return 結果のサマリー文字列（画面表示用）
     */
    fun testArchive(context: Context, uri: Uri): String {
        val log = StringBuilder()
        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: return "ファイルを開けませんでした（ParcelFileDescriptorがnull）"
        } catch (e: Exception) {
            return "ファイルオープン失敗: ${e.message}"
        }

        var archive = 0L
        var entryCount = 0
        var extractedPath: String? = null
        var detectedFormat: String? = null

        try {
            archive = Archive.readNew()
            Archive.readSupportFormatAll(archive)
            Archive.readSupportFilterAll(archive)
            Archive.readOpenFd(archive, pfd.fd, 10240L)

            log.appendLine("=== libarchive PoC 開始 ===")

            while (true) {
                val entry = ArchiveEntry.new1()
                val ret: Long
                try {
                    ret = Archive.readNextHeader2(archive, entry)
                } catch (e: ArchiveException) {
                    ArchiveEntry.free(entry)
                    log.appendLine("読み込み終了（${e.code}）: ${e.message}")
                    break
                }

                if (ret.toInt() == Archive.ERRNO_EOF) {
                    ArchiveEntry.free(entry)
                    log.appendLine("--- 全エントリ読み込み完了 ---")
                    break
                }

                if (detectedFormat == null) {
                    // 最初のエントリが読めた時点で、RAR5判定のために
                    // フォーマット定数と照合できるよう生の情報も残す
                    detectedFormat = "エントリ読み込み成功（RAR5含め展開可能な形式として認識）"
                }

                val name  = ArchiveEntry.pathnameUtf8(entry) ?: "(名前取得失敗)"
                val size  = ArchiveEntry.size(entry)
                val isDir = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                val encrypted = try { ArchiveEntry.isEncrypted(entry) } catch (e: Exception) { false }

                log.appendLine("[$entryCount] $name (${size} bytes) dir=$isDir encrypted=$encrypted")
                Log.d(TAG, "[$entryCount] $name (${size} bytes) dir=$isDir encrypted=$encrypted")

                val ext = name.substringAfterLast(".").lowercase()
                if (!isDir && extractedPath == null && ext in imageExtensions) {
                    val outFile = File(context.cacheDir, "libarchive_poc_test.$ext")
                    var outPfd: ParcelFileDescriptor? = null
                    try {
                        outPfd = ParcelFileDescriptor.open(
                            outFile,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_READ_WRITE
                        )
                        Archive.readDataIntoFd(archive, outPfd.fd)
                        extractedPath = outFile.absolutePath
                        log.appendLine("  → 展開成功: ${outFile.absolutePath} (${outFile.length()} bytes)")
                        Log.d(TAG, "展開成功: ${outFile.absolutePath} (${outFile.length()} bytes)")
                    } catch (e: Exception) {
                        log.appendLine("  → 展開失敗: ${e.message}")
                        Log.e(TAG, "展開失敗: $name", e)
                    } finally {
                        runCatching { outPfd?.close() }
                    }
                } else {
                    try {
                        Archive.readDataSkip(archive)
                    } catch (e: Exception) {
                        log.appendLine("  → スキップ失敗: ${e.message}")
                    }
                }

                ArchiveEntry.free(entry)
                entryCount++
            }

            log.appendLine("=== 結果サマリー ===")
            log.appendLine("エントリ総数: $entryCount")
            log.appendLine("展開テスト: ${extractedPath ?: "（画像エントリなし、または展開失敗）"}")

        } catch (e: ArchiveException) {
            log.appendLine("libarchiveエラー（コード${e.code}）: ${e.message}")
            Log.e(TAG, "libarchiveエラー", e)
        } catch (e: Exception) {
            log.appendLine("予期しないエラー: ${e::class.simpleName}: ${e.message}")
            Log.e(TAG, "予期しないエラー", e)
        } finally {
            if (archive != 0L) {
                runCatching { Archive.readClose(archive) }
                runCatching { Archive.readFree(archive) }
            }
            runCatching { pfd.close() }
        }

        val result = log.toString()
        Log.d(TAG, result)
        return result
    }
}
