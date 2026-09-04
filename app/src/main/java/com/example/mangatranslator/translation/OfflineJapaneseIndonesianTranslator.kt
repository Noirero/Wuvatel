package com.example.mangatranslator.translation

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * M3.1.10 translator-owned preparation.
 *
 * RemoteModelManager remains diagnostic only. It is no longer allowed to decide
 * whether Wuvatel must go online, because the real translator path already proved
 * more reliable on the target device. Translator.downloadModelIfNeeded() is the
 * single source of truth: cached models are reused without a Wuvatel network probe;
 * missing models are downloaded by ML Kit when connectivity is available.
 */
class OfflineJapaneseIndonesianTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build(),
    )

    private val modelManager = RemoteModelManager.getInstance()
    private val japaneseModel = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
    private val indonesianModel = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build()
    private val downloadConditions = DownloadConditions.Builder().build()

    suspend fun ensureModel(
        onStatus: (String) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        fun log(stage: String, message: String) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val line = "+${elapsed}ms [$stage] $message"
            Log.i(TAG, line)
            onLog(line)
        }

        log(
            "START",
            "Wuvatel M3.1.10 · translator-owned cache · Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
        )

        onStatus("Memeriksa status model lokal…")
        val japaneseBefore = isModelDownloadedDiagnostic(
            model = japaneseModel,
            label = "JA",
            threadName = "wuvatel-check-ja",
            log = ::log,
        )
        val indonesianBefore = isModelDownloadedDiagnostic(
            model = indonesianModel,
            label = "ID",
            threadName = "wuvatel-check-id",
            log = ::log,
        )
        log(
            "MODEL-DIAG",
            "RemoteModelManager sebelum prepare: JA=${formatDiagnostic(japaneseBefore)}, " +
                "ID=${formatDiagnostic(indonesianBefore)}. Hasil ini hanya diagnostik.",
        )

        onStatus("Menyiapkan model translator…")
        prepareTranslatorModels(
            onStatus = onStatus,
            log = ::log,
        )

        onStatus("Memverifikasi translator…")
        val japaneseAfter = isModelDownloadedDiagnostic(
            model = japaneseModel,
            label = "JA-final",
            threadName = "wuvatel-final-ja",
            log = ::log,
        )
        val indonesianAfter = isModelDownloadedDiagnostic(
            model = indonesianModel,
            label = "ID-final",
            threadName = "wuvatel-final-id",
            log = ::log,
        )
        log(
            "MODEL-DIAG-FINAL",
            "RemoteModelManager setelah prepare: JA=${formatDiagnostic(japaneseAfter)}, " +
                "ID=${formatDiagnostic(indonesianAfter)}. Tidak dipakai sebagai gerbang runtime.",
        )

        log(
            "READY",
            "Translator.downloadModelIfNeeded() selesai. Translator JP → ID siap digunakan.",
        )
        onStatus("Model translator siap. Memulai terjemahan…")
        return "Translator JP → ID siap"
    }

    private suspend fun isModelDownloadedDiagnostic(
        model: TranslateRemoteModel,
        label: String,
        threadName: String,
        log: (String, String) -> Unit,
    ): Boolean? {
        log("CHECK-$label", "Memanggil RemoteModelManager.isModelDownloaded() untuk diagnostik")
        return try {
            val result = withTimeout(MODEL_QUERY_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = threadName,
                    taskFactory = { modelManager.isModelDownloaded(model) },
                )
            }
            log("CHECK-$label", "Task selesai: isModelDownloaded=$result")
            result
        } catch (t: TimeoutCancellationException) {
            log("CHECK-$label-TIMEOUT", "Diagnostik model tidak selesai dalam 20 detik; diabaikan")
            null
        } catch (t: Throwable) {
            log("CHECK-$label-ERROR", "Diagnostik diabaikan: ${describeFailure("cek model $label", t)}")
            null
        }
    }

    private suspend fun prepareTranslatorModels(
        onStatus: (String) -> Unit,
        log: (String, String) -> Unit,
    ) {
        onStatus("Menyiapkan model JP → ID dari cache ML Kit…")
        log(
            "PREPARE",
            "Memanggil Translator.downloadModelIfNeeded() tanpa probe internet Wuvatel",
        )

        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-translator-model-prepare",
                    taskFactory = {
                        log(
                            "PREPARE",
                            "Worker masuk; meminta translator memakai cache atau download bila perlu",
                        )
                        val task = translator.downloadModelIfNeeded(downloadConditions)
                        log("PREPARE", "SDK mengembalikan Task; menunggu task selesai")
                        task
                    },
                )
            }
            log(
                "PREPARE-DONE",
                "downloadModelIfNeeded() selesai. Tidak ada probe HTTP dari Wuvatel.",
            )
        } catch (t: TimeoutCancellationException) {
            val message = "downloadModelIfNeeded() tidak selesai dalam 180 detik"
            log("PREPARE-TIMEOUT", message)
            throw IllegalStateException(message, t)
        } catch (t: Throwable) {
            val message = describeFailure("downloadModelIfNeeded", t)
            log("PREPARE-ERROR", message)
            throw IllegalStateException(
                "$message. Jika perangkat sedang offline dan model benar-benar belum tersimpan, " +
                    "aktifkan internet sekali untuk menyiapkan model.",
                t,
            )
        }
    }

    private fun formatDiagnostic(value: Boolean?): String = when (value) {
        true -> "READY"
        false -> "MISSING"
        null -> "UNKNOWN"
    }

    private suspend fun <T> awaitMlKitTaskOffMain(
        threadName: String,
        taskFactory: () -> Task<T>,
    ): T {
        val result = CompletableDeferred<T>()

        Thread(
            {
                try {
                    result.complete(Tasks.await(taskFactory()))
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            },
            threadName,
        ).apply {
            isDaemon = true
            start()
        }

        return result.await()
    }

    suspend fun translate(
        text: String,
        onLog: (String) -> Unit = {},
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        onLog("+0ms [TRANSLATE] Memulai translate() untuk ${text.length} karakter")
        try {
            val translated = withTimeout(TRANSLATION_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-mlkit-translate",
                    taskFactory = { translator.translate(text) },
                ).trim()
            }
            onLog(
                "+${SystemClock.elapsedRealtime() - startedAt}ms [TRANSLATE] Selesai; " +
                    "hasil ${translated.length} karakter",
            )
            return translated
        } catch (t: TimeoutCancellationException) {
            val message = "translate() tidak selesai selama 30 detik"
            onLog("+${SystemClock.elapsedRealtime() - startedAt}ms [TRANSLATE-TIMEOUT] $message")
            throw IllegalStateException(message, t)
        } catch (t: Throwable) {
            val message = describeFailure("translate", t)
            onLog("+${SystemClock.elapsedRealtime() - startedAt}ms [TRANSLATE-ERROR] $message")
            throw IllegalStateException(message, t)
        }
    }

    fun diagnosticMessage(error: Throwable): String {
        val readable = describeFailure("runtime", error)
        val chain = buildCauseChain(error)
        return if (chain.isBlank()) readable else "$readable | cause: $chain"
    }

    private fun describeFailure(stage: String, error: Throwable): String {
        val mlKitError = findMlKitException(error)
        if (mlKitError != null) {
            return readableMlKitError(mlKitError, stage)
        }

        val type = error::class.java.simpleName.ifBlank { error::class.java.name }
        val message = error.message ?: error.cause?.message ?: "tanpa pesan error"
        return "$stage gagal [$type]: $message"
    }

    private fun buildCauseChain(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        repeat(8) {
            val value = current ?: return@repeat
            val type = value::class.java.simpleName.ifBlank { value::class.java.name }
            val extra = if (value is MlKitException) "(code=${value.errorCode})" else ""
            parts += "$type$extra"
            current = value.cause
        }
        return parts.distinct().joinToString(" -> ")
    }

    private fun findMlKitException(error: Throwable): MlKitException? {
        var current: Throwable? = error
        repeat(8) {
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
        const val TAG = "WuvatelTranslation"
        const val MODEL_QUERY_TIMEOUT_MS = 20_000L
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 180_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
