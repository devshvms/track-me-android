package `in`.shvms.trackme.data.local

import java.util.Locale

/** The single source of truth for TASK-306's seven user-selectable app languages. */
object AppLanguageCatalog {
    val supportedCodes: List<String> = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    /** Reduces regional/script tags from Android Settings to TrackMe's translated base language. */
    fun normalize(languageTag: String?): String? {
        val language = languageTag
            ?.takeIf(String::isNotBlank)
            ?.let(Locale::forLanguageTag)
            ?.language
            ?.lowercase(Locale.ROOT)
        return language?.takeIf(supportedCodes::contains)
    }
}
