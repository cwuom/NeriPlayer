package moe.ouom.neriplayer.data.model.youtube.music

import java.util.Locale

private val isoCountryCodes = Locale.getISOCountries().toSet()

val youtubeContentLanguageTags = listOf(
    "ar", "bg", "cs", "da", "de", "el", "en", "en-GB", "en-NG", "es", "et", "fi",
    "fr", "he", "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "lv", "ms", "nl",
    "no", "pl", "pt", "pt-BR", "ro", "ru", "sk", "sr", "sv", "th", "tr", "uk", "vi",
    "zh", "zh-CN", "zh-TW"
)

interface YouTubeCatalogPolicy {
    val languageTag: String get() = ""
    val countryCode: String get() = ""
    val hideExplicit: Boolean get() = false
    val hideMusicVideos: Boolean get() = false

    fun hides(label: YouTubeContentLabel?): Boolean =
        label != null && ((hideExplicit && label.explicit) || (hideMusicVideos && label.musicVideo))

    fun resolveLocale(system: Locale): YouTubeMusicRequestLocale {
        val country = system.country.uppercase(Locale.ROOT)
        val language = when {
            country == "CN" -> "zh-CN"
            system.language == "zh" -> if (system.script == "Hant" || country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            system.language == "en" && country == "US" -> "en-US"
            system.toLanguageTag() in youtubeContentLanguageTags -> system.toLanguageTag()
            system.language in youtubeContentLanguageTags -> system.language
            else -> "en"
        }
        return YouTubeMusicRequestLocale(
            hl = languageTag.ifBlank { language },
            gl = countryCode.ifBlank {
                when (country) {
                    "CN" -> "JP"
                    in isoCountryCodes -> country
                    else -> "US"
                }
            }
        )
    }
}
