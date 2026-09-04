package com.example.mangatranslator.translation

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M3.1.4 offline-first Japanese -> Indonesian translation.
 *
 * Use Translator.downloadModelIfNeeded() as the primary path, matching the
 * current ML Kit documentation/sample. RemoteModelManager is only used after
 * success to expose useful diagnostics about models visible to this app.
 */
class OfflineJapaneseIndonesianTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build(),
    )

    private val modelManager = RemoteModelManager.getInstance()
    private val downloadConditions = DownloadConditions.Builder().build()

    suspend fun ensureModel(onStatus: (String) -> Unit = {}): String {
        onStatus("Meminta model JP + ID lewat ML Kit…")
        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                translator.downloadModelIfNeeded(downloadConditions).await()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "ML Kit tidak menyelesaikan permintaan model dalam 5 menit. " +
                    "Tahap berhenti di downloadModelIfNeeded().",
                t,
            )
        } catch (t: Throwable) {
            throw IllegalStateException(describeFailure("download model", t), t)
        }

        onStatus("ML Kit melaporkan model siap. Memverifikasi model…")
        return downloadedModelsSummary()
    }

    private suspend fun downloadedModelsSummary(): String {
        val models = withTimeoutOrNull(MODEL_LIST_TIMEOUT_MS) {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        } ?: return "download selesai; daftar model tidak merespons dalam 5 detik"

        val languages = models.map { it.language }.sorted()
        return if (languages.isEmpty()) {
            "download selesai; RemoteModelManager tidak melaporkan model"
        } else {
            "tersimpan: ${languages.joinToString(", ")}"
        }
    }

    suspend fun translate(text: String): String {
        try {
            return withTimeout(TRANSLATION_TIMEOUT_MS) {
                translator.translate(text).await().trim()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Model dilaporkan siap, tetapi translate() tidak merespons selama 30 detik.",
                t,
            )
        } catch (t: Throwable) {
            throw IllegalStateException(describeFailure("translate", t), t)
        }
    }

    fun diagnosticMessage(error: Throwable): String = describeFailure("runtime", error)

    private fun describeFailure(stage: String, error: Throwable): String {
        val mlKitError = findMlKitException(error)
        if (mlKitError != null) {
            return readableMlKitError(mlKitError, stage)
        }

        val type = error::class.java.simpleName.ifBlank { error::class.java.name }
        val message = error.message ?: error.cause?.message ?: "tanpa pesan error"
        return "$stage gagal [$type]: $message"
    }

    private fun findMlKitException(error: Throwable): MlKitException? {
        var current: Throwable? = error
        repeat(6) {
            if (current is MlKitException) return current
            current = current?.cause
        }
        return null
    }

    private fun readableMlKitError(error: MlKitException, stage: String): String =
        when (error.errorCode) {
            MlKitException.NETWORK_ISSUE ->
                "$stage: NETWORK_ISSUE (kode ${error.errorCode}) - ${error.message ?: "jaringan bermasalah"}"

            MlKitException.NOT_ENOUGH_SPACE ->
                "$stage: NOT_ENOUGH_SPACE (kode ${error.errorCode}) - ${error.message ?: "ruang tidak cukup"}"

            MlKitException.UNAVAILABLE ->
                "$stage: UNAVAILABLE (kode ${error.errorCode}) - ${error.message ?: "layanan/model tidak tersedia"}"

            MlKitException.UNSUPPORTED ->
                "$stage: UNSUPPORTED (kode ${error.errorCode}) - ${error.message ?: "fitur tidak didukung"}"

            MlKitException.PERMISSION_DENIED ->
                "$stage: PERMISSION_DENIED (kode ${error.errorCode}) - ${error.message ?: "izin ditolak"}"

            MlKitException.NOT_FOUND ->
                "$stage: NOT_FOUND (kode ${error.errorCode}) - ${error.message ?: "model tidak ditemukan"}"

            else ->
                "$stage: ML Kit kode ${error.errorCode} - ${error.message ?: "error tidak diketahui"}"
        }

    fun close() {
        translator.close()
    }

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 300_000L
        const val MODEL_LIST_TIMEOUT_MS = 5_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
