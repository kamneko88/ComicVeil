package com.kamneko88.comicveil.ui.lock

/**
 * アプリロックの「今のフォアグラウンドセッションで解除済みか」を保持するランタイム状態。
 * プロセスが生きている間だけ有効（SharedPreferencesには保存しない）。
 * バックグラウンドに一度でも行ったらfalseに戻し、再度ロック画面を出す
 * （MainActivity側のProcessLifecycleOwner監視でfalseに戻す）。
 */
object AppLockState {
    var isUnlocked: Boolean = false
}
