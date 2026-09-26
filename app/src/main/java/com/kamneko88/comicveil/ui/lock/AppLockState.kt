package com.kamneko88.comicveil.ui.lock

/**
 * アプリロックの「今のフォアグラウンドセッションで解除済みか」を保持するランタイム状態。
 * プロセスが生きている間だけ有効（SharedPreferencesには保存しない）。
 * バックグラウンドに入っている間は[backgroundedAt]に時刻を記録し、フォアグラウンド復帰時に
 * [shouldRelockOnResume]で猶予期間（[LOCK_GRACE_PERIOD_MS]）内かどうかを判定してから
 * 必要な場合のみ[isUnlocked]をfalseに戻す（MainActivity側のProcessLifecycleOwner監視で行う）。
 */
object AppLockState {
    var isUnlocked: Boolean = false
    var backgroundedAt: Long? = null
}
