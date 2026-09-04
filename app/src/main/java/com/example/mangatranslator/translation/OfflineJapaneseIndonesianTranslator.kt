package com.example.mangatranslator.translation

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * M3.1.2 offline-first Japanese -> Indonesian translation.
 *
 * Download translation language models explicitly through RemoteModelManager.
 * This avoids an indefinite wait inside Translator.downloadModelIfNeeded().
 */
class OfflineJapaneseIndonesianTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build(),
    )

    private val modelManager = RemoteModelManager.getInstance()
    private val japaneseModel = TranslateRemoteModel.Builder(
        TranslateLanguage.JAPANESE,
    ).build()
    private val indonesianModel = TranslateRemoteModel.Builder(
        TranslateLanguage.INDONESIAN,
    ).build()

    suspend fun ensureModel() {
        try {
            ensureDownloaded(japaneseModel, "Jepang")
            ensureDownloaded(indonesianModel, "Indonesia")
        } catch (t: MlKitException) {
            throw IllegalStateException(readableMlKitError(t), t)
        }
    }

    private suspend fun ensureDownloaded(
        model: TranslateRemoteModel,
        label: String,
    ) {
        if (modelManager.isModelDownloaded(model).await()) return

        val conditions = DownloadConditions.Builder().build()
        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                modelManager.download(model, conditions).await()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Download model $label tidak merespons selama 2 menit. " +
                    "Coba koneksi lain lalu tekan Terjemahkan lagi.",
                t,
            )
        }

        if (!modelManager.isModelDownloaded(model).await()) {
            throw IllegalStateException(
                "Model $label belum tersedia setelah proses download selesai. " +
                    "Tekan Terjemahkan lagi.",
            )
        }
    }

    suspend fun translate(text: String): String =
        translator.translate(text).await().trim()

    private fun readableMlKitError(error: MlKitException): String =
        when (error.errorCode) {
            MlKitException.NETWORK_ISSUE ->
                "Jaringan bermasalah saat mengunduh model ML Kit. " +
                    "Coba koneksi lain lalu ulangi. (kode ${error.errorCode})"

            MlKitException.NOT_ENOUGH_SPACE ->
                "Penyimpanan perangkat tidak cukup untuk model terjemahan. " +
                    "Kosongkan ruang lalu ulangi. (kode ${error.errorCode})"

            MlKitException.UNAVAILABLE ->
                "Layanan/model ML Kit sedang tidak tersedia. " +
                    "Coba lagi beberapa saat. (kode ${error.errorCode})"

            MlKitException.UNSUPPORTED ->
                "Fitur terjemahan ML Kit tidak didukung pada perangkat ini. " +
                    "(kode ${error.errorCode})"

            MlKitException.PERMISSION_DENIED ->
                "ML Kit tidak mendapat izin untuk mengunduh model. " +
                    "(kode ${error.errorCode})"

            MlKitException.NOT_FOUND ->
                "Model terjemahan belum ditemukan di perangkat/server. " +
                    "Coba ulangi download. (kode ${error.errorCode})"

            else ->
                "ML Kit gagal menyiapkan model (kode ${error.errorCode}): " +
                    (error.message ?: "error tidak diketahui")
        }

    fun close() {
        translator.close()
    }

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 120_000L
    }
}
