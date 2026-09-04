package com.example.mangatranslator.ocr

import android.graphics.Rect

data class TextRegion(
    val text: String,
    val boundingBox: Rect,
    val reviewed: Boolean = false,
    val translation: String? = null,
    val translationReviewed: Boolean = false,
)
