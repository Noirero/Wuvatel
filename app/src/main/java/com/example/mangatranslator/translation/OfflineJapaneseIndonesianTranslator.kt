package com.example.mangatranslator.translation

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * M3.1.1 offline-first Japanese -> Indonesian translation.
 *
 * Japanese and Indonesian are both non-English languages, so ML Kit may need
 * two language models on the first run. The models are downloaded on demand;
 * after both are present, translation runs on-device without an API key.
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
            if (modelsReady()) return

            // Use the no-argument overload so the download starts as soon as
            // any network connection is available. Do not silently wait for
            // Wi-Fi or charging conditions.
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                translator.downloadModelIfNeeded().await()
            }

            // Never start translation merely because the download Task
            // completed; verify both required language models explicitly.
            if (!modelsReady()) {
                throw IllegalStateException(
                    "ML Kit selesai menyiapkan model, tetapi model Jepang/Indonesia belum tersedia. Tekan Terjemahkan lagi.",
                )
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Download model tidak merespons selama 3 menit. Pastikan internet stabil lalu tekan Terjemahkan lagi.",
                t,
            )
        } catch (t: MlKitException) {
            throw IllegalStateException(readableMlKitError(t), t)
        }
    }

    private suspend fun modelsReady(): Boolean {
        val japaneseReady = modelManager.isModelDownloaded(japaneseModel).await()
        val indonesianReady = modelManager.isModelDownloaded(indonesianModel).await()
        return japaneseReady && indonesianReady
    }

    suspend fun translate(text: String): String =
        translator.translate(text).await().trim()

    private fun readableMlKitError(error: MlKitException): String =
        when (error.errorCode) {
            MlKitException.NETWORK_ISSUE ->
                "Jaringan bermasalah saat mengunduh model ML Kit. Coba koneksi lain lalu ulangi. (kode ${error.errorCode})"

            MlKitException.NOT_ENOUGH_SPACE ->
                "Penyimpanan perangkat tidak cukup untuk model terjemahan. Kosongkan ruang lalu ulangi. (kode ${error.errorCode})"

            MlKitException.UNAVAILABLE ->
                "Layanan/model ML Kit sedang tidak tersedia. Coba lagi beberapa saat. (kode ${error.errorCode})"

            MlKitException.UNSUPPORTED ->
                "Fitur terjemahan ML Kit tidak didukung pada perangkat ini. (kode ${error.errorCode})"

            MlKitException.PERMISSION_DENIED ->
                "ML Kit tidak mendapat izin untuk mengunduh model. (kode ${error.errorCode})"

            MlKitException.NOT_FOUND ->
                "Model terjemahan belum ditemukan di perangkat/server. Coba ulangi download. (kode ${error.errorCode})"

            else ->
                "ML Kit gagal menyiapkan model (kode ${error.errorCode}): ${error.message ?: "error tidak diketahui"}"
        }

    fun close() {
        translator.close()
    }

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
