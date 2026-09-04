package com.example.mangatranslator.translation

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * M3.1.5 Japanese -> Indonesian translation.
 *
 * The ML Kit call itself is created and awaited on a dedicated daemon worker.
 * This prevents an OEM/SDK synchronous stall inside downloadModelIfNeeded()
 * from blocking Compose's main thread and defeating the coroutine timeout.
 */
class OfflineJapaneseIndonesianTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build(),
    )

    private val downloadConditions = DownloadConditions.Builder().build()

    suspend fun ensureModel(onStatus: (String) -> Unit = {}): String {
        onStatus("Menguji akses internet langsung dari Wuvatel…")
        val probe = probeInternet()

        onStatus("Internet Wuvatel OK ($probe). ML Kit dijalankan di worker terpisah…")
        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-mlkit-model",
                    taskFactory = { translator.downloadModelIfNeeded(downloadConditions) },
                )
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Internet Wuvatel berhasil ($probe), tetapi permintaan model ML Kit di worker " +
                    "masih tidak selesai setelah 120 detik. UI tidak lagi diblokir. " +
                    "Jika ini terjadi lagi, engine ML Kit akan kita tinggalkan untuk perangkat ini.",
                t,
            )
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Internet Wuvatel berhasil ($probe), lalu ${describeFailure("download model", t)}",
                t,
            )
        }

        onStatus("Model ML Kit siap. Memulai terjemahan…")
        return "siap oleh downloadModelIfNeeded()"
    }

    private suspend fun probeInternet(): String = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (code !in 200..399) {
                    throw IllegalStateException("HTTP $code")
                }
                "HTTP $code"
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            val type = t::class.java.simpleName.ifBlank { t::class.java.name }
            throw IllegalStateException(
                "Probe internet Wuvatel gagal [$type]: ${t.message ?: "tanpa pesan"}. " +
                    "Periksa Wi-Fi/data/VPN/DNS lalu coba lagi.",
                t,
            )
        }
    }

    private suspend fun <T> awaitMlKitTaskOffMain(
        threadName: String,
        taskFactory: () -> Task<T>,
    ): T {
        val result = CompletableDeferred<T>()

        Thread(
            {
                try {
                    // Both creating the Task and waiting for it happen away from
                    // the UI thread. If the SDK blocks synchronously, the outer
                    // coroutine timeout can still update the UI.
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

    suspend fun translate(text: String): String {
        try {
            return withTimeout(TRANSLATION_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = "wuvatel-mlkit-translate",
                    taskFactory = { translator.translate(text) },
                ).trim()
            }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException(
                "Model sudah siap, tetapi translate() di worker tidak selesai selama 30 detik.",
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
        const val PROBE_URL = "https://www.google.com/generate_204"
        const val PROBE_TIMEOUT_MS = 8_000
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 120_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
