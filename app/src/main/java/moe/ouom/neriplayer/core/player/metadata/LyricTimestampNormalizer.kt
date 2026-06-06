package moe.ouom.neriplayer.core.player.metadata

private val LegacyLrcTimestampRegex = Regex("""\[(\d{1,2}):(\d{2}):(\d{2,3})]""")

internal fun normalizeLegacyLrcTimestamps(content: String): String {
    if (content.isEmpty()) {
        return content
    }
    return LegacyLrcTimestampRegex.replace(content) { match ->
        val minutes = match.groupValues[1].padStart(2, '0')
        val seconds = match.groupValues[2]
        val fraction = match.groupValues[3]
        "[$minutes:$seconds.$fraction]"
    }
}
