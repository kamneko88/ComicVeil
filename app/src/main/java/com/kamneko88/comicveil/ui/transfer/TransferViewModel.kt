package com.kamneko88.comicveil.ui.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.nas.TransferItem
import com.kamneko88.comicveil.data.nas.TransferManager
import kotlinx.coroutines.flow.StateFlow

/**
 * 転送画面用のViewModel
 *
 * 実処理・キューの実体は TransferManager（シングルトン）が持つ。
 * このクラスはUIとTransferManagerをつなぐ薄い委譲層。
 *
 * 【なぜ委譲するのか】
 * 転送処理をViewModelが直接持っていると、画面破棄や他アプリへの切り替えで
 * ViewModelが消えたときに転送も止まってしまうため。
 * TransferManager＋TransferService（フォアグラウンドサービス）に処理を任せることで、
 * 他アプリ使用中・画面オフ中でも転送が継続される。
 */
class TransferViewModel(application: Application) : AndroidViewModel(application) {

    init {
        TransferManager.init(application)
    }

    /** 全転送アイテムのリスト（アクティブ＋履歴を一元管理） */
    val items: StateFlow<List<TransferItem>> = TransferManager.items

    /** 転送中・待機中のアイテム（UI側でフィルタして使う） */
    val activeItems: StateFlow<List<TransferItem>>
        get() = TransferManager.items

    /** ファイルをダウンロードキューに追加する */
    fun enqueue(fileItem: FileItem, isStreaming: Boolean = false): TransferItem =
        TransferManager.enqueue(fileItem, isStreaming)

    /** 履歴のアイテムを再転送する */
    fun retry(item: TransferItem) = TransferManager.retry(item)

    /** 現在転送中のアイテムをキャンセルする */
    fun cancelCurrent() = TransferManager.cancelCurrent()

    /** 待機中のアイテムをキャンセルする */
    fun cancelWaiting(itemId: String) = TransferManager.cancelWaiting(itemId)

    /** 全てキャンセル */
    fun cancelAll() = TransferManager.cancelAll()

    /** 履歴を全て削除（アクティブなアイテムは残す） */
    fun clearHistory() = TransferManager.clearHistory()
}
