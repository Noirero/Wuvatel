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
        val baseCandidates = result.textBlocks.mapNotNull blockLoop@ { block ->
            val lines = block.lines.mapNotNull lineLoop@ { line ->
                val box = line.boundingBox ?: return@lineLoop null
                val value = line.text.trim()
                if (value.isBlank()) return@lineLoop null
                OcrLine(text = value, boundingBox = Rect(box))
            }

            if (lines.isEmpty()) return@blockLoop null
            buildCandidate(lines)
        }

        // M2.1: ML Kit can split neighbouring vertical columns from the same
        // speech bubble into separate TextBlocks. Merge only conservative,
        // spatially-close vertical neighbours before the final reading order.
        val groupedRegions = mergeNearbyVerticalBlocks(
            candidates = baseCandidates,
            imageWidth = bitmap.width,
        ).map { candidate ->
            TextRegion(
                text = candidate.text,
                boundingBox = Rect(candidate.boundingBox),
            )
        }

        return sortForMangaReading(groupedRegions, bitmap.height)
    }

    private fun buildCandidate(lines: List<OcrLine>): OcrCandidate {
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

        return OcrCandidate(
            text = text,
            boundingBox = bounds,
            isVertical = isVertical,
        )
    }

    private fun mergeNearbyVerticalBlocks(
        candidates: List<OcrCandidate>,
        imageWidth: Int,
    ): List<OcrCandidate> {
        if (candidates.size < 2) return candidates

        val ordered = candidates.sortedWith(
            compareBy<OcrCandidate> { it.boundingBox.top }
                .thenByDescending { it.boundingBox.right },
        )
        val used = BooleanArray(ordered.size)
        val output = mutableListOf<OcrCandidate>()
        val maxGroupWidth = max(
            MIN_GROUP_WIDTH_PX,
            (imageWidth * MAX_GROUP_WIDTH_RATIO).toInt(),
        )

        ordered.indices.forEach { seedIndex ->
            if (used[seedIndex]) return@forEach

            val seed = ordered[seedIndex]
            if (!seed.isVertical) {
                used[seedIndex] = true
                output += seed
                return@forEach
            }

            val group = mutableListOf(seed)
            used[seedIndex] = true

            while (true) {
                val groupBounds = unionBounds(group.map { it.boundingBox })
                val bestNeighbour = ordered.indices
                    .asSequence()
                    .filter { index -> !used[index] && ordered[index].isVertical }
                    .mapNotNull { index ->
                        val candidate = ordered[index]
                        val pairGap = group
                            .mapNotNull { member ->
                                if (shouldMergeVerticalPair(member, candidate, imageWidth)) {
                                    horizontalGap(member.boundingBox, candidate.boundingBox)
                                } else {
                                    null
                                }
                            }
                            .minOrNull()
                            ?: return@mapNotNull null

                        val combinedBounds = Rect(groupBounds).apply {
                            union(candidate.boundingBox)
                        }
                        if (combinedBounds.width() > maxGroupWidth) {
                            return@mapNotNull null
                        }

                        index to pairGap
                    }
                    .minByOrNull { it.second }
                    ?.first
                    ?: break

                group += ordered[bestNeighbour]
                used[bestNeighbour] = true
            }

            output += mergeCandidateGroup(group)
        }

        return output
    }

    private fun shouldMergeVerticalPair(
        first: OcrCandidate,
        second: OcrCandidate,
        imageWidth: Int,
    ): Boolean {
        if (!first.isVertical || !second.isVertical) return false

        val firstBox = first.boundingBox
        val secondBox = second.boundingBox
        val overlap = min(firstBox.bottom, secondBox.bottom) - max(firstBox.top, secondBox.top)
        if (overlap <= 0) return false

        val smallerHeight = min(firstBox.height(), secondBox.height()).coerceAtLeast(1)
        val overlapRatio = overlap.toFloat() / smallerHeight
        if (overlapRatio < MIN_BLOCK_VERTICAL_OVERLAP_RATIO) return false

        val firstWidth = firstBox.width().coerceAtLeast(1)
        val secondWidth = secondBox.width().coerceAtLeast(1)
        val smallerWidth = min(firstWidth, secondWidth)
        val centerDistanceX = abs(firstBox.centerX() - secondBox.centerX())

        // Avoid treating overlapping detections from essentially the same
        // column as separate manga columns.
        if (centerDistanceX < smallerWidth * MIN_COLUMN_CENTER_DISTANCE_RATIO) {
            return false
        }

        val widthBasedGap = (max(firstWidth, secondWidth) * MAX_COLUMN_GAP_WIDTH_MULTIPLIER).toInt()
        val imageBasedGap = (imageWidth * MAX_COLUMN_GAP_IMAGE_RATIO).toInt()
        val allowedGap = max(
            MIN_BLOCK_GAP_PX,
            min(widthBasedGap, imageBasedGap),
        )

        return horizontalGap(firstBox, secondBox) <= allowedGap
    }

    private fun mergeCandidateGroup(group: List<OcrCandidate>): OcrCandidate {
        if (group.size == 1) return group.first()

        val ordered = group.sortedWith(
            compareByDescending<OcrCandidate> { it.boundingBox.centerX() }
                .thenBy { it.boundingBox.top },
        )
        val bounds = unionBounds(ordered.map { it.boundingBox })

        return OcrCandidate(
            text = ordered.joinToString(separator = "") { it.text },
            boundingBox = bounds,
            isVertical = true,
        )
    }

    private fun unionBounds(boxes: List<Rect>): Rect {
        val bounds = Rect(boxes.first())
        boxes.drop(1).forEach(bounds::union)
        return bounds
    }

    private fun horizontalGap(first: Rect, second: Rect): Int = when {
        first.right < second.left -> second.left - first.right
        second.right < first.left -> first.left - second.right
        else -> 0
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

    private data class OcrCandidate(
        val text: String,
        val boundingBox: Rect,
        val isVertical: Boolean,
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

        const val MIN_BLOCK_VERTICAL_OVERLAP_RATIO = 0.45f
        const val MAX_COLUMN_GAP_WIDTH_MULTIPLIER = 1.8f
        const val MAX_COLUMN_GAP_IMAGE_RATIO = 0.04f
        const val MIN_BLOCK_GAP_PX = 10
        const val MIN_COLUMN_CENTER_DISTANCE_RATIO = 0.55f
        const val MAX_GROUP_WIDTH_RATIO = 0.24f
        const val MIN_GROUP_WIDTH_PX = 96
    }
}
