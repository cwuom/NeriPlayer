package moe.ouom.neriplayer.lyrics.parser

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

fun parseEmbeddedPhoneticLyrics(rawLyrics: String): List<LyricEntry> {
    if (rawLyrics.isBlank()) return emptyList()
    return runCatching {
        AutoParser().parse(rawLyrics).lines.mapNotNull { line ->
            val karaoke = line as? KaraokeLine ?: return@mapNotNull null
            val phonetic = karaoke.embeddedPhoneticText() ?: return@mapNotNull null
            val startMs = line.start.toLong()
            LyricEntry(
                text = phonetic,
                startTimeMs = startMs,
                endTimeMs = line.end.toLong().coerceAtLeast(startMs)
            )
        }
    }.getOrDefault(emptyList())
}

private fun KaraokeLine.embeddedPhoneticText(): String? {
    phonetic?.takeIf(String::isNotBlank)?.let { return it }
    return syllables.mapNotNull { it.phonetic?.takeIf(String::isNotBlank) }
        .joinToString(" ")
        .takeIf(String::isNotBlank)
}
