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

        // M2.2: keep M2.1's useful inter-block merge, but prevent chain-merging
        // across separate bubbles. Every new block must still align with the
        // original anchor and keep the whole group's geometry compact.
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

            while (group.size < MAX_BLOCKS_PER_GROUP) {
                val groupBounds = unionBounds(group.map { it.boundingBox })
                val bestNeighbour = ordered.indices
                    .asSequence()
                    .filter { index -> !used[index] && ordered[index].isVertical }
                    .mapNotNull { index ->
                        val candidate = ordered[index]

                        // Candidate must be adjacent to at least one current
                        // member, preserving the good M2.1 multi-column merge.
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

                        // Unlike M2.1, proximity to any member is not enough:
                        // the new block must remain aligned with the seed and
                        // must not stretch the overall group into another bubble.
                        if (!shouldJoinVerticalGroup(
                                anchor = seed,
                                group = group,
                                candidate = candidate,
                                combinedBounds = combinedBounds,
                                maxGroupWidth = maxGroupWidth,
                            )
                        ) {
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

    private fun shouldJoinVerticalGroup(
        anchor: OcrCandidate,
        group: List<OcrCandidate>,
        candidate: OcrCandidate,
        combinedBounds: Rect,
        maxGroupWidth: Int,
    ): Boolean {
        if (combinedBounds.width() > maxGroupWidth) return false

        val anchorBox = anchor.boundingBox
        val candidateBox = candidate.boundingBox
        val anchorOverlap = verticalOverlapRatio(anchorBox, candidateBox)
        if (anchorOverlap < MIN_ANCHOR_VERTICAL_OVERLAP_RATIO) return false

        val anchorCenterDistance = abs(anchorBox.centerY() - candidateBox.centerY())
        val anchorHeight = anchorBox.height().coerceAtLeast(1)
        val candidateHeight = candidateBox.height().coerceAtLeast(1)
        val allowedCenterDistance = (
            max(anchorHeight, candidateHeight) * MAX_ANCHOR_CENTER_DISTANCE_RATIO
        ).toInt()
        if (anchorCenterDistance > allowedCenterDistance) return false

        val tallestMember = max(
            candidateHeight,
            group.maxOf { it.boundingBox.height().coerceAtLeast(1) },
        )
        val maxGroupHeight = (tallestMember * MAX_GROUP_HEIGHT_MULTIPLIER).toInt()
        if (combinedBounds.height() > maxGroupHeight) return false

        return true
    }

    private fun shouldMergeVerticalPair(
        first: OcrCandidate,
        second: OcrCandidate,
        imageWidth: Int,
    ): Boolean {
        if (!first.isVertical || !second.isVertical) return false

        val firstBox = first.boundingBox
        val secondBox = second.boundingBox
        if (verticalOverlapRatio(firstBox, secondBox) < MIN_BLOCK_VERTICAL_OVERLAP_RATIO) {
            return false
        }

        val firstWidth = firstBox.width().coerceAtLeast(1)
        val secondWidth = secondBox.width().coerceAtLeast(1)
        val smallerWidth = min(firstWidth, secondWidth)
        val centerDistanceX = abs(firstBox.centerX() - secondBox.centerX())

        // Avoid treating duplicate/overlapping detections from essentially the
        // same column as two neighbouring manga columns.
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

    private fun verticalOverlapRatio(first: Rect, second: Rect): Float {
        val overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        if (overlap <= 0) return 0f

        val smallerHeight = min(first.height(), second.height()).coerceAtLeast(1)
        return overlap.toFloat() / smallerHeight
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
        const val MIN_ANCHOR_VERTICAL_OVERLAP_RATIO = 0.30f
        const val MAX_ANCHOR_CENTER_DISTANCE_RATIO = 0.70f
        const val MAX_GROUP_HEIGHT_MULTIPLIER = 1.35f
        const val MAX_COLUMN_GAP_WIDTH_MULTIPLIER = 1.8f
        const val MAX_COLUMN_GAP_IMAGE_RATIO = 0.035f
        const val MIN_BLOCK_GAP_PX = 8
        const val MIN_COLUMN_CENTER_DISTANCE_RATIO = 0.55f
        const val MAX_GROUP_WIDTH_RATIO = 0.18f
        const val MIN_GROUP_WIDTH_PX = 72
        const val MAX_BLOCKS_PER_GROUP = 5
    }
}
