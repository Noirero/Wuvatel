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

/**
 * M2.5 conservative region refinement.
 *
 * Geometry/grouping is never changed here. M2.3 remains the baseline and a
 * region-level OCR result is accepted only when it is sufficiently similar,
 * conservatively better, and (for vertical text) supported by a second pass.
 */
class RegionOcrRefiner {
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    suspend fun refine(
        bitmap: Bitmap,
        regions: List<TextRegion>,
    ): List<TextRegion> {
        if (regions.isEmpty()) return regions

        val output = ArrayList<TextRegion>(regions.size)
        for (region in regions) {
            output += refineRegion(bitmap, region)
        }
        return output
    }

    private suspend fun refineRegion(
        source: Bitmap,
        region: TextRegion,
    ): TextRegion {
        val baseline = cleanupText(region.text)
        val vertical = isLikelyVertical(region.boundingBox)

        return try {
            val candidates = mutableListOf<String>()

            readRegionVariant(
                source = source,
                region = region,
                vertical = vertical,
                paddingRatio = NORMAL_PADDING_RATIO,
                contrast = NORMAL_CONTRAST,
            )?.let(candidates::add)

            if (vertical) {
                readRegionVariant(
                    source = source,
                    region = region,
                    vertical = true,
                    paddingRatio = TIGHT_PADDING_RATIO,
                    contrast = TIGHT_CONTRAST,
                )?.let(candidates::add)
            }

            region.copy(
                text = chooseConservativeText(
                    baseline = baseline,
                    rawCandidates = candidates,
                    vertical = vertical,
                ),
            )
        } catch (_: Throwable) {
            region.copy(text = baseline)
        }
    }

    private suspend fun readRegionVariant(
        source: Bitmap,
        region: TextRegion,
        vertical: Boolean,
        paddingRatio: Float,
        contrast: Float,
    ): String? {
        val cropRect = paddedRect(
            box = region.boundingBox,
            imageWidth = source.width,
            imageHeight = source.height,
            paddingRatio = paddingRatio,
        )

        if (cropRect.width() < MIN_REGION_SIZE_PX ||
            cropRect.height() < MIN_REGION_SIZE_PX
        ) {
            return null
        }

        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        var enhanced: Bitmap? = null

        return try {
            crop = Bitmap.createBitmap(
                source,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
            )

            val scale = scaleForRegion(crop.width, crop.height)
            scaled = if (scale > 1.01f) {
                Bitmap.createScaledBitmap(
                    crop,
                    max(1, (crop.width * scale).toInt()),
                    max(1, (crop.height * scale).toInt()),
                    true,
                )
            } else {
                crop
            }

            enhanced = enhanceForOcr(scaled, contrast)
            val result = recognizer.process(
                InputImage.fromBitmap(enhanced, 0),
            ).await()

            cleanupText(
                textFromRegionResult(
                    result = result,
                    vertical = vertical,
                ),
            ).takeIf { it.isNotBlank() }
        } finally {
            enhanced?.recycle()
            if (scaled !== crop) scaled?.recycle()
            crop?.recycle()
        }
    }

    private fun paddedRect(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
        paddingRatio: Float,
    ): Rect {
        val safe = Rect(
            box.left.coerceIn(0, imageWidth),
            box.top.coerceIn(0, imageHeight),
            box.right.coerceIn(0, imageWidth),
            box.bottom.coerceIn(0, imageHeight),
        )

        val padX = max(
            MIN_PADDING_PX,
            (safe.width().coerceAtLeast(1) * paddingRatio).toInt(),
        )
        val padY = max(
            MIN_PADDING_PX,
            (safe.height().coerceAtLeast(1) * paddingRatio).toInt(),
        )

        return Rect(
            max(0, safe.left - padX),
            max(0, safe.top - padY),
            min(imageWidth, safe.right + padX),
            min(imageHeight, safe.bottom + padY),
        )
    }

    private fun scaleForRegion(width: Int, height: Int): Float {
        val longestSide = max(width, height).coerceAtLeast(1)
        val memoryLimitedScale = MAX_REFINED_SIDE.toFloat() / longestSide
        return min(TARGET_SCALE, memoryLimitedScale).coerceAtLeast(1f)
    }

