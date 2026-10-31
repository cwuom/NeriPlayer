package moe.ouom.neriplayer.data.model.lan

import kotlinx.serialization.Serializable

/** Session presentation resolved by the existing host metadata/lyrics pipeline. */
@Serializable
data class LanSharedPresentation(
    val name: String,
    val artist: String,
    val album: String,
    val lyricOffsetMs: Long = 0,
    val lyrics: List<LanSharedLyricLine> = emptyList(),
    val translations: List<LanSharedLyricLine> = emptyList(),
    val romanized: List<LanSharedLyricLine> = emptyList(),
    val cover: LanFileResource? = null
)

@Serializable
data class LanSharedLyricLine(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val words: List<LanSharedWordTiming>? = null,
    val translation: String? = null
)

@Serializable
data class LanSharedWordTiming(val startTimeMs: Long, val endTimeMs: Long, val charCount: Int)
