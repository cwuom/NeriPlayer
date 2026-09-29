package moe.ouom.neriplayer.data.lyrics.model

import moe.ouom.neriplayer.core.lyrics.LyricEntry

data class AmllResolvedLyrics(
    val rawLyrics: String,
    val entries: List<LyricEntry>
)
