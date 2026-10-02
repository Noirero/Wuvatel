package com.example.mangatranslator.translation

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray

/**
 * Online JP -> ID translator matching the simple Online engine used by Miyorare.
 *
 * This provider is intentionally optional: it uses Google's public web translation endpoint,
 * not the contracted Google Cloud Translation API. Callers must keep ML Kit as an independent
 * engine and surface network/provider failures instead of treating this endpoint as guaranteed.
 */
class MiyorareOnlineJapaneseIndonesianTranslator {

    suspend fun translate(
        text: String,
        onLog: (String) -> Unit = {},
    ): String {
        val source = text.trim()
        if (source.isBlank()) return source

        onLog("[ONLINE] Mengirim JP → ID ke provider online")
        val encoded = URLEncoder.encode(source, Charsets.UTF_8.name())
        val url = URL(
            "$ENDPOINT?client=gtx&sl=ja&tl=id&dt=t&q=$encoded",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Provider online gagal (HTTP $status)")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val segments = root.optJSONArray(0)
                ?: throw IOException("Respons provider online tidak valid")

            val translated = buildString {
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONArray(index) ?: continue
                    append(segment.optString(0))
                }
            }.trim()

            if (translated.isBlank()) {
                throw IOException("Provider online mengembalikan hasil kosong")
            }
            onLog("[ONLINE-DONE] Terjemahan online selesai")
            return translated
        } finally {
            connection.disconnect()
        }
    }

    fun diagnosticMessage(error: Throwable): String =
        error.message ?: error::class.java.simpleName

    private companion object {
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        const val USER_AGENT = "Mozilla/5.0 (Android) Wuvatel"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
