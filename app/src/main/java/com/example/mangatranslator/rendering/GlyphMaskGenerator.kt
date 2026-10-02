package com.example.mangatranslator.rendering

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.mangatranslator.ocr.TextRegion
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental glyph-mask generator for the reconstruction spike.
 *
 * It deliberately masks dark glyph-like pixels inside translated OCR regions
 * instead of painting the entire OCR rectangle. SFX are out of scope for v1:
 * only regions already accepted by the dialogue OCR/translation pipeline enter
 * this stage.
 */
class GlyphMaskGenerator {

    fun generate(source: Bitmap, regions: List<TextRegion>): Bitmap {
        val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ALPHA_8)

        regions.forEach { region ->
            if (region.translation.isNullOrBlank()) return@forEach
            val box = clamp(region.boundingBox, source.width, source.height)
            if (box.width() < MIN_REGION_PX || box.height() < MIN_REGION_PX) return@forEach

            val threshold = estimateDarkThreshold(source, box)
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) {
                    val pixel = source.getPixel(x, y)
                    if (luminance(pixel) <= threshold) {
                        mask.setPixel(x, y, Color.WHITE)
                    }
                }
            }
        }

        return mask
    }

    private fun estimateDarkThreshold(source: Bitmap, box: Rect): Int {
        var sum = 0L
        var count = 0L
        val step = max(1, min(box.width(), box.height()) / SAMPLE_TARGET)
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                sum += luminance(source.getPixel(x, y))
                count++
                x += step
            }
            y += step
        }
        val average = if (count == 0L) 255 else (sum / count).toInt()
        return min(MAX_DARK_THRESHOLD, max(MIN_DARK_THRESHOLD, average - DARKNESS_MARGIN))
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun clamp(rect: Rect, width: Int, height: Int): Rect = Rect(
        rect.left.coerceIn(0, width),
        rect.top.coerceIn(0, height),
        rect.right.coerceIn(0, width),
        rect.bottom.coerceIn(0, height),
    )

    private companion object {
        const val MIN_REGION_PX = 8
        const val SAMPLE_TARGET = 24
        const val MIN_DARK_THRESHOLD = 48
        const val MAX_DARK_THRESHOLD = 150
        const val DARKNESS_MARGIN = 48
    }
}
