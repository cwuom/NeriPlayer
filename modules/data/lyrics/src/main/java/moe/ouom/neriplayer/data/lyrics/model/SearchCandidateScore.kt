package moe.ouom.neriplayer.data.lyrics.model

import moe.ouom.neriplayer.core.model.music.SongSearchInfo

internal data class SearchCandidateScore(
    val candidate: SongSearchInfo,
    val score: Int,
    val durationDeltaMs: Long
)
