package com.kamneko88.comicveil.data.nas

import android.content.Context
import com.kamneko88.comicveil.data.CacheDirInfo
import com.kamneko88.comicveil.data.calcDirSize
import com.kamneko88.comicveil.data.selectDirsToEvict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NASストリーミングキャッシュ（ダウンロードしながら読むSTRモード用の一時ファイル）の保存先・
 * 上限管理をまとめたオブジェクト。
 *
 * 【なぜcacheDirを使わないか】
 * 以前は Android の cacheDir（`cacheDir/nas_cache`）を使っていたが、アプリごとのキャッシュ容量が
 * 上限（実機で89〜143MBと可変）を超えると、システム（installd）が予告なく中身を削除する。
 * 数百MB級のNAS本を落とした直後に消され、開く前に「非対応のファイル形式」エラーになる事故が
 * 実際に起きた。そのため、アプリが自分で容量管理する外部ストレージ領域
 * （`getExternalFilesDir(null)/nas_stream_cache`）を使う。DLモードの保存先（Comics）とは
 * 完全に別の並列フォルダであり、混在させない。
 *
 * 【ディレクトリ構成】
 * 本1冊＝1ディレクトリ（`nas_stream_cache/nas_<nasPathのハッシュ値>/`）に、
 * 実ファイル・ストリーミング用サイドカー（.stream）・完了マーカー（complete）をまとめて持つ。
 * 上限超過時の削除は、archive_pages（ページキャッシュ）と同じくディレクトリ単位で行う。
 */
object NasStreamCache {

    private const val DIR_NAME = "nas_stream_cache"
    private const val COMPLETE_MARKER = "complete"

    fun baseDir(context: Context): File = File(context.getExternalFilesDir(null), DIR_NAME)

    /** 本1冊ぶんのディレクトリ（nasPathのハッシュ値で一意） */
    fun bookDir(context: Context, nasPath: String): File =
        File(baseDir(context), "nas_${nasPath.hashCode()}")

    /** ダウンロード先の実ファイル */
    fun destFile(context: Context, nasPath: String, ext: String): File =
        File(bookDir(context, nasPath), "nas_${nasPath.hashCode()}.$ext")

    /** ダウンロード完了時に立てる完了マーカー（LRU判定に使う更新日時を持つ） */
    fun markComplete(bookDir: File) {
        runCatching {
            bookDir.mkdirs()
            File(bookDir, COMPLETE_MARKER).writeText("")
        }
    }

    /**
     * キャッシュ済みの本を開くたびに呼ぶ。完了マーカーの更新日時を今に進めて、
     * 古い順の自動削除から外す（archive_pagesの「complete」マーカーと同じ仕組み）。
     * setLastModifiedがfalseを返す端末があるため、失敗時は書き直して更新日時を進める。
     */
    fun touchComplete(bookDir: File) {
        val marker = File(bookDir, COMPLETE_MARKER)
        val touched = runCatching { marker.setLastModified(System.currentTimeMillis()) }.getOrDefault(false)
        if (!touched) runCatching { marker.writeText("") }
    }

    fun isComplete(bookDir: File): Boolean = File(bookDir, COMPLETE_MARKER).exists()

    fun totalSize(context: Context): Long = calcDirSize(baseDir(context))

    /** 全て削除する（設定画面の手動削除ボタン用） */
    fun clearAll(context: Context): Long {
        val dir = baseDir(context)
        val totalBytes = calcDirSize(dir)
        dir.deleteRecursively()
        return totalBytes
    }

    /**
     * 上限を超えていれば、古い順に本のディレクトリごと削除する。
     * 呼び出し箇所：①起動時 ②本を閉じたとき ③上限変更時。
     * 「古い」の判定はarchive_pagesと同じくCacheDirInfoの型を共有し、
     * 選別ロジック（selectDirsToEvict）もそのまま再利用する。
     */
    suspend fun evictIfNeeded(context: Context, limitBytes: Long) {
        withContext(Dispatchers.IO) {
            val dir = baseDir(context)
            val entries = dir.listFiles { f -> f.isDirectory }?.map { bookDir ->
                val marker = File(bookDir, COMPLETE_MARKER)
                val lastModified = if (marker.exists()) marker.lastModified() else bookDir.lastModified()
                CacheDirInfo(bookDir.name, calcDirSize(bookDir), lastModified)
            } ?: emptyList()

            selectDirsToEvict(entries, limitBytes).forEach { name ->
                File(dir, name).deleteRecursively()
            }
        }
    }
}
