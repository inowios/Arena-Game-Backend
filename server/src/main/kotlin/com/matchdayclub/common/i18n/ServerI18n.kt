package com.matchdayclub.common.i18n

import java.time.Duration

/** A catalog entry used as a message argument (sport, division, verdict…), translated with the message. */
data class Named(val key: String)

/**
 * Server texts in the five client languages (en, es, pt, ru, uk): error messages, pushes. The catalog lives in
 * `resources/i18n/messages.tsv` (key, en, es, pt, ru, uk); error messages use their English template as the key
 * (gettext style), so throw sites stay readable: `ConflictException("Requires club level {0}", level)`.
 * Arguments: numbers and strings as is, [Duration] as "2h 13m" in the language, [Named] looked up in the catalog.
 */
object ServerI18n {
    val LANGS = listOf("en", "es", "pt", "ru", "uk")

    private val catalog: Map<String, List<String>> = run {
        val stream = ServerI18n::class.java.getResourceAsStream("/i18n/messages.tsv") ?: return@run emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size != 1 + LANGS.size) null else cols[0] to cols.drop(1).map { it.replace("\\n", "\n") }
            }.toMap()
        }
    }

    fun has(key: String) = key in catalog
    val size get() = catalog.size

    /** First supported language of an Accept-Language header ("ru-RU,ru;q=0.9,en;q=0.8" → "ru"), or null. */
    fun fromHeader(acceptLanguage: String?): String? {
        if (acceptLanguage.isNullOrBlank()) return null
        return acceptLanguage.split(',')
            .map { part -> val p = part.trim().split(';'); p[0].trim().lowercase() to (p.getOrNull(1)?.trim()?.removePrefix("q=")?.toDoubleOrNull() ?: 1.0) }
            .sortedByDescending { it.second }
            .map { it.first.substringBefore('-').substringBefore('_') }
            .firstOrNull { it in LANGS }
    }

    fun normalize(locale: String?): String = locale?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it in LANGS } ?: "en"

    /** Translated text for [key] (falls back to English, then to the key itself) with {0}, {1}… filled in. */
    fun t(key: String, lang: String, vararg args: Any?): String {
        val idx = LANGS.indexOf(normalize(lang))
        val row = catalog[key]
        val template = row?.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: row?.getOrNull(0) ?: key
        if (args.isEmpty()) return template
        var out = template
        args.forEachIndexed { i, a -> out = out.replace("{$i}", format(a, lang)) }
        return out
    }

    fun format(arg: Any?, lang: String): String = when (arg) {
        null -> ""
        is Named -> t(arg.key, lang)
        is Duration -> duration(arg.seconds, lang)
        is Long -> if (arg >= 10_000) String.format(java.util.Locale.ROOT, "%,d", arg).replace(',', if (normalize(lang) == "en") ',' else ' ') else arg.toString()
        else -> arg.toString()
    }

    /** "2h 13m" / "2 ч 13 мин"… (seconds only under a minute). */
    fun duration(seconds: Long, lang: String): String {
        val s = maxOf(0, seconds)
        val (h, m, sec) = when (normalize(lang)) { "ru" -> Triple("ч", "мин", "с"); "uk" -> Triple("год", "хв", "с"); "es", "pt" -> Triple("h", "min", "s"); else -> Triple("h", "m", "s") }
        val sep = if (normalize(lang) == "en") "" else " "
        val hours = s / 3600; val minutes = (s % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours$sep$h $minutes$sep$m"
            hours > 0 -> "$hours$sep$h"
            minutes > 0 -> "$minutes$sep$m"
            else -> "$s$sep$sec"
        }
    }
}
