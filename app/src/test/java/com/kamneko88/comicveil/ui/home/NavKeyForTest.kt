package com.kamneko88.comicveil.ui.home

import android.net.FakeUri
import android.net.Uri
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
    fun `SAF item with local cache file gets canonical key appended`() {
        // ensureSafCached経由（SAF直接閲覧）を模したケース：uriを正しく設定した上で、
        // 実ファイルだけキャッシュパスに差し替えられている（onComicTappedのcopy(file=...)相当）
        val path      = "content://com.example.provider/document/999"
        val uri       = fakeUri(path)
        val cacheFile = File("saf_cache/saf_999.zip")
        val item = FileItem(
            uri  = uri,
            file = cacheFile,
            path = path,
            type = FileItemType.COMIC_FILE
        )

        val expected = "${cacheFile.absolutePath}${ViewerViewModel.KEY_MARKER_PUBLIC}$path"
        assertEquals(expected, navKeyFor(item))
    }

    @Test
    fun `plain local file without uri or cache swap gets no canonical key`() {
        // uriもキャッシュ差し替えも無い、file.absolutePath == pathの純粋なローカルファイル。
        // スコープ外（ローカル・SAF取り込み済み）に影響していないことの回帰ガード
        val file = File("/storage/emulated/0/Comics/plain.zip")
        val item = FileItem(
            file = file,
            path = file.absolutePath,
            type = FileItemType.COMIC_FILE
        )

        assertEquals(file.absolutePath, navKeyFor(item))
    }

    private fun fakeUri(value: String): Uri = FakeUri(value)
}
