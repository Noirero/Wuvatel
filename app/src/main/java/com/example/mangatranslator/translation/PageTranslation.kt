package com.example.mangatranslator.translation

import com.example.mangatranslator.ocr.TextRegion

/**
 * Stable page-level contract for contextual translation.
 *
 * Region IDs preserve manga reading order so context-capable providers can
 * translate a whole page without losing the mapping back to speech bubbles.
 * ML Kit may still execute region-by-region behind the same contract.
 */
data class PageTranslationRequest(
    val regions: List<PageTranslationRegion>,
)

data class PageTranslationRegion(
    val id: String,
    val japanese: String,
)

data class PageTranslationResult(
    val translations: Map<String, String>,
)

fun List<TextRegion>.toPageTranslationRequest(): PageTranslationRequest =
    PageTranslationRequest(
        regions = mapIndexedNotNull { index, region ->
            region.text.trim().takeIf { it.isNotBlank() }?.let { japanese ->
                PageTranslationRegion(
                    id = "R%02d".format(index + 1),
                    japanese = japanese,
                )
            }
        },
    )
