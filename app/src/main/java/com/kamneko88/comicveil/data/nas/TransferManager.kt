package com.kamneko88.comicveil.data.nas

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.ZipStreamSupport
import com.kamneko88.comicveil.data.isFullyCached
import com.kamneko88.comicveil.data.resolveUniqueFileName
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
 *
 * 【転送元について】
 * NAS(SMB)のみを転送元として扱う。保存先は常にアプリ専用フォルダ
 * （AppPrefs.getAppFolder()）固定。
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
        File(appContext.cacheDir, "dl_work").deleteRecursively()
        // 旧バージョンで使っていたcacheDir内のNASストリーミングキャッシュの残骸を一度だけ掃除する。
        // （保存先をgetExternalFilesDir配下のnas_stream_cacheへ移行したため、もう使われない）
        File(appContext.cacheDir, "nas_cache").deleteRecursively()
    }

    // ─── キュー操作 ──────────────────────────────────────────────────────

    /**
     * ファイルをダウンロードキューに追加する。
     *
     * @param fileItem    NASのファイルアイテム
     * @param isStreaming true=STRモード（nas_stream_cache／getExternalFilesDir）/
     *                    false=DLモード（常にアプリ専用フォルダへ保存）
     */
    fun enqueue(fileItem: FileItem, isStreaming: Boolean = false): TransferItem {
        val source = TransferSource.Nas(
            server  = fileItem.nasServer ?: error("NASサーバー情報がありません"),
            nasPath = fileItem.nasPath
        )

        val ext = fileItem.name.substringAfterLast(".")

        // ダウンロード先はモードごとに異なる。
        // ・STR：getExternalFilesDir配下のnas_stream_cache（本1冊＝1ディレクトリ。上限・LRU削除の対象）
        // ・DL：アプリ専用フォルダ（Comics）へ直接保存（そのまま最終的な保存先になる）
        //
        // DLでは、保存先に同名ファイルが既にあっても絶対に上書きしない。
        // 常に連番を付けた別名で保存する（ここで1回だけ名前解決する。転送中・完了済みには影響しない）。
        val destFile: File
        var resolvedName = fileItem.name

        if (isStreaming) {
            destFile = NasStreamCache.destFile(appContext, source.nasPath, ext)
        } else {
            val dir = appPrefs.resolveDownloadFolder(appContext).also { it.mkdirs() }
            resolvedName = resolveUniqueFileName(fileItem.name, existingAppFolderNames(dir))
            destFile = File(dir, resolvedName)
        }

        val item = TransferItem(
            fileName    = resolvedName,
            source      = source,
            destPath    = destFile.absolutePath,
            isStreaming = isStreaming,
            totalBytes  = fileItem.size
        )

        _items.value = _items.value + item
        ensureServiceRunning()
        processQueue()
        return item
    }

    /**
     * 履歴のアイテムを再転送する。
     * 元の履歴レコードはそのまま残し、新しいアイテムをキューに追加する。
     */
    fun retry(item: TransferItem) {
        val newItem = TransferItem(
            fileName   = item.fileName,
            source     = item.source,
            destPath   = item.destPath,
            totalBytes = item.totalBytes
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

    /**
     * ストリーミング再生用のダウンロードを中止する（本を閉じたときに呼ぶ）。
     *
     * ユーザーが意図的に保存しているDLモードの転送は対象外（isStreaming=falseは無視）。
     * 中途半端なストリーミングキャッシュ（ZIP本体・サイドカー）は再利用できないので破棄する。
     * 対象が無ければ何もしない（ローカル本を閉じたときなどは安全に素通り）。
     *
     * @param destPath ビューワーが読んでいたキャッシュZIPのパス（＝ストリーミングDLの保存先）
     */
    fun cancelStreamingByPath(destPath: String) {
        val item = _items.value.firstOrNull {
            it.isStreaming && it.destPath == destPath &&
                (it.status == TransferStatus.TRANSFERRING || it.status == TransferStatus.WAITING)
        }
        if (item != null) {
            if (item.status == TransferStatus.TRANSFERRING) {
                // 実行中のDLを止める。CancellationExceptionハンドラが不完全ファイルを削除する
                currentJob?.cancel()
            } else {
                // 待機中：キューから外して不完全ファイルを削除
                updateItem(item.id) {
                    it.copy(status = TransferStatus.CANCELLED, completedAt = System.currentTimeMillis())
                }
                deleteIncompleteDest(item)
            }
            Log.d(TAG, "ストリーミングDLを中止: ${item.fileName}")
        }
        // サイドカー(.stream)も破棄しておく（次に開いたとき確実に最初からやり直させる）
        runCatching { ZipStreamSupport.deleteSidecar(File(destPath)) }
    }

    /** 履歴を全て削除（アクティブなアイテムは残す） */
    fun clearHistory() {
        _items.value = _items.value.filter { !it.isFinished }
    }

    // ─── ファイル名重複解決 ──────────────────────────────────────────────

    /** アプリ専用DLフォルダ内で既に使われている名前（ディスク上＋キュー中の未完了アイテム分） */
    private fun existingAppFolderNames(dir: File): Set<String> {
        val onDisk = dir.list()?.toSet() ?: emptySet()
        val pending = _items.value
            .filter { !it.isFinished && !it.isStreaming }
            .filter { File(it.destPath).parentFile?.absolutePath == dir.absolutePath }
            .map { it.fileName }
        return onDisk + pending
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
            Log.d(TAG, "転送開始: ${next.fileName}")

            try {
                val destFile = File(next.destPath)

                // すでにファイルが存在する場合はスキップする。
                // これはDLの重複回避には使わない（enqueue()の時点で名前が一意になっている
                // ため通常はここに到達しない）。STRモードの再開判定・同一アイテムの再試行時に
                // 「前回すでに書き終わっていた」場合のためだけに残している。
                if (destFile.exists() && isFullyCached(destFile.length(), next.totalBytes)) {
                    if (next.isStreaming) NasStreamCache.markComplete(destFile.parentFile!!)
                    updateItem(next.id) {
                        it.copy(
                            status          = TransferStatus.COMPLETED,
                            downloadedBytes = it.totalBytes.coerceAtLeast(0),
                            completedAt     = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }

                if (!(destFile.exists() && isFullyCached(destFile.length(), next.totalBytes))) {
                    // 進捗更新の間引き：％が変わった時だけUIへ反映する。
                    // （毎チャンク更新するとリスト全体の再構築・再描画が頻発し、
                    //   非力な端末で描画が詰まって進捗バーが固まる原因になる）
                    var lastPercent = -1
                    val onProgress: (Long, Long) -> Unit = { downloaded, total ->
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

                    when (val source = next.source) {
                        is TransferSource.Nas -> smbRepository.downloadFile(
                            server     = source.server,
                            nasPath    = source.nasPath,
                            destFile   = destFile,
                            onProgress = onProgress
                        )
                    }
                    Log.d(TAG, "DL完了: ${next.fileName} (${destFile.length()} bytes)")
                }

                if (next.isStreaming) {
                    NasStreamCache.markComplete(destFile.parentFile!!)
                }

                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                }
                Log.d(TAG, "転送完了: ${next.fileName}")

            } catch (e: kotlinx.coroutines.CancellationException) {
                // キャンセル：不完全なファイル（STRなら本のディレクトリごと）を削除。
                deleteIncompleteDest(next)
                updateItem(next.id) {
                    it.copy(
                        status      = TransferStatus.CANCELLED,
                        completedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                // エラー：不完全なファイル（STRなら本のディレクトリごと）を削除。
                Log.w(TAG, "転送エラー: ${next.fileName} / ${e.message}")
                deleteIncompleteDest(next)
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

    /**
     * 不完全な転送先を削除する。STR（ストリーミング）は本のディレクトリごと破棄する
     * （サイドカー等も含めて再利用できないため）。DL（ユーザーが意図的に保存中）はファイルのみ削除する。
     */
    private fun deleteIncompleteDest(item: TransferItem) {
        val destFile = File(item.destPath)
        if (item.isStreaming) {
            runCatching { destFile.parentFile?.deleteRecursively() }
        } else {
            runCatching { destFile.delete() }
        }
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
            // minSdk=26（Android 8.0=O）のため、startForegroundServiceは常に利用可能
            appContext.startForegroundService(intent)
        }.onFailure {
            Log.w(TAG, "転送サービスの起動に失敗: ${it.message}")
        }
    }
}
