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
 * @param whiteOnly    true の場合、白い余白だけを削除する（黒背景のページはそのまま）
 * @param keepAspect   true の場合、切り出し範囲を広げて元の縦横比を保つ
 *                     （ページごとに大きさがバラつくのを防ぐ）
 */
class TrimMarginsTransformation(
    private val whiteOnly: Boolean = false,
    private val keepAspect: Boolean = true
) : Transformation() {

    override val cacheKey: String = "trim_margins_${whiteOnly}_$keepAspect"

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
        val isLightEdge = corners.map { luminance(it) }.average() > 128

        // 「白い余白のみ」設定のときは、黒フチのページには手を付けない
        if (whiteOnly && !isLightEdge) return input

        // 走査の刻み幅（大きな画像でも軽く済ませるための間引き）
        val stepX = (width  / SAMPLE_COUNT).coerceAtLeast(1)
        val stepY = (height / SAMPLE_COUNT).coerceAtLeast(1)

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

        var cropLeft   = left
        var cropTop    = top
        var cropWidth  = right - left + 1
        var cropHeight = bottom - top + 1

        // 縦横比を保つ場合は、切り出し範囲を足りない方向へ広げて元の比率に合わせる
        if (keepAspect) {
            val targetRatio = width.toFloat() / height
            val cropRatio   = cropWidth.toFloat() / cropHeight

            if (cropRatio < targetRatio) {
                // 横が足りない → 左右に広げる
                val wanted = (cropHeight * targetRatio).toInt().coerceAtMost(width)
                val extra  = wanted - cropWidth
                cropLeft  = (cropLeft - extra / 2).coerceAtLeast(0)
                cropWidth = wanted.coerceAtMost(width - cropLeft)
            } else if (cropRatio > targetRatio) {
                // 縦が足りない → 上下に広げる
                val wanted = (cropWidth / targetRatio).toInt().coerceAtMost(height)
                val extra  = wanted - cropHeight
                cropTop    = (cropTop - extra / 2).coerceAtLeast(0)
                cropHeight = wanted.coerceAtMost(height - cropTop)
            }
        }

        // 切り落とす量がごくわずかなら、そのまま返す（無駄なコピーを避ける）
        if (cropWidth >= width * 0.98 && cropHeight >= height * 0.98) return input
        if (cropWidth < MIN_SIZE || cropHeight < MIN_SIZE) return input

        return Bitmap.createBitmap(input, cropLeft, cropTop, cropWidth, cropHeight)
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

    override fun equals(other: Any?): Boolean =
        other is TrimMarginsTransformation &&
            other.whiteOnly == whiteOnly &&
            other.keepAspect == keepAspect

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
