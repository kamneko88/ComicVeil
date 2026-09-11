package com.kamneko88.comicveil.ui.home

import com.kamneko88.comicveil.data.FileItem
import com.kamneko88.comicveil.data.FileItemType
import com.kamneko88.comicveil.data.nas.NasServer
import com.kamneko88.comicveil.ui.viewer.ViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * navKeyFor()の回帰テスト。
 *
 * NAS本(STRモード)は実ファイルがローカルキャッシュパスになる一方、HOME一覧・
 * ブックマーク一覧はFileItem.path（smb://...）で進捗・既読状態を読み書きするため、
 * 両者を一致させる正規キーの組み立てが正しいかを検証する。
 */
class NavKeyForTest {

    private val server = NasServer(
        displayName = "archive",
        host        = "192.168.1.1",
        username    = "user",
        password    = "pass"
    )

    @Test
    fun `NAS item with local cache file gets canonical key appended`() {
        val cacheFile = File("nas_stream_cache/nas_123/nas_123.zip")
        val item = FileItem.fromNas(
            name        = "book.zip",
            nasPath     = "manga/book.zip",
            isDirectory = false,
            size        = 100L,
            server      = server
        ).copy(file = cacheFile)

        val expected = "${cacheFile.absolutePath}${ViewerViewModel.KEY_MARKER_PUBLIC}smb://192.168.1.1//manga/book.zip"
        assertEquals(expected, navKeyFor(item))
    }

    @Test
    fun `NAS item without a local file yet falls back to canonical path as-is`() {
        // まだダウンロードされていない（file=null）場合、effectivePathはitem.pathと同じになるため
        // マーカーは不要（付けても実害はないが、なるべく素のパスのままにする）
        val item = FileItem.fromNas(
            name        = "book.zip",
            nasPath     = "manga/book.zip",
            isDirectory = false,
            size        = 100L,
            server      = server
        )

        assertEquals(item.path, navKeyFor(item))
    }

    @Test
    fun `local file item is untouched`() {
        val file = File("/storage/emulated/0/Comics/book.zip")
        val item = FileItem.fromFile(file)

        assertEquals(file.absolutePath, navKeyFor(item))
    }

    @Test
    fun `non-NAS item is never given a canonical key even if paths differ`() {
        // ensureSafCached経由（SAF直接閲覧）を模したケース：実ファイルはキャッシュパス、
        // pathはcontent://...相当だが、isNas=falseなので今回の修正の対象外のまま
        // （このテストはスコープ外に手を加えていないことの回帰ガード）
        val cacheFile = File("saf_cache/saf_999.zip")
        val item = FileItem(
            file = cacheFile,
            path = "content://com.example.provider/document/999",
            type = FileItemType.COMIC_FILE
        )

        assertEquals(cacheFile.absolutePath, navKeyFor(item))
    }
}
