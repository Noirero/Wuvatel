package com.example.mangatranslator.ocr

import android.graphics.Rect

data class TextRegion(
    val text: String,
    val boundingBox: Rect,
)
