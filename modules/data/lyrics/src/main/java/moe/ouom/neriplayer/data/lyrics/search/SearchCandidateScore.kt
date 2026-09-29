package moe.ouom.neriplayer.data.lyrics.search

import moe.ouom.neriplayer.data.model.music.SongSearchInfo

internal data class SearchCandidateScore(
    val candidate: SongSearchInfo,
    val score: Int,
    val durationDeltaMs: Long
)
