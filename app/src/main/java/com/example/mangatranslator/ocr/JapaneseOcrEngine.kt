package com.example.mangatranslator.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class JapaneseOcrEngine {
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> {
        // M2.3 performs two OCR passes at the exact same pixel dimensions:
        // the untouched manga page and a grayscale/high-contrast copy. Because
        // dimensions do not change, every bounding box remains compatible with
        // the original preview shown by the UI.
        val originalResult = recognizer.process(
            InputImage.fromBitmap(bitmap, 0),
        ).await()

        val enhancedBitmap = createEnhancedBitmap(bitmap)
        val enhancedResult = try {
            recognizer.process(
                InputImage.fromBitmap(enhancedBitmap, 0),
            ).await()
        } finally {
            enhancedBitmap.recycle()
        }

        val originalCandidates = extractCandidates(originalResult)
        val enhancedCandidates = extractCandidates(enhancedResult)
        val baseCandidates = chooseCleanerPass(
            original = originalCandidates,
            enhanced = enhancedCandidates,
        )

        // Keep M2.2's conservative inter-block grouping. Text cleanup happens
        // only after geometry has been decided, so punctuation cleanup cannot
        // accidentally change grouping behavior.
        val groupedCandidates = mergeNearbyVerticalBlocks(
            candidates = baseCandidates,
            imageWidth = bitmap.width,
        ).map { candidate ->
            candidate.copy(text = cleanupText(candidate.text))
        }.filter { candidate ->
            candidate.text.isNotBlank()
        }

        val groupedRegions = groupedCandidates.map { candidate ->
            TextRegion(
                text = candidate.text,
                boundingBox = Rect(candidate.boundingBox),
            )
        }

        return sortForMangaReading(groupedRegions, bitmap.height)
    }

    private fun extractCandidates(result: Text): List<OcrCandidate> =
        result.textBlocks.mapNotNull blockLoop@ { block ->
            val lines = block.lines.mapNotNull lineLoop@ { line ->
                val box = line.boundingBox ?: return@lineLoop null
                val value = line.text.trim()
                if (value.isBlank()) return@lineLoop null
                OcrLine(text = value, boundingBox = Rect(box))
            }

            if (lines.isEmpty()) return@blockLoop null
            buildCandidate(lines)
        }

    private fun chooseCleanerPass(
        original: List<OcrCandidate>,
        enhanced: List<OcrCandidate>,
    ): List<OcrCandidate> {
        if (original.isEmpty()) return enhanced
        if (enhanced.isEmpty()) return original

        return original.map { originalCandidate ->
            val enhancedMatch = enhanced
                .asSequence()
                .filter { candidate ->
                    candidate.isVertical == originalCandidate.isVertical
                }
                .mapNotNull { candidate ->
                    val geometryScore = geometryMatchScore(
                        originalCandidate.boundingBox,
                        candidate.boundingBox,
                    )
                    if (geometryScore >= MIN_PASS_GEOMETRY_SCORE) {
                        candidate to geometryScore
                    } else {
                        null
                    }
                }
                .maxByOrNull { it.second }
                ?.first

            if (enhancedMatch == null) {
                originalCandidate
            } else {
                chooseCleanerCandidate(originalCandidate, enhancedMatch)
            }
        }
    }

    private fun chooseCleanerCandidate(
        original: OcrCandidate,
        enhanced: OcrCandidate,
    ): OcrCandidate {
        val originalScore = textQualityScore(original.text)
        val enhancedScore = textQualityScore(enhanced.text)
        val originalNoise = obviousNoiseCount(original.text)
        val enhancedNoise = obviousNoiseCount(enhanced.text)

        val useEnhanced = when {
            enhancedScore >= originalScore + PASS_SCORE_ADVANTAGE -> true
            enhancedNoise < originalNoise &&
                enhancedScore >= originalScore - PASS_SCORE_NOISE_TOLERANCE -> true
            else -> false
        }

        // Keep the original pass geometry. Only the recognized string is
        // replaced, avoiding small box jitter between the two ML Kit passes.
        return if (useEnhanced) {
            original.copy(text = enhanced.text)
        } else {
            original
        }
    }

    private fun geometryMatchScore(first: Rect, second: Rect): Float {
        val intersectionLeft = max(first.left, second.left)
        val intersectionTop = max(first.top, second.top)
        val intersectionRight = min(first.right, second.right)
        val intersectionBottom = min(first.bottom, second.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea =
            (intersectionRight - intersectionLeft).toLong() *
                (intersectionBottom - intersectionTop).toLong()
        val firstArea = first.width().coerceAtLeast(1).toLong() *
            first.height().coerceAtLeast(1).toLong()
        val secondArea = second.width().coerceAtLeast(1).toLong() *
            second.height().coerceAtLeast(1).toLong()
        val smallerArea = min(firstArea, secondArea).coerceAtLeast(1L)

        return intersectionArea.toFloat() / smallerArea.toFloat()
    }

    private fun createEnhancedBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)

        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        OCR_CONTRAST, 0f, 0f, 0f, OCR_CONTRAST_OFFSET,
                        0f, OCR_CONTRAST, 0f, 0f, OCR_CONTRAST_OFFSET,
                        0f, 0f, OCR_CONTRAST, 0f, OCR_CONTRAST_OFFSET,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
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

    private fun cleanupText(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .trim()

        if (text.isBlank()) return text

        val visibleCharacters = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val japaneseCount = text.count(::isJapaneseScript)
        val japaneseDominant =
            japaneseCount >= MIN_JAPANESE_FOR_CLEANUP &&
                japaneseCount.toFloat() / visibleCharacters >= JAPANESE_DOMINANCE_RATIO

        if (!japaneseDominant) {
            return text.replace(Regex("[\\t ]{2,}"), " ")
        }

        val source = text.toCharArray()
        text = buildString(source.size) {
            source.forEachIndexed { index, char ->
                when {
                    char == '|' || char == '｜' || char == '¦' -> Unit
                    char.isLowerCaseAscii() &&
                        isIsolatedAsciiNoise(source, index) -> Unit
                    char.isWhitespace() && char != '\n' &&
                        shouldRemoveJapaneseWhitespace(source, index) -> Unit
                    else -> append(char)
                }
            }
        }

        text = removeUnbalancedAsciiPair(text, '(', ')')
        text = removeUnbalancedAsciiPair(text, '[', ']')

        return text
            .replace(Regex("[\\t ]{2,}"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun removeUnbalancedAsciiPair(
        text: String,
        open: Char,
        close: Char,
    ): String {
        val openCount = text.count { it == open }
        val closeCount = text.count { it == close }
        if (openCount == closeCount) return text

        return text.filter { it != open && it != close }
    }

    private fun shouldRemoveJapaneseWhitespace(
        source: CharArray,
        index: Int,
    ): Boolean {
        val previous = previousVisibleChar(source, index)
        val next = nextVisibleChar(source, index)

        return (previous?.let(::isJapaneseOrPunctuation) == true) &&
            (next?.let(::isJapaneseOrPunctuation) == true)
    }

    private fun isIsolatedAsciiNoise(
        source: CharArray,
        index: Int,
    ): Boolean {
        val char = source[index]
        if (!char.isLowerCaseAscii()) return false

        // Preserve real Latin words such as "vs" or "web"; only a single
        // lowercase letter embedded in otherwise Japanese text is removed.
        if (index > 0 && source[index - 1].isAsciiLetter()) return false
        if (index + 1 < source.size && source[index + 1].isAsciiLetter()) return false

        val previous = previousVisibleChar(source, index)
        val next = nextVisibleChar(source, index)
        val previousFits = previous == null || isJapaneseOrPunctuation(previous)
        val nextFits = next == null || isJapaneseOrPunctuation(next)

        return previousFits && nextFits
    }

    private fun previousVisibleChar(
        source: CharArray,
        index: Int,
    ): Char? {
        var cursor = index - 1
        while (cursor >= 0) {
            if (!source[cursor].isWhitespace()) return source[cursor]
            cursor--
        }
        return null
    }

    private fun nextVisibleChar(
        source: CharArray,
        index: Int,
    ): Char? {
        var cursor = index + 1
        while (cursor < source.size) {
            if (!source[cursor].isWhitespace()) return source[cursor]
            cursor++
        }
        return null
    }

    private fun textQualityScore(raw: String): Float {
        val cleaned = cleanupText(raw)
        if (cleaned.isBlank()) return Float.NEGATIVE_INFINITY

        var score = 0f
        cleaned.forEach { char ->
            score += when {
                isKana(char) -> 3.0f
                isKanji(char) -> 2.5f
                isJapanesePunctuation(char) -> 0.25f
                char.isAsciiLetter() -> -1.5f
                char == '|' || char == '｜' || char == '¦' -> -3.0f
                char == '\uFFFD' -> -4.0f
                else -> 0f
            }
        }

        score -= obviousNoiseCount(raw) * 1.5f
        return score
    }

    private fun obviousNoiseCount(raw: String): Int {
        val visibleCharacters = raw.count { !it.isWhitespace() }.coerceAtLeast(1)
        val japaneseCount = raw.count(::isJapaneseScript)
        val japaneseDominant =
            japaneseCount >= MIN_JAPANESE_FOR_CLEANUP &&
                japaneseCount.toFloat() / visibleCharacters >= JAPANESE_DOMINANCE_RATIO

        if (!japaneseDominant) return 0

        val source = raw.toCharArray()
        var count = 0
        source.forEachIndexed { index, char ->
            if (char == '|' || char == '｜' || char == '¦' || char == '\uFFFD') {
                count++
            } else if (char.isLowerCaseAscii() && isIsolatedAsciiNoise(source, index)) {
                count++
            }
        }

        if (raw.count { it == '(' } != raw.count { it == ')' }) count++
        if (raw.count { it == '[' } != raw.count { it == ']' }) count++
        return count
    }

    private fun isJapaneseScript(char: Char): Boolean =
        isKana(char) || isKanji(char)

    private fun isKana(char: Char): Boolean {
        val code = char.code
        return code in 0x3040..0x309F ||
            code in 0x30A0..0x30FF ||
            code in 0x31F0..0x31FF ||
            code in 0xFF66..0xFF9D
    }

    private fun isKanji(char: Char): Boolean {
        val code = char.code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF
    }

    private fun isJapanesePunctuation(char: Char): Boolean =
        char in JAPANESE_PUNCTUATION

    private fun isJapaneseOrPunctuation(char: Char): Boolean =
        isJapaneseScript(char) || isJapanesePunctuation(char)

    private fun Char.isAsciiLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isLowerCaseAscii(): Boolean =
        this in 'a'..'z'

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

        if (centerDistanceX < smallerWidth * MIN_COLUMN_CENTER_DISTANCE_RATIO) {
            return false
        }

        val widthBasedGap =
            (max(firstWidth, secondWidth) * MAX_COLUMN_GAP_WIDTH_MULTIPLIER).toInt()
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

        val rowTolerance =
            max(MIN_ROW_TOLERANCE_PX, (imageHeight * ROW_TOLERANCE_RATIO).toInt())
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
        val overlapRatio =
            overlap.coerceAtLeast(0).toFloat() / min(rowHeight, boxHeight)

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

        const val OCR_CONTRAST = 1.35f
        const val OCR_CONTRAST_OFFSET = -44.8f
        const val MIN_PASS_GEOMETRY_SCORE = 0.55f
        const val PASS_SCORE_ADVANTAGE = 1.0f
        const val PASS_SCORE_NOISE_TOLERANCE = 1.5f
        const val MIN_JAPANESE_FOR_CLEANUP = 2
        const val JAPANESE_DOMINANCE_RATIO = 0.55f

        val JAPANESE_PUNCTUATION = setOf(
            '。', '、', '！', '？', '!', '?', '…', '‥', 'ー', '〜', '～',
            '「', '」', '『', '』', '【', '】', '（', '）', '(', ')',
            '・', '：', ':', '；', ';', '♪', '♡', '♥',
        )
    }
}
