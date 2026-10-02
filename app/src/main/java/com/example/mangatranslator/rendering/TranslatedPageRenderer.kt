package com.example.mangatranslator.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.example.mangatranslator.ocr.TextRegion
import kotlin.math.max
import kotlin.math.min

/**
 * Produces a translated copy of a manga page without mutating the source bitmap.
 *
 * M4 starts deliberately conservative: only regions with a non-blank translation
 * are rendered. The original glyph area is covered using a background colour
 * sampled around the region, then Indonesian text is fitted back into the box.
 *
 * This is not semantic inpainting yet. Keeping the cleanup strategy behind this
 * class lets a future inpainting implementation replace it without changing OCR,
 * translation, or UI state.
 */
class TranslatedPageRenderer {

    fun render(source: Bitmap, regions: List<TextRegion>): Bitmap {
        val translatedRegions = regions.filter { !it.translation.isNullOrBlank() }
        val mask = GlyphMaskGenerator().generate(source, translatedRegions)
        val output = LocalGlyphInpainter().reconstruct(source, mask)
        mask.recycle()
        val canvas = Canvas(output)

        translatedRegions.forEach { region ->
            val translation = region.translation?.trim().orEmpty()
            if (translation.isBlank()) return@forEach

            val box = clamp(region.boundingBox, output.width, output.height)
            if (box.width() < MIN_REGION_PX || box.height() < MIN_REGION_PX) return@forEach

            drawTranslation(canvas, box, translation)
        }

        return output
    }

    private fun drawTranslation(canvas: Canvas, box: Rect, text: String) {
        val horizontalPadding = max(MIN_TEXT_PADDING_PX, (box.width() * TEXT_PADDING_RATIO).toInt())
        val verticalPadding = max(MIN_TEXT_PADDING_PX, (box.height() * TEXT_PADDING_RATIO).toInt())
        val content = Rect(
            box.left + horizontalPadding,
            box.top + verticalPadding,
            box.right - horizontalPadding,
            box.bottom - verticalPadding,
        )
        if (content.width() <= 0 || content.height() <= 0) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        var textSize = min(content.width(), content.height()) * INITIAL_TEXT_SIZE_RATIO
        val minTextSize = max(MIN_TEXT_SIZE_PX, min(content.width(), content.height()) * MIN_TEXT_SIZE_RATIO)
        var lines: List<String>

        do {
            paint.textSize = textSize
            lines = wrap(text, paint, content.width().toFloat())
            val metrics = paint.fontMetrics
            val lineHeight = (metrics.descent - metrics.ascent) * LINE_SPACING
            if (lines.size * lineHeight <= content.height()) break
            textSize -= TEXT_SIZE_STEP_PX
        } while (textSize >= minTextSize)

        paint.textSize = max(textSize, minTextSize)
        lines = wrap(text, paint, content.width().toFloat())

        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * LINE_SPACING
        val totalHeight = lines.size * lineHeight
        var baseline = content.centerY() - totalHeight / 2f - metrics.ascent

        lines.forEach { line ->
            canvas.drawText(line, content.exactCenterX(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.replace(Regex("\\s+"), " ").trim().split(" ")
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var current = ""

        words.forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isBlank()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun clamp(rect: Rect, width: Int, height: Int): Rect = Rect(
        rect.left.coerceIn(0, width),
        rect.top.coerceIn(0, height),
        rect.right.coerceIn(0, width),
        rect.bottom.coerceIn(0, height),
    )

    private companion object {
        const val MIN_REGION_PX = 12
        const val MIN_TEXT_PADDING_PX = 4
        const val TEXT_PADDING_RATIO = 0.08f
        const val INITIAL_TEXT_SIZE_RATIO = 0.24f
        const val MIN_TEXT_SIZE_RATIO = 0.10f
        const val MIN_TEXT_SIZE_PX = 10f
        const val TEXT_SIZE_STEP_PX = 2f
        const val LINE_SPACING = 1.08f
    }
}
