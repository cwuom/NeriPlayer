package moe.ouom.neriplayer.data.model.lyrics.amll

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

data class AmllResolvedLyrics(
    val rawLyrics: String,
    val entries: List<LyricEntry>
)
