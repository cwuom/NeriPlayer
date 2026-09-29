package moe.ouom.neriplayer.data.model.youtube.music

import java.util.Locale

data class YouTubeMusicRequestLocale(
    val hl: String,
    val gl: String
) {
    val acceptLanguage: String
        get() = buildString {
            append(hl)
            append(",")
            append(gl.lowercase(Locale.US))
            append(";q=0.9,en;q=0.8")
        }
}
