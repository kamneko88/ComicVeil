package com.kamneko88.comicveil.ui.transfer

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.SmbRepository
import com.kamneko88.comicveil.data.nas.TransferItem
import com.kamneko88.comicveil.data.nas.TransferStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TransferViewModel(application: Application) : AndroidViewModel(application) {

    private val smbRepository = SmbRepository()

    private val downloadsFolder = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    )

    /**
     * 全転送アイテムのリスト（アクティブ＋履歴を一元管理）
     * UIはここをフィルタリングして「転送中タブ」「履歴タブ」に振り分ける
     */
    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    /** 転送中・待機中のアイテム（転送中タブ用）*/
    val activeItems: StateFlow<List<TransferItem>>
        get() = _items  // UI側でフィルタして使う

    /** 現在実行中のダウンロードJob */
    private var currentJob: Job? = null

    // ─── キュー操作 ──────────────────────────────────────────────────────

    /**
     * ファイルをダウンロードキューに追加する
     *
     * @param fileItem  NASのファイルアイテム
     * @param isStreaming true=STRモード（cacheDir）/ false=DLモード（Downloads/ComicVeil）
     */
    fun enqueue(fileItem: FileItem, isStreaming: Boolean = false): TransferItem {
        val server = fileItem.nasServer ?: error("NASサーバー情報がありません")
        val ext    = fileItem.name.substringAfterLast(".")

        val destFile = if (isStreaming) {
            val dir = File(getApplication<Application>().cacheDir, "nas_cache")
            File(dir, "nas_${fileItem.nasPath.hashCode()}.$ext")
        } else {
            val dir = File(downloadsFolder, "ComicVeil")
            File(dir, fileItem.name)
        }

        val item = TransferItem(
            fileName = fileItem.name,
            nasPath  = fileItem.nasPath,
            server   = server,
            destPath = destFile.absolutePath
        )

        _items.value = _items.value + item
        processQueue()
        return item
    }

    /**
     * 履歴のアイテムを再転送する
     * 元の履歴レコードはそのまま残し、新しいアイテムをキューに追加する
     */
    fun retry(item: TransferItem) {
        val newItem = TransferItem(
            fileName = item.fileName,
            nasPath  = item.nasPath,
            server   = item.server,
            destPath = item.destPath
        )
        _items.value = _items.value + newItem
        processQueue()
    }

    /**
     * 現在転送中のアイテムをキャンセルする
     * → Jobをキャンセル → キューの次のアイテムへ
     */
    fun cancelCurrent() {
        currentJob?.cancel()
    }

    /**
     * 待機中のアイテムをキャンセルする（キューから削除）
     */
    fun cancelWaiting(itemId: String) {
        _items.value = _items.value.map { item ->
            if (item.id == itemId && item.status == TransferStatus.WAITING) {
                item.copy(
                    status      = TransferStatus.CANCELLED,
                    completedAt = System.currentTimeMillis()
                )
            } else item
        }
    }

    /**
     * 全てキャンセル
     * 転送中を止め、待機中を全てキャンセル済みにする
     */
    fun cancelAll() {
        currentJob?.cancel()
        _items.value = _items.value.map { item ->
            if (item.status == TransferStatus.WAITING) {
                item.copy(
                    status      = TransferStatus.CANCELLED,
                    completedAt = System.currentTimeMillis()
                )
            } else item
        }
    }

    /** 履歴を全て削除（アクティブなアイテムは残す）*/
    fun clearHistory() {
        _items.value = _items.value.filter { !it.isFinished }
    }

    // ─── キュー処理 ──────────────────────────────────────────────────────

    /**
     * キューを処理する
     * 転送中のものがなければ、次のWAITINGアイテムを処理開始する
     */
    private fun processQueue() {
        // すでに転送中なら何もしない
        if (_items.value.any { it.status == TransferStatus.TRANSFERRING }) return

        // 次のWAITINGアイテムを取得
        val next = _items.value.firstOrNull { it.status == TransferStatus.WAITING } ?: return

        currentJob = viewModelScope.launch {
            // TRANSFERRING に更新
            updateItem(next.id) { it.copy(status = TransferStatus.TRANSFERRING) }

            try {
                val destFile = File(next.destPath)

                // すでにファイルが存在する場合はスキップ（キャッシュ済み）
                if (destFile.exists() && destFile.length() > 0) {
                    updateItem(next.id) {
                        it.copy(
                            status        = TransferStatus.COMPLETED,
                            downloadedBytes = it.totalBytes.coerceAtLeast(0),
                            completedAt   = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }

                smbRepository.downloadFile(
                    server   = next.server,
                    nasPath  = next.nasPath,
                    destFile = destFile,
                    onProgress = { downloaded, total ->
                        updateItem(next.id) {
                            it.copy(
                                downloadedBytes = downloaded,
                                totalBytes      = total
                            )
                        }
                    }
                )

                // 完了
                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // キャンセル：不完全ファイルを削除
                runCatching { File(next.destPath).delete() }
                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.CANCELLED,
                        completedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                // エラー：不完全ファイルを削除
                runCatching { File(next.destPath).delete() }
                updateItem(next.id) {
                    it.copy(
                        status       = TransferStatus.ERROR,
                        errorMessage = e.message,
                        completedAt  = System.currentTimeMillis()
                    )
                }
            } finally {
                currentJob = null
                // 次のキューを処理
                processQueue()
            }
        }
    }

    /** 指定IDのアイテムを更新するヘルパー */
    private fun updateItem(id: String, update: (TransferItem) -> TransferItem) {
        _items.value = _items.value.map { if (it.id == id) update(it) else it }
    }
}