    private fun enhanceForOcr(source: Bitmap, contrast: Float): Bitmap {
        val output = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        val offset = 128f * (1f - contrast)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
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

    private fun textFromRegionResult(
        result: Text,
        vertical: Boolean,
    ): String {
        val lines = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim()
                if (value.isBlank()) return@mapNotNull null
                RegionLine(value, Rect(box))
            }
        }

        if (lines.isEmpty()) return result.text.trim()

        val ordered = if (vertical) {
            lines.sortedWith(
                compareByDescending<RegionLine> { it.box.centerX() }
                    .thenBy { it.box.top },
            )
        } else {
            lines.sortedWith(
                compareBy<RegionLine> { it.box.top }
                    .thenBy { it.box.left },
            )
        }

        return ordered.joinToString(separator = if (vertical) "" else "\n") { it.text }
    }

    private fun chooseConservativeText(
        baseline: String,
        rawCandidates: List<String>,
        vertical: Boolean,
    ): String {
        val candidates = rawCandidates
            .map(::cleanupText)
            .filter { it.isNotBlank() }
            .distinct()

        if (candidates.isEmpty()) return baseline
        if (baseline.isBlank()) {
            return candidates.maxByOrNull(::normalizedQuality) ?: baseline
        }

        val baseNoise = noiseCount(baseline)
        val baseScore = normalizedQuality(baseline)
        val baseCompact = compactForCompare(baseline)

        val eligible = candidates.filter { candidate ->
            val candidateCompact = compactForCompare(candidate)
            val similarity = editSimilarity(baseCompact, candidateCompact)
            if (similarity < MIN_BASELINE_SIMILARITY) return@filter false

            val candidateNoise = noiseCount(candidate)
            val candidateScore = normalizedQuality(candidate)
            val baseJapanese = baseline.count(::isJapaneseScript)
            val candidateJapanese = candidate.count(::isJapaneseScript)

            if (baseJapanese >= MIN_JAPANESE_FOR_COMPARE &&
                candidateJapanese < (baseJapanese * MIN_JAPANESE_RATIO).toInt()
            ) {
                return@filter false
            }

            if (hasAmbiguousKanjiReplacement(baseline, candidate) &&
                candidateNoise >= baseNoise
            ) {
                return@filter false
            }

            val lengthDelta = abs(visibleLength(candidate) - visibleLength(baseline))
            if (lengthDelta > 0 &&
                candidateNoise >= baseNoise &&
                candidateScore < baseScore + LENGTH_CHANGE_SCORE_ADVANTAGE
            ) {
                return@filter false
            }

            true
        }

        if (eligible.isEmpty()) return baseline

        val ranked = eligible.map { candidate ->
            val consensus = if (vertical) {
                candidates.count { other ->
                    editSimilarity(
                        compactForCompare(candidate),
                        compactForCompare(other),
                    ) >= MIN_PASS_CONSENSUS_SIMILARITY
                }
            } else {
                1
            }
            CandidateVote(candidate, consensus)
        }.sortedWith(
            compareByDescending<CandidateVote> { it.consensus }
                .thenByDescending { normalizedQuality(it.text) },
        )

        val best = ranked.first().text
        val bestConsensus = ranked.first().consensus
        val similarity = editSimilarity(baseCompact, compactForCompare(best))
        val bestNoise = noiseCount(best)
        val bestScore = normalizedQuality(best)
        val sameLength = visibleLength(best) == visibleLength(baseline)

        if (bestNoise < baseNoise &&
            similarity >= MIN_NOISE_FIX_SIMILARITY &&
            bestScore >= baseScore - NOISE_SCORE_TOLERANCE
        ) {
            return best
        }

        if (vertical &&
            bestConsensus >= REQUIRED_VERTICAL_CONSENSUS &&
            similarity >= MIN_CONSENSUS_BASELINE_SIMILARITY &&
            sameLength &&
            bestScore >= baseScore - CONSENSUS_SCORE_TOLERANCE
        ) {
            return best
        }

        if (!vertical &&
            sameLength &&
            similarity >= MIN_SINGLE_PASS_SIMILARITY &&
            bestScore >= baseScore + SINGLE_PASS_SCORE_ADVANTAGE
        ) {
            return best
        }

        return baseline
    }

    private fun hasAmbiguousKanjiReplacement(
        baseline: String,
        candidate: String,
    ): Boolean {
        val first = compactForCompare(baseline)
        val second = compactForCompare(candidate)
        if (first.length != second.length) return false

        return first.indices.any { index ->
            first[index] != second[index] &&
                isKanji(first[index]) &&
                isKanji(second[index])
        }
    }

    private fun editSimilarity(first: String, second: String): Float {
        if (first == second) return 1f
        if (first.isEmpty() || second.isEmpty()) return 0f

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                val substitution = previous[j] + if (first[i] == second[j]) 0 else 1
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        val distance = previous[second.length]
        val longest = max(first.length, second.length).coerceAtLeast(1)
        return 1f - distance.toFloat() / longest.toFloat()
    }

    private fun compactForCompare(text: String): String =
        cleanupText(text).filterNot { it.isWhitespace() }

    private fun cleanupText(raw: String): String {
        var text = normalizeFullWidthLatin(
            Normalizer.normalize(raw, Normalizer.Form.NFC),
        )
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .trim()

        if (text.isBlank()) return text

        val visible = visibleLength(text).coerceAtLeast(1)
        val japanese = text.count(::isJapaneseScript)
        val japaneseDominant =
            japanese >= MIN_JAPANESE_FOR_CLEANUP &&
                japanese.toFloat() / visible >= JAPANESE_DOMINANCE_RATIO

        if (!japaneseDominant) {
            return text.replace(Regex("[\\t ]{2,}"), " ")
        }

        val chars = text.toCharArray()
        text = buildString(chars.size) {
            chars.forEachIndexed { index, char ->
                when {
                    char == '|' || char == '｜' || char == '¦' -> Unit
                    isIsolatedForeignLetter(chars, index) -> Unit
                    char.isWhitespace() && char != '\n' &&
                        removeJapaneseWhitespace(chars, index) -> Unit
                    else -> append(char)
                }
            }
        }

        text = removeUnbalancedPair(text, '(', ')')
        text = removeUnbalancedPair(text, '[', ']')

        return text
            .replace(Regex("[\\t ]{2,}"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun normalizeFullWidthLatin(text: String): String =
        buildString(text.length) {
            text.forEach { char ->
                append(
                    when (char.code) {
                        in 0xFF21..0xFF3A -> (char.code - 0xFEE0).toChar()
                        in 0xFF41..0xFF5A -> (char.code - 0xFEE0).toChar()
                        else -> char
                    },
                )
            }
        }

    private fun isIsolatedForeignLetter(chars: CharArray, index: Int): Boolean {
        val char = chars[index]
        if (!isForeignLetter(char)) return false

        if (index > 0 && isForeignLetter(chars[index - 1])) return false
        if (index + 1 < chars.size && isForeignLetter(chars[index + 1])) return false

        val previous = previousVisible(chars, index)
        val next = nextVisible(chars, index)
        return (previous == null || isJapaneseOrPunctuation(previous)) &&
            (next == null || isJapaneseOrPunctuation(next))
    }

    private fun removeJapaneseWhitespace(chars: CharArray, index: Int): Boolean {
        val previous = previousVisible(chars, index)
        val next = nextVisible(chars, index)
        return previous?.let(::isJapaneseOrPunctuation) == true &&
            next?.let(::isJapaneseOrPunctuation) == true
    }

    private fun previousVisible(chars: CharArray, index: Int): Char? {
        var cursor = index - 1
        while (cursor >= 0) {
            if (!chars[cursor].isWhitespace()) return chars[cursor]
            cursor--
        }
        return null
    }

    private fun nextVisible(chars: CharArray, index: Int): Char? {
        var cursor = index + 1
        while (cursor < chars.size) {
            if (!chars[cursor].isWhitespace()) return chars[cursor]
            cursor++
        }
        return null
    }

    private fun removeUnbalancedPair(text: String, open: Char, close: Char): String {
        if (text.count { it == open } == text.count { it == close }) return text
        return text.filter { it != open && it != close }
    }

    private fun normalizedQuality(text: String): Float {
        val visible = visibleLength(text).coerceAtLeast(1)
        val japanese = text.count(::isJapaneseScript)
        val kana = text.count(::isKana)
        val foreign = text.count(::isForeignLetter)
        val punctuation = text.count(::isJapanesePunctuation)
        val score =
            japanese * 2.5f +
                kana * 0.35f +
                punctuation * 0.10f -
                foreign * 1.75f -
                noiseCount(text) * 2.0f
        return score / visible.toFloat()
    }

    private fun noiseCount(text: String): Int {
        val normalized = normalizeFullWidthLatin(text)
        val visible = visibleLength(normalized).coerceAtLeast(1)
        val japanese = normalized.count(::isJapaneseScript)
        if (japanese < MIN_JAPANESE_FOR_CLEANUP ||
            japanese.toFloat() / visible < JAPANESE_DOMINANCE_RATIO
        ) {
            return 0
        }

        val chars = normalized.toCharArray()
        var count = 0
        chars.forEachIndexed { index, char ->
            when {
                char == '|' || char == '｜' || char == '¦' || char == '\uFFFD' -> count++
                isIsolatedForeignLetter(chars, index) -> count++
            }
        }

        if (normalized.count { it == '(' } != normalized.count { it == ')' }) count++
        if (normalized.count { it == '[' } != normalized.count { it == ']' }) count++
        return count
    }

    private fun visibleLength(text: String): Int = text.count { !it.isWhitespace() }

    private fun isLikelyVertical(box: Rect): Boolean =
        box.height() > box.width() * VERTICAL_REGION_RATIO

    private fun isJapaneseScript(char: Char): Boolean = isKana(char) || isKanji(char)

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

    private fun isForeignLetter(char: Char): Boolean =
        Character.isLetter(char) && !isJapaneseScript(char)

    private fun isJapanesePunctuation(char: Char): Boolean =
        char in JAPANESE_PUNCTUATION

    private fun isJapaneseOrPunctuation(char: Char): Boolean =
        isJapaneseScript(char) || isJapanesePunctuation(char)

    fun close() {
        recognizer.close()
    }

    private data class RegionLine(
        val text: String,
        val box: Rect,
    )

    private data class CandidateVote(
        val text: String,
        val consensus: Int,
    )

    private companion object {
        const val VERTICAL_REGION_RATIO = 1.10f
        const val NORMAL_PADDING_RATIO = 0.08f
        const val TIGHT_PADDING_RATIO = 0.035f
        const val MIN_PADDING_PX = 5
        const val MIN_REGION_SIZE_PX = 8

        const val TARGET_SCALE = 2.6f
        const val MAX_REFINED_SIDE = 1700
        const val NORMAL_CONTRAST = 1.38f
        const val TIGHT_CONTRAST = 1.52f

        const val MIN_BASELINE_SIMILARITY = 0.68f
        const val MIN_NOISE_FIX_SIMILARITY = 0.72f
        const val MIN_CONSENSUS_BASELINE_SIMILARITY = 0.78f
        const val MIN_SINGLE_PASS_SIMILARITY = 0.86f
        const val MIN_PASS_CONSENSUS_SIMILARITY = 0.92f
        const val REQUIRED_VERTICAL_CONSENSUS = 2

        const val MIN_JAPANESE_FOR_COMPARE = 4
        const val MIN_JAPANESE_RATIO = 0.75f
        const val LENGTH_CHANGE_SCORE_ADVANTAGE = 0.10f
        const val SINGLE_PASS_SCORE_ADVANTAGE = 0.14f
        const val NOISE_SCORE_TOLERANCE = 0.08f
        const val CONSENSUS_SCORE_TOLERANCE = 0.03f

        const val MIN_JAPANESE_FOR_CLEANUP = 2
        const val JAPANESE_DOMINANCE_RATIO = 0.55f

        val JAPANESE_PUNCTUATION = setOf(
            '。', '、', '！', '？', '!', '?', '…', '‥', 'ー', '〜', '～',
            '「', '」', '『', '』', '【', '】', '（', '）', '(', ')',
            '・', '：', ':', '；', ';', '♪', '♡', '♥',
        )
    }
}
