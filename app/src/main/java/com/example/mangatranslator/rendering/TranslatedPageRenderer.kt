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
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        regions.forEach { region ->
            val translation = region.translation?.trim().orEmpty()
            if (translation.isBlank()) return@forEach

            val box = clamp(region.boundingBox, output.width, output.height)
            if (box.width() < MIN_REGION_PX || box.height() < MIN_REGION_PX) return@forEach

            eraseOriginalText(output, canvas, box)
            drawTranslation(canvas, box, translation)
        }

        return output
    }

    private fun eraseOriginalText(bitmap: Bitmap, canvas: Canvas, box: Rect) {
        val expanded = expand(box, bitmap.width, bitmap.height)
        val background = sampleBackground(bitmap, expanded, box)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            box.left.toFloat(),
            box.top.toFloat(),
            box.right.toFloat(),
            box.bottom.toFloat(),
            min(box.width(), box.height()) * CORNER_RADIUS_RATIO,
            min(box.width(), box.height()) * CORNER_RADIUS_RATIO,
            paint,
        )
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

    private fun sampleBackground(bitmap: Bitmap, outer: Rect, inner: Rect): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val step = max(1, min(outer.width(), outer.height()) / SAMPLE_TARGET)

        var y = outer.top
        while (y < outer.bottom) {
            var x = outer.left
            while (x < outer.right) {
                if (!inner.contains(x, y)) {
                    val pixel = bitmap.getPixel(x, y)
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0L) return Color.WHITE
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun expand(rect: Rect, width: Int, height: Int): Rect {
        val padding = max(MIN_SAMPLE_PADDING_PX, (min(rect.width(), rect.height()) * SAMPLE_PADDING_RATIO).toInt())
        return Rect(
            max(0, rect.left - padding),
            max(0, rect.top - padding),
            min(width, rect.right + padding),
            min(height, rect.bottom + padding),
        )
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
        const val CORNER_RADIUS_RATIO = 0.08f
        const val MIN_SAMPLE_PADDING_PX = 8
        const val SAMPLE_PADDING_RATIO = 0.20f
        const val SAMPLE_TARGET = 24
    }
}
