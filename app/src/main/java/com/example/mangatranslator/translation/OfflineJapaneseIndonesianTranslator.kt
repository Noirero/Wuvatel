package com.example.mangatranslator.translation

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * M3.1.11 - direct cache probe.
 *
 * RemoteModelManager remains diagnostic only. Before starting any model download,
 * Wuvatel directly calls Translator.translate() with a tiny Japanese probe. ML Kit
 * documents translate() as failing with NOT_FOUND when model files are absent, so
 * a successful probe proves the translator can really use its local model cache.
 * Only when that probe fails do we call the no-argument downloadModelIfNeeded().
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
            "Wuvatel M3.1.11 · direct cache probe · Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
        )

        onStatus("Memeriksa cache model ML Kit…")
        logRemoteModelDiagnostics("awal", log = ::log)

        onStatus("Menguji model lokal tanpa download…")
        if (probeTranslatorCache(log = ::log)) {
            log(
                "READY",
                "Probe translate lokal berhasil; model JP → ID benar-benar bisa dipakai tanpa download.",
            )
            onStatus("Model lokal siap. Internet tidak diperlukan.")
            return "Cache translator JP → ID terverifikasi"
        }

        onStatus("Model lokal belum bisa dipakai. Menyiapkan lewat ML Kit…")
        prepareTranslatorModels(
            onStatus = onStatus,
            log = ::log,
        )

        onStatus("Memverifikasi model setelah download…")
        if (!probeTranslatorCache(log = ::log, stageSuffix = "-FINAL")) {
            val message = "downloadModelIfNeeded() selesai, tetapi translate() masih tidak dapat memakai model lokal"
            log("VERIFY-ERROR", message)
            throw IllegalStateException(message)
        }

        logRemoteModelDiagnostics("akhir", log = ::log)
        log(
            "READY",
            "Download/prepare selesai dan probe translate lokal berhasil. Model JP → ID siap.",
        )
        onStatus("Model translator siap. Memulai terjemahan…")
        return "Translator JP → ID siap dan cache terverifikasi"
    }

    private suspend fun logRemoteModelDiagnostics(
        phase: String,
        log: (String, String) -> Unit,
    ) {
        val japanese = isModelDownloadedDiagnostic(
            model = japaneseModel,
            label = "JA-$phase",
            threadName = "wuvatel-check-ja-$phase",
            log = log,
        )
        val indonesian = isModelDownloadedDiagnostic(
            model = indonesianModel,
            label = "ID-$phase",
            threadName = "wuvatel-check-id-$phase",
            log = log,
        )
        val downloadedLanguages = downloadedLanguagesDiagnostic(
            phase = phase,
            log = log,
        )
        log(
            "MODEL-DIAG",
            "$phase: JA=${formatDiagnostic(japanese)}, ID=${formatDiagnostic(indonesian)}, " +
                "getDownloadedModels=${downloadedLanguages ?: "UNKNOWN"}. Hanya diagnostik.",
        )
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

    private suspend fun downloadedLanguagesDiagnostic(
        phase: String,
        log: (String, String) -> Unit,
    ): String? {
        log("LIST-$phase", "Memanggil RemoteModelManager.getDownloadedModels(TranslateRemoteModel)")
        return try {
            val models = withTimeout(MODEL_QUERY_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-list-models-$phase",
                    taskFactory = {
                        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                    },
                )
            }
            val languages = models.map { it.language }.sorted()
            val result = if (languages.isEmpty()) "EMPTY" else languages.joinToString(",")
            log("LIST-$phase", "Model terdaftar: $result")
            result
        } catch (t: TimeoutCancellationException) {
            log("LIST-$phase-TIMEOUT", "getDownloadedModels() tidak selesai; diabaikan")
            null
        } catch (t: Throwable) {
            log("LIST-$phase-ERROR", "Diagnostik diabaikan: ${describeFailure("list model", t)}")
            null
        }
    }

    private suspend fun probeTranslatorCache(
        log: (String, String) -> Unit,
        stageSuffix: String = "",
    ): Boolean {
        val stage = "CACHE-PROBE$stageSuffix"
        log(stage, "Memanggil translate() langsung tanpa download untuk membuktikan cache lokal")
        return try {
            val result = withTimeout(CACHE_PROBE_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-cache-probe${stageSuffix.lowercase()}",
                    taskFactory = { translator.translate(CACHE_PROBE_TEXT) },
                )
            }
            log("$stage-DONE", "Probe lokal berhasil: hasil ${result.trim().length} karakter")
            true
        } catch (t: TimeoutCancellationException) {
            log("$stage-TIMEOUT", "Probe translate lokal tidak selesai dalam 15 detik")
            false
        } catch (t: Throwable) {
            log("$stage-MISS", describeFailure("probe cache translator", t))
            false
        }
    }

    private suspend fun prepareTranslatorModels(
        onStatus: (String) -> Unit,
        log: (String, String) -> Unit,
    ) {
        onStatus("Menyiapkan / mengunduh model JP → ID…")
        log(
            "PREPARE",
            "Memanggil overload tanpa argumen Translator.downloadModelIfNeeded()",
        )

        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-translator-model-prepare",
                    taskFactory = {
                        log("PREPARE", "Worker masuk; memanggil downloadModelIfNeeded() tanpa DownloadConditions")
                        val task = translator.downloadModelIfNeeded()
                        log("PREPARE", "SDK mengembalikan Task; menunggu task selesai")
                        task
                    },
                )
            }
            log("PREPARE-DONE", "downloadModelIfNeeded() selesai")
        } catch (t: TimeoutCancellationException) {
            val message = "downloadModelIfNeeded() tidak selesai dalam 120 detik"
            log("PREPARE-TIMEOUT", message)
            throw IllegalStateException(message, t)
        } catch (t: Throwable) {
            val message = describeFailure("downloadModelIfNeeded", t)
            log("PREPARE-ERROR", message)
            throw IllegalStateException(message, t)
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
        const val CACHE_PROBE_TEXT = "おはよう"
        const val MODEL_QUERY_TIMEOUT_MS = 20_000L
        const val CACHE_PROBE_TIMEOUT_MS = 15_000L
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 120_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
