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
 * M2.4 accuracy pass.
 *
 * Geometry/grouping remains owned by [JapaneseOcrEngine]. This class only
 * re-reads the text inside an already accepted region, so a refinement can
 * never move, split, merge, or delete a bounding box.
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

        // Sequential processing keeps peak memory predictable on phones.
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
        val cropRect = paddedRect(
            box = region.boundingBox,
            imageWidth = source.width,
            imageHeight = source.height,
        )

        if (cropRect.width() < MIN_REGION_SIZE_PX ||
            cropRect.height() < MIN_REGION_SIZE_PX
        ) {
            return region.copy(text = baseline)
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

            enhanced = enhanceForOcr(scaled)

            val result = recognizer.process(
                InputImage.fromBitmap(enhanced, 0),
            ).await()

            val vertical = isLikelyVertical(region.boundingBox)
            val refined = cleanupText(
                textFromRegionResult(
                    result = result,
                    vertical = vertical,
                ),
            )

            region.copy(
                text = chooseBetterText(
                    baseline = baseline,
                    refined = refined,
                ),
            )
        } catch (_: Throwable) {
            // M2.4 is optional refinement. Keep the stable M2.3 result on any
            // per-region OCR or bitmap failure.
            region.copy(text = baseline)
        } finally {
            enhanced?.recycle()
            if (scaled !== crop) {
                scaled?.recycle()
            }
            crop?.recycle()
        }
    }

    private fun paddedRect(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
    ): Rect {
        val safe = Rect(
            box.left.coerceIn(0, imageWidth),
            box.top.coerceIn(0, imageHeight),
            box.right.coerceIn(0, imageWidth),
            box.bottom.coerceIn(0, imageHeight),
        )

        val padX = max(
            MIN_PADDING_PX,
            (safe.width().coerceAtLeast(1) * PADDING_RATIO).toInt(),
        )
        val padY = max(
            MIN_PADDING_PX,
            (safe.height().coerceAtLeast(1) * PADDING_RATIO).toInt(),
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

        return min(TARGET_SCALE, memoryLimitedScale)
            .coerceAtLeast(1f)
    }

    private fun enhanceForOcr(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        val offset = 128f * (1f - REGION_CONTRAST)

        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        REGION_CONTRAST, 0f, 0f, 0f, offset,
                        0f, REGION_CONTRAST, 0f, 0f, offset,
                        0f, 0f, REGION_CONTRAST, 0f, offset,
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
                RegionLine(
                    text = value,
                    box = Rect(box),
                )
            }
        }

        if (lines.isEmpty()) {
            return result.text.trim()
        }

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

        val separator = if (vertical) "" else "\n"
        return ordered.joinToString(separator = separator) { it.text }
    }

    private fun chooseBetterText(
        baseline: String,
        refined: String,
    ): String {
        if (refined.isBlank()) return baseline
        if (baseline.isBlank()) return refined
        if (refined == baseline) return baseline

        val baseLength = visibleLength(baseline)
        val refinedLength = visibleLength(refined)

        val minLength = max(
            1,
            (baseLength * MIN_LENGTH_RATIO).toInt(),
        )
        val maxLength = max(
            baseLength + LENGTH_SLACK,
            (baseLength * MAX_LENGTH_RATIO).toInt(),
        )
        if (refinedLength !in minLength..maxLength) {
            return baseline
        }

        val baseJapanese = baseline.count(::isJapaneseScript)
        val refinedJapanese = refined.count(::isJapaneseScript)
        if (baseJapanese >= MIN_JAPANESE_FOR_COMPARE &&
            refinedJapanese < (baseJapanese * MIN_JAPANESE_RATIO).toInt()
        ) {
            return baseline
        }

        val baseNoise = noiseCount(baseline)
        val refinedNoise = noiseCount(refined)
        val baseScore = normalizedQuality(baseline)
        val refinedScore = normalizedQuality(refined)

        return when {
            refinedNoise < baseNoise &&
                refinedScore >= baseScore - NOISE_SCORE_TOLERANCE -> refined

            refinedScore >= baseScore + SCORE_ADVANTAGE -> refined

            // A plausible region-level reading is preferred when it remains
            // equally Japanese, similar in length, and adds no obvious noise.
            refinedNoise <= baseNoise &&
                refinedJapanese >= (baseJapanese * TRUST_JAPANESE_RATIO).toInt() &&
                abs(refinedLength - baseLength) <= TRUST_LENGTH_DELTA &&
                refinedScore >= baseScore - TRUST_SCORE_TOLERANCE -> refined

            else -> baseline
        }
    }

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
                    isIsolatedLowercaseForeignLetter(chars, index) -> Unit
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

    private fun isIsolatedLowercaseForeignLetter(
        chars: CharArray,
        index: Int,
    ): Boolean {
        val char = chars[index]
        if (!Character.isLowerCase(char) || !isForeignLetter(char)) {
            return false
        }

        // Keep actual Latin words such as "web" or "vs".
        if (index > 0 && isForeignLetter(chars[index - 1])) return false
        if (index + 1 < chars.size && isForeignLetter(chars[index + 1])) return false

        val previous = previousVisible(chars, index)
        val next = nextVisible(chars, index)

        return (previous == null || isJapaneseOrPunctuation(previous)) &&
            (next == null || isJapaneseOrPunctuation(next))
    }

    private fun removeJapaneseWhitespace(
        chars: CharArray,
        index: Int,
    ): Boolean {
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

    private fun removeUnbalancedPair(
        text: String,
        open: Char,
        close: Char,
    ): String {
        if (text.count { it == open } == text.count { it == close }) {
            return text
        }
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
                char == '|' || char == '｜' || char == '¦' || char == '\uFFFD' ->
                    count++
                isIsolatedLowercaseForeignLetter(chars, index) ->
                    count++
            }
        }

        if (normalized.count { it == '(' } != normalized.count { it == ')' }) count++
        if (normalized.count { it == '[' } != normalized.count { it == ']' }) count++
        return count
    }

    private fun visibleLength(text: String): Int =
        text.count { !it.isWhitespace() }

    private fun isLikelyVertical(box: Rect): Boolean =
        box.height() > box.width() * VERTICAL_REGION_RATIO

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

    private companion object {
        const val VERTICAL_REGION_RATIO = 1.10f

        const val PADDING_RATIO = 0.08f
        const val MIN_PADDING_PX = 6
        const val MIN_REGION_SIZE_PX = 8

        const val TARGET_SCALE = 2.75f
        const val MAX_REFINED_SIDE = 1800
        const val REGION_CONTRAST = 1.48f

        const val MIN_LENGTH_RATIO = 0.60f
        const val MAX_LENGTH_RATIO = 1.55f
        const val LENGTH_SLACK = 6
        const val MIN_JAPANESE_FOR_COMPARE = 4
        const val MIN_JAPANESE_RATIO = 0.65f

        const val NOISE_SCORE_TOLERANCE = 0.20f
        const val SCORE_ADVANTAGE = 0.10f
        const val TRUST_JAPANESE_RATIO = 0.80f
        const val TRUST_LENGTH_DELTA = 3
        const val TRUST_SCORE_TOLERANCE = 0.04f

        const val MIN_JAPANESE_FOR_CLEANUP = 2
        const val JAPANESE_DOMINANCE_RATIO = 0.55f

        val JAPANESE_PUNCTUATION = setOf(
            '。', '、', '！', '？', '!', '?', '…', '‥', 'ー', '〜', '～',
            '「', '」', '『', '』', '【', '】', '（', '）', '(', ')',
            '・', '：', ':', '；', ';', '♪', '♡', '♥',
        )
    }
}
