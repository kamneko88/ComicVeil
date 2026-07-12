package com.kamneko88.comicveil.ui.viewer

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * ページ周囲の余白（白フチ・黒フチ）を自動で切り落とすCoilの画像変換。
 *
 * スキャンされたマンガは周囲に白（または黒）の余白が入っていることが多く、
 * そのまま表示すると本文が小さくなってしまう。
 * この変換で余白を取り除くことで、画面いっぱいに本文を表示できる。
 *
 * 【処理の流れ】
 * 1. 四隅の色から「背景が白系か黒系か」を判断する
 * 2. 上下左右から順に走査し、背景色でない画素（＝本文）が現れる位置を探す
 * 3. 見つかった範囲だけを切り出す
 *
 * 判定はサンプリング（数画素おき）で行うため、大きな画像でも軽量に動作する。
 */
class TrimMarginsTransformation : Transformation() {

    override val cacheKey: String = "trim_margins"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width  = input.width
        val height = input.height
        if (width < MIN_SIZE || height < MIN_SIZE) return input

        // 四隅の明るさから背景色（白系 or 黒系）を判断する
        val corners = intArrayOf(
            input.getPixel(0, 0),
            input.getPixel(width - 1, 0),
            input.getPixel(0, height - 1),
            input.getPixel(width - 1, height - 1)
        )
        val cornerLuma  = corners.map { luminance(it) }
        val isLightEdge = cornerLuma.average() > 128

        // 走査の刻み幅（大きな画像でも軽く済ませるための間引き）
        val stepX = (width  / SAMPLE_COUNT).coerceAtLeast(1)
        val stepY = (height / SAMPLE_COUNT).coerceAtLeast(1)

        /** その行・列が「まるごと余白」かどうか */
        fun isBackgroundRow(y: Int): Boolean {
            var x = 0
            while (x < width) {
                if (!isBackground(input.getPixel(x, y), isLightEdge)) return false
                x += stepX
            }
            return true
        }

        fun isBackgroundColumn(x: Int): Boolean {
            var y = 0
            while (y < height) {
                if (!isBackground(input.getPixel(x, y), isLightEdge)) return false
                y += stepY
            }
            return true
        }

        var top = 0
        while (top < height - MIN_SIZE && isBackgroundRow(top)) top++

        var bottom = height - 1
        while (bottom > top + MIN_SIZE && isBackgroundRow(bottom)) bottom--

        var left = 0
        while (left < width - MIN_SIZE && isBackgroundColumn(left)) left++

        var right = width - 1
        while (right > left + MIN_SIZE && isBackgroundColumn(right)) right--

        val newWidth  = right - left + 1
        val newHeight = bottom - top + 1

        // 切り落とす量がごくわずかなら、そのまま返す（無駄なコピーを避ける）
        if (newWidth >= width * 0.98 && newHeight >= height * 0.98) return input
        if (newWidth < MIN_SIZE || newHeight < MIN_SIZE) return input

        return Bitmap.createBitmap(input, left, top, newWidth, newHeight)
    }

    private fun isBackground(pixel: Int, isLightEdge: Boolean): Boolean {
        val luma = luminance(pixel)
        return if (isLightEdge) luma >= LIGHT_THRESHOLD else luma <= DARK_THRESHOLD
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // 人間の目の感度に合わせた明るさ（緑を重く見る）
        return (r * 30 + g * 59 + b * 11) / 100
    }

    override fun equals(other: Any?): Boolean = other is TrimMarginsTransformation

    override fun hashCode(): Int = cacheKey.hashCode()

    companion object {
        /** 背景を「白」と判定する明るさの下限（0〜255） */
        private const val LIGHT_THRESHOLD = 236

        /** 背景を「黒」と判定する明るさの上限（0〜255） */
        private const val DARK_THRESHOLD = 20

        /** これ以上小さくは切り詰めない（切りすぎ防止） */
        private const val MIN_SIZE = 32

        /** 1行・1列あたりのサンプリング数の目安 */
        private const val SAMPLE_COUNT = 60
    }
}
