package com.kamneko88.comicveil.ui.lock

// バックグラウンド復帰時の再ロック判定ロジック（UI副作用なしの純粋関数）

// 猶予ピリオド（ミリ秒）：バックグラウンドに入ってからこの時間未満で復帰した場合は再ロックしない
const val LOCK_GRACE_PERIOD_MS = 5 * 60 * 1000L // 5分

// backgroundedAt: バックグラウンドに入った時刻（ms epoch、未記録ならnull）
// resumedAt: 復帰した時刻（ms epoch）
// gracePeriodMs: 猶予ミリ秒（既定値はLOCK_GRACE_PERIOD_MS）
// 戻り値: 再ロックすべきならtrue
fun shouldRelockOnResume(
    backgroundedAt: Long?,
    resumedAt: Long,
    gracePeriodMs: Long = LOCK_GRACE_PERIOD_MS
): Boolean {
    if (backgroundedAt == null) return false
    return resumedAt - backgroundedAt >= gracePeriodMs
}
