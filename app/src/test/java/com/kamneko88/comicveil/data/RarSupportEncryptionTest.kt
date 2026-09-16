package com.kamneko88.comicveil.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 課題2（RAR）の暗号化検知テスト。
 *
 * 【なぜピュア関数のテストに留めるか】
 * - RAR4の実際の検知はjunrar（純Java）でFileHeaderを読むが、junrarはデコード専用ライブラリで
 *   RARを書き出す手段が無く、正しいCRC・ブロック構造を持つ実物のRARファイルをJVMテストで
 *   自作するのは現実的ではない。そのため判定ロジック本体（[RarSupport.junrarHeadersEncrypted]）
 *   のみを切り出してテストする。実際のjunrar連携（[RarSupport.isEncrypted]のRAR4経路）は
 *   実機・エミュレータでの確認が必要（未確認）。
 * - RAR5の実際の検知はlibarchive（JNI・ネイティブ.so）を使うため、プレーンなJVMユニットテストでは
 *   そもそも実行できない。判定ロジック本体（[RarSupport.libarchiveHasEncryptedEntries]）のみを
 *   切り出してテストする。実際のlibarchive連携（[RarSupport.isEncrypted]のRAR5経路）は
 *   実機・エミュレータでの確認が必要（未確認）。
 */
class RarSupportEncryptionTest {

    @Test
    fun `rar4 - any encrypted header means the archive needs a password`() {
        assertTrue(RarSupport.junrarHeadersEncrypted(listOf(false, false, true, false)))
    }

    @Test
    fun `rar4 - no encrypted headers means no password needed`() {
        assertFalse(RarSupport.junrarHeadersEncrypted(listOf(false, false, false)))
    }

    @Test
    fun `rar4 - empty header list means no password needed`() {
        assertFalse(RarSupport.junrarHeadersEncrypted(emptyList()))
    }

    @Test
    fun `rar5 - positive code from archive_read_has_encrypted_entries means encrypted`() {
        assertTrue(RarSupport.libarchiveHasEncryptedEntries(1))
    }

    @Test
    fun `rar5 - zero code means not encrypted`() {
        assertFalse(RarSupport.libarchiveHasEncryptedEntries(0))
    }

    @Test
    fun `rar5 - unknown code (-1) is treated as not encrypted to avoid false positives`() {
        assertFalse(RarSupport.libarchiveHasEncryptedEntries(-1))
    }
}
