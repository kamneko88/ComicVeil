package com.kamneko88.comicveil.ui.viewer

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * ページを表示するとき、画像のどちら半分を見せるか。
 * FULL＝分割しない（通常ページ）。RIGHT/LEFT＝見開き分割で使う片側。
 */
enum class PageHalf { FULL, RIGHT, LEFT }

/**
 * 見開き分割機能（見開き表示OFF時、横長ページ＝見開きの1枚絵を自動で単ページ2枚として見せる）で使う
 * Coilの画像変換。表示直前に画像の右半分・左半分だけを切り出す。
 *
 * 実データやページ数は一切変更しない。栞・読書進捗・スライダー・サムネイルは
 * 元の1ページ分のまま扱われる（表示だけを2回に分けて見せている）。
 */
class HalfCropTransformation(
    private val half: PageHalf
) : Transformation() {

    override val cacheKey: String = "half_crop_$half"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (half == PageHalf.FULL) return input

        val width     = input.width
        val height    = input.height
        val halfWidth = width / 2
        if (halfWidth < 1) return input

        val left = if (half == PageHalf.RIGHT) width - halfWidth else 0
        return Bitmap.createBitmap(input, left, 0, halfWidth, height)
    }

    override fun equals(other: Any?): Boolean = other is HalfCropTransformation && other.half == half

    override fun hashCode(): Int = cacheKey.hashCode()
}
