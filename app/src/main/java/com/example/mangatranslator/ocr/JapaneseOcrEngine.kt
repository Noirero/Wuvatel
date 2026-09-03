package com.example.mangatranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class JapaneseOcrEngine {
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> {
        // OCR the exact bitmap shown by the UI so bounding-box coordinates
        // always use the same pixel coordinate space as the preview.
        val input = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(input).await()

        // M2 keeps ML Kit text blocks as the base dialogue group instead of
        // exposing every detected line as a separate region. Inside a block,
        // vertical Japanese columns are read right-to-left.
        val groupedRegions = result.textBlocks.mapNotNull blockLoop@ { block ->
            val lines = block.lines.mapNotNull lineLoop@ { line ->
                val box = line.boundingBox ?: return@lineLoop null
                val value = line.text.trim()
                if (value.isBlank()) return@lineLoop null
                OcrLine(text = value, boundingBox = Rect(box))
            }

            if (lines.isEmpty()) return@blockLoop null
            buildRegion(lines)
        }

        return sortForMangaReading(groupedRegions, bitmap.height)
    }

    private fun buildRegion(lines: List<OcrLine>): TextRegion {
        val verticalCount = lines.count { line ->
            line.boundingBox.height() > line.boundingBox.width() * VERTICAL_RATIO
        }
        val isVertical = verticalCount * 2 >= lines.size

        val orderedLines = if (isVertical) {
            lines.sortedWith(
                compareByDescending<OcrLine> { it.boundingBox.centerX() }
                    .thenBy { it.boundingBox.top },
            )
        } else {
            lines.sortedWith(
                compareBy<OcrLine> { it.boundingBox.top }
                    .thenBy { it.boundingBox.left },
            )
        }

        val bounds = Rect(orderedLines.first().boundingBox)
        orderedLines.drop(1).forEach { line -> bounds.union(line.boundingBox) }

        // Japanese vertical columns form one continuous sentence. Horizontal
        // lines keep their line break so later translation/typesetting can
        // preserve the original layout hint.
        val separator = if (isVertical) "" else "\n"
        val text = orderedLines.joinToString(separator = separator) { it.text }

        return TextRegion(text = text, boundingBox = bounds)
    }

    private fun sortForMangaReading(
        regions: List<TextRegion>,
        imageHeight: Int,
    ): List<TextRegion> {
        if (regions.size < 2) return regions

        val rowTolerance = max(MIN_ROW_TOLERANCE_PX, (imageHeight * ROW_TOLERANCE_RATIO).toInt())
        val rows = mutableListOf<ReadingRow>()
        val candidates = regions.sortedWith(
            compareBy<TextRegion> { it.boundingBox.top }
                .thenByDescending { it.boundingBox.right },
        )

        candidates.forEach { region ->
            val box = region.boundingBox
            val matchingRow = rows
                .mapNotNull { row ->
                    val score = rowMatchScore(row, box, rowTolerance)
                    if (score >= 0f) row to score else null
                }
                .maxByOrNull { it.second }
                ?.first

            if (matchingRow == null) {
                rows += ReadingRow(
                    regions = mutableListOf(region),
                    top = box.top,
                    bottom = box.bottom,
                )
            } else {
                matchingRow.regions += region
                matchingRow.top = min(matchingRow.top, box.top)
                matchingRow.bottom = max(matchingRow.bottom, box.bottom)
            }
        }

        return rows
            .sortedBy { it.top }
            .flatMap { row ->
                row.regions.sortedWith(
                    compareByDescending<TextRegion> { it.boundingBox.right }
                        .thenBy { it.boundingBox.top },
                )
            }
    }

    private fun rowMatchScore(row: ReadingRow, box: Rect, tolerance: Int): Float {
        val overlap = min(row.bottom, box.bottom) - max(row.top, box.top)
        val rowHeight = (row.bottom - row.top).coerceAtLeast(1)
        val boxHeight = box.height().coerceAtLeast(1)
        val overlapRatio = overlap.coerceAtLeast(0).toFloat() / min(rowHeight, boxHeight)

        if (overlapRatio >= MIN_VERTICAL_OVERLAP_RATIO) {
            return overlapRatio
        }

        val rowCenter = (row.top + row.bottom) / 2
        val centerDistance = abs(rowCenter - box.centerY())
        if (centerDistance <= tolerance) {
            return 1f - centerDistance.toFloat() / (tolerance + 1)
        }

        return -1f
    }

    fun close() {
        recognizer.close()
    }

    private data class OcrLine(
        val text: String,
        val boundingBox: Rect,
    )

    private data class ReadingRow(
        val regions: MutableList<TextRegion>,
        var top: Int,
        var bottom: Int,
    )

    private companion object {
        const val VERTICAL_RATIO = 1.2f
        const val ROW_TOLERANCE_RATIO = 0.025f
        const val MIN_ROW_TOLERANCE_PX = 12
        const val MIN_VERTICAL_OVERLAP_RATIO = 0.35f
    }
}
