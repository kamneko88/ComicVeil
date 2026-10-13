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

/**
 * NASの非圧縮画像フォルダ内の画像1枚が、キャッシュ済みと判定できるかを返す。
 *
 * 画像フォルダは親フォルダ単位でダウンロードされ、完了マーカーは親フォルダにしか付かない
 * （[com.kamneko88.comicveil.data.nas.NasStreamCache.markComplete]）ため、親フォルダが
 * 完了済みであることと、画像ファイル自身が実在しサイズが一致していることの両方を見る。
 */
fun isNasImageCachedInFolder(folderComplete: Boolean, cachedLength: Long, expectedSize: Long): Boolean =
    folderComplete && isFullyCached(cachedLength, expectedSize)

/**
 * NASの一覧に並ぶ「画像フォルダの行（ディレクトリそのもの）」が、キャッシュ済みと判定できるかを返す。
 * 完了マーカーが立っているだけでは、ダウンロード前の空フォルダと区別できないため、
 * マーカー以外のファイルが1つ以上存在することも併せて確認する。
 */
fun isNasFolderCached(folderComplete: Boolean, cachedFileCount: Int): Boolean =
    folderComplete && cachedFileCount > 0
