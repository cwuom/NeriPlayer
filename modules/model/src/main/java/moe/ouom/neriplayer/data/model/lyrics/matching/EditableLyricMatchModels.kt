package moe.ouom.neriplayer.data.model.lyrics.matching

enum class EditableLyricMatchSource {
    KUGOU,
    CLOUD_MUSIC,
    QQ_MUSIC,
    AMLL_TTML,
    LRCLIB,
    YOUTUBE_MUSIC
}

val DEFAULT_EDITABLE_LYRIC_MATCH_SOURCES: Set<EditableLyricMatchSource> = setOf(
    EditableLyricMatchSource.AMLL_TTML,
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.KUGOU
)

enum class EditableLyricFormat {
    LRC,
    YRC,
    TTML,
    PLAIN
}

data class EditableLyricMatchRequest(
    val keyword: String,
    val trackName: String,
    val artistName: String,
    val albumName: String? = null,
    val durationMs: Long = 0L,
    val preferWordTimed: Boolean = true,
    val sources: Set<EditableLyricMatchSource> = DEFAULT_EDITABLE_LYRIC_MATCH_SOURCES.toSet()
)

data class EditableLyricMatchCandidate(
    val id: String,
    val source: EditableLyricMatchSource,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val lyrics: String,
    val translatedLyrics: String? = null,
    val format: EditableLyricFormat = EditableLyricFormat.LRC,
    val sourceScore: Int = 0
)

data class RankedEditableLyricMatch(
    val candidate: EditableLyricMatchCandidate,
    val score: Int,
    val durationDeltaMs: Long?,
    val confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.LOW,
    /**
     * 该候选歌词自身是否带逐词时间轴。
     *
     * 独立于 [EditableLyricMatchRequest.preferWordTimed]: 即使调用方不偏好逐词,
     * 这里也必须如实反映歌词内容, 否则 UI 无法稳定地标记"逐词"。
     */
    val hasWordTiming: Boolean = false
)

enum class EditableLyricMatchConfidence(val rank: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2)
}
