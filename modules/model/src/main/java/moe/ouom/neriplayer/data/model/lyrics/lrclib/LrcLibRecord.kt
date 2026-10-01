package moe.ouom.neriplayer.data.model.lyrics.lrclib

/** LRCLIB 原始歌词字段，时间轴可用性由仓库判断 */
data class LrcLibRecord(
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val trackName: String,
    val artistName: String,
    val durationSeconds: Long
)
