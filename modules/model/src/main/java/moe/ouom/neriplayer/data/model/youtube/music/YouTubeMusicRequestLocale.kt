package moe.ouom.neriplayer.data.model.youtube.music

import java.util.Locale

data class YouTubeMusicRequestLocale(
    val hl: String,
    val gl: String
) {
    val acceptLanguage: String
        get() {
            val base = Locale.forLanguageTag(hl).language
            return listOfNotNull(hl, base.takeUnless { it.isBlank() || it == hl }?.let { "$it;q=0.9" })
                .joinToString(",")
        }
}
