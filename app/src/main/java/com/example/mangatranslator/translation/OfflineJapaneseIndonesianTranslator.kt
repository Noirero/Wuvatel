package com.example.mangatranslator.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * M3.1 offline-first Japanese -> Indonesian translation.
 *
 * The language model is downloaded on demand once. After the model is present
 * on the device, translations run on-device and do not require an API key.
 */
class OfflineJapaneseIndonesianTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build(),
    )

    suspend fun ensureModel() {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    suspend fun translate(text: String): String =
        translator.translate(text).await().trim()

    fun close() {
        translator.close()
    }
}
