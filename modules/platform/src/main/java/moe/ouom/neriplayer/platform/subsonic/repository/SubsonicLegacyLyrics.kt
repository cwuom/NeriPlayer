package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import org.json.JSONObject

internal fun parseLegacyServerLyrics(json: JSONObject, durationMs: Long): List<LyricEntry> {
    val text = (json.optJSONObject("lyrics")?.opt("value") as? String)?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val timed = parseNeteaseLyricsAuto(text)
    return timed.takeIf { entries -> entries.any { it.text.isNotBlank() } }
        ?: convertPlainLyricsToEntries(text, durationMs)
}
