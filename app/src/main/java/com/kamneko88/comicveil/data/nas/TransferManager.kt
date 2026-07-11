package com.kamneko88.comicveil.data.nas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.service.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 転送キューの実体（アプリ全体で1つだけ存在するシングルトン）
 *
 * 【なぜViewModelから切り出したか】
 * 転送処理をViewModelが持っていると、画面を離れる・他アプリに切り替えるなどで
 * ViewModelが破棄された際に転送も一緒に止まってしまう。
 * ここに実体を置き、TransferService（フォアグラウンドサービス）と組み合わせることで、
 * 他アプリ使用中・画面オフ中でも転送を継続できるようにしている。
 *
 * ViewModelはこのクラスへ処理を委譲するだけの薄い層になる。
 */
object TransferManager {

    private const val TAG = "ComicVeil"

    private val smbRepository = SmbRepository()

    /** アプリのライフサイクルに紐づく独自スコープ（ViewModelの破棄に影響されない） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var appPrefs: AppPrefs
    private var initialized = false

    /** 全転送アイテム（アクティブ＋履歴を一元管理。UI側でフィルタして表示） */
    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    /** 現在実行中のダウンロードJob */
    private var currentJob: Job? = null

    /** 転送中・待機中の作業が残っているか（サービスの継続判定に使う） */
    val hasActiveWork: Boolean
        get() = _items.value.any {
            it.status == TransferStatus.WAITING || it.status == TransferStatus.TRANSFERRING
        }

    /** 通知に表示すべき現在の対象（転送中を優先、なければ待機中の先頭） */
    val currentItem: TransferItem?
        get() = _items.value.firstOrNull { it.status == TransferStatus.TRANSFERRING }
            ?: _items.value.firstOrNull { it.status == TransferStatus.WAITING }

    /** 初期化（Applicationコンテキストを保持する。何度呼んでも安全） */
    fun init(context: Context) {
        if (initialized) return
        appContext  = context.applicationContext
        appPrefs    = AppPrefs(appContext)
        initialized = true
    }

    // ─── キュー操作 ──────────────────────────────────────────────────────

    /**
     * ファイルをダウンロードキューに追加する
     *
     * @param fileItem    NASのファイルアイテム
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
            val dir = File(appContext.cacheDir, "nas_cache")
            destFile = File(dir, "nas_${fileItem.nasPath.hashCode()}.$ext")
        } else {
            when (appPrefs.downloadFolderType) {
                AppPrefs.DownloadFolderType.APP_FOLDER -> {
                    destFile = appPrefs.resolveDownloadFolder(appContext).also { it.mkdirs() }
                        .let { File(it, fileItem.name) }
                }
                AppPrefs.DownloadFolderType.SAF_FOLDER -> {
                    val dir = File(appContext.cacheDir, "dl_work")
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
        ensureServiceRunning()
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
        ensureServiceRunning()
        processQueue()
    }

    /** 現在転送中のアイテムをキャンセルする → キューの次のアイテムへ */
    fun cancelCurrent() {
        currentJob?.cancel()
    }

    /** 待機中のアイテムをキャンセルする（キューから削除） */
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

    /** 全てキャンセル（転送中を止め、待機中を全てキャンセル済みにする） */
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

    /** 履歴を全て削除（アクティブなアイテムは残す） */
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

        currentJob = scope.launch {
            updateItem(next.id) { it.copy(status = TransferStatus.TRANSFERRING) }
            Log.d(TAG, "転送開始: ${next.fileName} (saf=${next.safTargetUri != null})")

            try {
                val destFile = File(next.destPath)

                // すでにファイルが存在する場合はスキップ（キャッシュ済み）
                if (destFile.exists() && destFile.length() > 0 && next.safTargetUri == null) {
                    updateItem(next.id) {
                        it.copy(
                            status          = TransferStatus.COMPLETED,
                            downloadedBytes = it.totalBytes.coerceAtLeast(0),
                            completedAt     = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }

                if (!(destFile.exists() && destFile.length() > 0)) {
                    // 進捗更新の間引き：％が変わった時だけUIへ反映する。
                    // （毎チャンク更新するとリスト全体の再構築・再描画が頻発し、
                    //   非力な端末で描画が詰まって進捗バーが固まる原因になる）
                    var lastPercent = -1

                    smbRepository.downloadFile(
                        server   = next.server,
                        nasPath  = next.nasPath,
                        destFile = destFile,
                        onProgress = { downloaded, total ->
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateItem(next.id) {
                                    it.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes      = total
                                    )
                                }
                            }
                        }
                    )
                    Log.d(TAG, "DL完了: ${next.fileName} (${destFile.length()} bytes)")
                }

                // DL保存先がSAFフォルダの場合、ダウンロード済みファイルをSAF側へコピーする
                if (next.safTargetUri != null) {
                    Log.d(TAG, "SAFコピー開始: ${next.fileName}")
                    copyToSafFolder(destFile, next.safTargetUri, next.fileName)
                    runCatching { destFile.delete() }
                    Log.d(TAG, "SAFコピー完了: ${next.fileName}")
                }

                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                }
                Log.d(TAG, "転送完了: ${next.fileName}")

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
                Log.w(TAG, "転送エラー: ${next.fileName} / ${e.message}")
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
        val treeDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(targetUriString))
            ?: error("保存先フォルダにアクセスできません")

        // 同名ファイルがあれば削除してから作成（上書き）
        treeDoc.findFile(fileName)?.delete()

        val mimeType = "application/octet-stream"
        val newDoc = treeDoc.createFile(mimeType, fileName)
            ?: error("保存先にファイルを作成できません")

        appContext.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("保存先への書き込みに失敗しました")
    }

    /** 指定IDのアイテムを更新するヘルパー */
    private fun updateItem(id: String, update: (TransferItem) -> TransferItem) {
        _items.value = _items.value.map { if (it.id == id) update(it) else it }
    }

    /**
     * フォアグラウンドサービスを起動する（未起動なら）
     * これにより、他アプリ使用中・画面オフ中でも転送が継続される
     */
    private fun ensureServiceRunning() {
        if (!initialized) return
        val intent = Intent(appContext, TransferService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure {
            Log.w(TAG, "転送サービスの起動に失敗: ${it.message}")
        }
    }
}
