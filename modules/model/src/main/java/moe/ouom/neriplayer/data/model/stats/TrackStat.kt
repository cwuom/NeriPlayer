package moe.ouom.neriplayer.data.model.stats

data class TrackStat(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val coverUrl: String?,
    val durationMs: Long,
    val totalListenMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long,
    val mediaUri: String?,
    val localFilePath: String?,
    val localFileName: String?,
    val customName: String?,
    val customArtist: String?,
    val customCoverUrl: String?,
    val identityKey: String
)
