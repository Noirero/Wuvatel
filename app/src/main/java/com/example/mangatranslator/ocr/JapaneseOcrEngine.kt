package com.example.mangatranslator.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class JapaneseOcrEngine {
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> {
        // OCR the exact bitmap shown by the UI so bounding-box coordinates
        // always use the same pixel coordinate space as the preview.
        val input = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(input).await()

        return result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim()
                if (value.isBlank()) return@mapNotNull null
                TextRegion(text = value, boundingBox = box)
            }
    }

    fun close() {
        recognizer.close()
    }
}
