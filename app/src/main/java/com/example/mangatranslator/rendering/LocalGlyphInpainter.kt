package com.example.mangatranslator.rendering

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic local reconstruction for the spike.
 *
 * Masked glyph pixels are replaced from nearby unmasked pixels. This is not
 * semantic/AI inpainting; it gives us a measurable baseline before deciding
 * whether a heavier inpainting backend is justified.
 */
class LocalGlyphInpainter {

    fun reconstruct(source: Bitmap, mask: Bitmap): Bitmap {
        require(source.width == mask.width && source.height == mask.height)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                if (Color.alpha(mask.getPixel(x, y)) == 0) continue
                output.setPixel(x, y, sampleNearby(source, mask, x, y))
            }
        }
        return output
    }

    private fun sampleNearby(source: Bitmap, mask: Bitmap, x: Int, y: Int): Int {
        for (radius in 2..MAX_RADIUS step 2) {
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0

            val left = max(0, x - radius)
            val right = min(source.width - 1, x + radius)
            val top = max(0, y - radius)
            val bottom = min(source.height - 1, y + radius)

            fun collect(px: Int, py: Int) {
                if (Color.alpha(mask.getPixel(px, py)) != 0) return
                val pixel = source.getPixel(px, py)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }

            for (px in left..right) {
                collect(px, top)
                collect(px, bottom)
            }
            for (py in (top + 1) until bottom) {
                collect(left, py)
                collect(right, py)
            }

            if (count >= MIN_SAMPLES) {
                return Color.rgb(
                    (red / count).toInt(),
                    (green / count).toInt(),
                    (blue / count).toInt(),
                )
            }
        }
        return Color.WHITE
    }

    private companion object {
        const val MAX_RADIUS = 18
        const val MIN_SAMPLES = 4
    }
}
