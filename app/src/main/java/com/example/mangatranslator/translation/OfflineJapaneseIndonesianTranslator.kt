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
 * M3.1.3 offline-first Japanese -> Indonesian translation.
 *
 * Request both translation language models directly. Do not call
 * isModelDownloaded() before the request because that status Task itself can
 * stall on some devices and prevent the actual download timeout from starting.
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
    private val downloadConditions = DownloadConditions.Builder().build()

    suspend fun ensureModel() {
        downloadModel(japaneseModel, "Jepang")
        downloadModel(indonesianModel, "Indonesia")
    }

    private suspend fun downloadModel(
        model: TranslateRemoteModel,
        label: String,
    ) {
        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                // RemoteModelManager.download() is safe to call when a model is
                // already present; ML Kit resolves the request without needing
                // a separate pre-flight status check.
                modelManager.download(model, downloadConditions).await()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Permintaan model $label tidak merespons selama 90 detik. " +
                    "Coba koneksi lain lalu tekan Terjemahkan lagi.",
                t,
            )
        } catch (t: MlKitException) {
            throw IllegalStateException(readableMlKitError(t, label), t)
        }
    }

    suspend fun translate(text: String): String {
        try {
            return withTimeout(TRANSLATION_TIMEOUT_MS) {
                translator.translate(text).await().trim()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Model sudah diminta, tetapi proses terjemahan tidak merespons selama 30 detik.",
                t,
            )
        } catch (t: MlKitException) {
            throw IllegalStateException(readableMlKitError(t, "terjemahan"), t)
        }
    }

    private fun readableMlKitError(error: MlKitException, stage: String): String =
        when (error.errorCode) {
            MlKitException.NETWORK_ISSUE ->
                "Jaringan bermasalah saat menyiapkan $stage. " +
                    "Coba koneksi lain lalu ulangi. (kode ${error.errorCode})"

            MlKitException.NOT_ENOUGH_SPACE ->
                "Penyimpanan perangkat tidak cukup untuk model $stage. " +
                    "Kosongkan ruang lalu ulangi. (kode ${error.errorCode})"

            MlKitException.UNAVAILABLE ->
                "Layanan/model ML Kit untuk $stage sedang tidak tersedia. " +
                    "Coba lagi beberapa saat. (kode ${error.errorCode})"

            MlKitException.UNSUPPORTED ->
                "Fitur ML Kit untuk $stage tidak didukung pada perangkat ini. " +
                    "(kode ${error.errorCode})"

            MlKitException.PERMISSION_DENIED ->
                "ML Kit tidak mendapat izin saat menyiapkan $stage. " +
                    "(kode ${error.errorCode})"

            MlKitException.NOT_FOUND ->
                "Model $stage tidak ditemukan di perangkat/server. " +
                    "Coba ulangi download. (kode ${error.errorCode})"

            else ->
                "ML Kit gagal pada tahap $stage (kode ${error.errorCode}): " +
                    (error.message ?: "error tidak diketahui")
        }

    fun close() {
        translator.close()
    }

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 90_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
