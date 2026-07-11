package com.kamneko88.comicveil.ui.transfer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.data.nas.SmbRepository
import com.kamneko88.comicveil.data.nas.TransferItem
import com.kamneko88.comicveil.data.nas.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TransferViewModel(application: Application) : AndroidViewModel(application) {

    private val smbRepository = SmbRepository()
    private val appPrefs       = AppPrefs(application)

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
     * @param isStreaming true=STRモード（cacheDir）/ false=DLモード（DL保存先設定に従う）
     */
    fun enqueue(fileItem: FileItem, isStreaming: Boolean = false): TransferItem {
        val server = fileItem.nasServer ?: error("NASサーバー情報がありません")
        val ext    = fileItem.name.substringAfterLast(".")

        // 実際のダウンロード先は常にアプリキャッシュ内の作業用パス
        // （DL保存先がSAFフォルダの場合、ダウンロード完了後にSAF側へコピーする）
        val destFile: File
        var safTargetUri: String? = null

        if (isStreaming) {
            val dir = File(getApplication<Application>().cacheDir, "nas_cache")
            destFile = File(dir, "nas_${fileItem.nasPath.hashCode()}.$ext")
        } else {
            when (appPrefs.downloadFolderType) {
                AppPrefs.DownloadFolderType.APP_FOLDER -> {
                    destFile = appPrefs.resolveDownloadFolder(getApplication()).also { it.mkdirs() }
                        .let { File(it, fileItem.name) }
                }
                AppPrefs.DownloadFolderType.SAF_FOLDER -> {
                    val dir = File(getApplication<Application>().cacheDir, "dl_work")
                    dir.mkdirs()
                    destFile = File(dir, "${fileItem.nasPath.hashCode()}_${fileItem.name}")
                    safTargetUri = appPrefs.downloadFolderSafUri
                }
            }
        }

        val item = TransferItem(
            fileName     = fileItem.name,
            nasPath      = fileItem.nasPath,
            server       = server,
            destPath     = destFile.absolutePath,
            safTargetUri = safTargetUri
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
            fileName     = item.fileName,
            nasPath      = item.nasPath,
            server       = item.server,
            destPath     = item.destPath,
            safTargetUri = item.safTargetUri
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

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            // TRANSFERRING に更新
            updateItem(next.id) { it.copy(status = TransferStatus.TRANSFERRING) }
            Log.d("ComicVeil", "転送開始: ${next.fileName} (saf=${next.safTargetUri != null})")

            try {
                val destFile = File(next.destPath)

                // すでにファイルが存在する場合はスキップ（キャッシュ済み）
                if (destFile.exists() && destFile.length() > 0 && next.safTargetUri == null) {
                    updateItem(next.id) {
                        it.copy(
                            status        = TransferStatus.COMPLETED,
                            downloadedBytes = it.totalBytes.coerceAtLeast(0),
                            completedAt   = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }

                if (!(destFile.exists() && destFile.length() > 0)) {
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
                    Log.d("ComicVeil", "DL完了: ${next.fileName} (${destFile.length()} bytes)")
                }

                // DL保存先がSAFフォルダの場合、ダウンロード済みファイルをSAF側へコピーする
                if (next.safTargetUri != null) {
                    Log.d("ComicVeil", "SAFコピー開始: ${next.fileName}")
                    copyToSafFolder(destFile, next.safTargetUri, next.fileName)
                    runCatching { destFile.delete() }
                    Log.d("ComicVeil", "SAFコピー完了: ${next.fileName}")
                }

                // 完了
                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                }
                Log.d("ComicVeil", "転送完了: ${next.fileName}")

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

    /** ダウンロード済みファイルを、SAFで許可されたフォルダへコピーする */
    private fun copyToSafFolder(sourceFile: File, targetUriString: String, fileName: String) {
        val context = getApplication<Application>()
        val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(targetUriString))
            ?: error("保存先フォルダにアクセスできません")

        // 同名ファイルがあれば削除してから作成（上書き）
        treeDoc.findFile(fileName)?.delete()

        val mimeType = "application/octet-stream"
        val newDoc = treeDoc.createFile(mimeType, fileName)
            ?: error("保存先にファイルを作成できません")

        context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("保存先への書き込みに失敗しました")
    }

    /** 指定IDのアイテムを更新するヘルパー */
    private fun updateItem(id: String, update: (TransferItem) -> TransferItem) {
        _items.value = _items.value.map { if (it.id == id) update(it) else it }
    }
}
