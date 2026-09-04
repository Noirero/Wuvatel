package com.example.mangatranslator.translation

/**
 * Conservative M3.2 naturalization layer.
 *
 * This intentionally does not try to rewrite full sentences. ML Kit remains the
 * translation engine; this layer only cleans formatting/honorifics and fixes a
 * small set of standalone Japanese expressions whose meaning is unambiguous.
 */
object NaturalIndonesianPostProcessor {
    fun process(
        japanese: String,
        machineTranslation: String,
    ): String {
        standaloneExpression(japanese)?.let { return it }

        return machineTranslation
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("(?i)-\\s*(chan|san|kun|sama|senpai)\\b")) { match ->
                "-${match.groupValues[1].lowercase()}"
            }
    }

    private fun standaloneExpression(japanese: String): String? {
        val source = japanese.trim().replace(" ", "")
        return when {
            Regex("^でも(?:[…。!！?？]*)$").matches(source) -> "Tapi…"
            Regex("^おはよ(?:う|ー+)?(?:[!！。…]*)$").matches(source) -> "Selamat pagi!"
            Regex("^ありがとう(?:ございます)?(?:[!！。…]*)$").matches(source) -> "Terima kasih!"
            Regex("^こんにちは(?:[!！。…]*)$").matches(source) -> "Halo!"
            Regex("^こんばんは(?:[!！。…]*)$").matches(source) -> "Selamat malam!"
            else -> null
        }
    }
}
