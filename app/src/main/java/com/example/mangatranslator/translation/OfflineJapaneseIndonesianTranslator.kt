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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * M3.1.7 Japanese -> Indonesian translation diagnostics.
 *
 * Translation models are checked/downloaded explicitly through RemoteModelManager
 * so each stage can be shown in the in-app diagnostic log. ML Kit Tasks are still
 * created and awaited on daemon worker threads to protect the Compose main thread
 * from OEM/SDK synchronous stalls.
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
            "Wuvatel M3.1.7 · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )

        onStatus("Menguji akses internet langsung dari Wuvatel…")
        log("NET", "Memulai probe $PROBE_URL")
        val probe = try {
            probeInternet()
        } catch (t: Throwable) {
            log("NET-ERROR", diagnosticMessage(t))
            throw t
        }
        log("NET", "Probe berhasil: $probe")

        onStatus("Memeriksa model ML Kit yang sudah tersimpan…")
        val japaneseReadyBefore = isModelDownloaded(
            model = japaneseModel,
            label = "JA",
            threadName = "wuvatel-check-ja",
            log = ::log,
        )
        val indonesianReadyBefore = isModelDownloaded(
            model = indonesianModel,
            label = "ID",
            threadName = "wuvatel-check-id",
            log = ::log,
        )

        log(
            "MODEL",
            "Status awal: JA=${if (japaneseReadyBefore) "READY" else "MISSING"}, " +
                "ID=${if (indonesianReadyBefore) "READY" else "MISSING"}",
        )

        if (!japaneseReadyBefore) {
            downloadLanguageModel(
                model = japaneseModel,
                label = "JA",
                displayName = "Jepang",
                threadName = "wuvatel-download-ja",
                onStatus = onStatus,
                log = ::log,
            )
        }

        if (!indonesianReadyBefore) {
            downloadLanguageModel(
                model = indonesianModel,
                label = "ID",
                displayName = "Indonesia",
                threadName = "wuvatel-download-id",
                onStatus = onStatus,
                log = ::log,
            )
        }

        onStatus("Memverifikasi model setelah download…")
        val japaneseReadyAfter = isModelDownloaded(
            model = japaneseModel,
            label = "JA-final",
            threadName = "wuvatel-final-ja",
            log = ::log,
        )
        val indonesianReadyAfter = isModelDownloaded(
            model = indonesianModel,
            label = "ID-final",
            threadName = "wuvatel-final-id",
            log = ::log,
        )

        if (!japaneseReadyAfter || !indonesianReadyAfter) {
            val message =
                "Verifikasi model gagal: JA=$japaneseReadyAfter, ID=$indonesianReadyAfter"
            log("VERIFY-ERROR", message)
            throw IllegalStateException(message)
        }

        log("READY", "Model Jepang dan Indonesia terverifikasi tersedia.")
        onStatus("Model ML Kit siap. Memulai terjemahan…")
        return "JA + ID siap via RemoteModelManager"
    }

    private suspend fun isModelDownloaded(
        model: TranslateRemoteModel,
        label: String,
        threadName: String,
        log: (String, String) -> Unit,
    ): Boolean {
        log("CHECK-$label", "Memanggil RemoteModelManager.isModelDownloaded()")
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
            val message = "isModelDownloaded($label) tidak selesai dalam 20 detik"
            log("CHECK-$label-TIMEOUT", message)
            throw IllegalStateException(message, t)
        } catch (t: Throwable) {
            val message = describeFailure("cek model $label", t)
            log("CHECK-$label-ERROR", message)
            throw IllegalStateException(message, t)
        }
    }

    private suspend fun downloadLanguageModel(
        model: TranslateRemoteModel,
        label: String,
        displayName: String,
        threadName: String,
        onStatus: (String) -> Unit,
        log: (String, String) -> Unit,
    ) {
        onStatus("Mengunduh model ML Kit $displayName ($label)…")
        log("DOWNLOAD-$label", "Memanggil RemoteModelManager.download(); jaringan apa pun diizinkan")

        try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                awaitMlKitTaskOffMain(
                    threadName = threadName,
                    taskFactory = { modelManager.download(model, downloadConditions) },
                )
            }
            log("DOWNLOAD-$label", "Task download selesai")
        } catch (t: TimeoutCancellationException) {
            val message = "download model $label tidak selesai dalam 180 detik"
            log("DOWNLOAD-$label-TIMEOUT", message)
            throw IllegalStateException(message, t)
        } catch (t: Throwable) {
            val message = describeFailure("download model $label", t)
            log("DOWNLOAD-$label-ERROR", message)
            throw IllegalStateException(message, t)
        }

        val verified = isModelDownloaded(
            model = model,
            label = "$label-post",
            threadName = "$threadName-verify",
            log = log,
        )
        if (!verified) {
            val message = "Task download $label selesai tetapi model masih dilaporkan belum tersedia"
            log("DOWNLOAD-$label-VERIFY-ERROR", message)
            throw IllegalStateException(message)
        }
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
        const val PROBE_URL = "https://www.google.com/generate_204"
        const val PROBE_TIMEOUT_MS = 8_000
        const val MODEL_QUERY_TIMEOUT_MS = 20_000L
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 180_000L
        const val TRANSLATION_TIMEOUT_MS = 30_000L
    }
}
