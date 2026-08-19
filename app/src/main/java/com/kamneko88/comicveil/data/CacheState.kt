package com.kamneko88.comicveil.data

/**
 * キャッシュファイルが「完全に落ちきっているか」を判定する。
 *
 * NASバッジ表示（[com.kamneko88.comicveil.ui.home.HomeViewModel.isNasCached]）と
 * STR再生時の判定（[com.kamneko88.comicveil.ui.home.HomeViewModel]内 openNasComicStr）が
 * 別ルールで実装され表示と実態が食い違っていた問題（v0.27.0のZIPストリーミング導入以降、
 * ダウンロード途中のファイルもキャッシュに残るようになった）を受けて、判定ロジックを一本化する。
 */
fun isFullyCached(cachedLength: Long, expectedSize: Long): Boolean =
    cachedLength > 0 && (expectedSize <= 0 || cachedLength >= expectedSize)
