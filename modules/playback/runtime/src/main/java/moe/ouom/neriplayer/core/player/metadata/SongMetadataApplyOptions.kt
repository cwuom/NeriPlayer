package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.lyrics.matching.DEFAULT_EDITABLE_LYRIC_MATCH_SOURCES
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource

data class SongMetadataApplyOptions(
    val lyricSources: Set<EditableLyricMatchSource> = DEFAULT_EDITABLE_LYRIC_MATCH_SOURCES.toSet(),
    val originalLyrics: Boolean = true,
    val translatedLyrics: Boolean = true,
    val romanizedLyrics: Boolean = true,
    val preferWordTimed: Boolean = true,
    val writeLocalMetadata: Boolean = false
) {
    internal val fetchesLyrics: Boolean
        get() = lyricSources.isNotEmpty() && (originalLyrics || translatedLyrics || romanizedLyrics)
}

enum class SongMetadataApplyStage {
    FETCHING_DETAILS,
    FETCHING_LYRICS,
    PREPARING_COVER,
    PREPARING_METADATA,
    APPLYING
}
