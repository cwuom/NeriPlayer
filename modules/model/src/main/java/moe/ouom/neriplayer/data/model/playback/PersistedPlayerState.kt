package moe.ouom.neriplayer.data.model.playback

import moe.ouom.neriplayer.data.model.music.MusicPlatform

data class PersistedSongItem(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val mediaUri: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedLyricSource: MusicPlatform? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val localFileName: String? = null,
    val localFilePath: String? = null,
    val channelId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val streamUrl: String? = null,
    val matchedRomanizedLyric: String? = null,
    val originalRomanizedLyric: String? = null,
    val lyricSyncRevision: Long = 0L,
    val lyricSyncEdited: Boolean? = null,
    val explicitLocalSourceReference: String? = null
)

data class PersistedState(
    val playlist: List<PersistedSongItem>,
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val shuffleRestorePlaylist: List<PersistedSongItem>? = null,
    val shuffleRestoreIndex: Int? = null,
    // 心动模式开关（moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode）。
    // 可空 + 默认 null：旧存档 JSON 里没有这个字段，Gson 反序列化时缺字段即为 null，恢复侧统一按 OFF 处理。
    val heartbeatMode: Int? = null
)

data class PersistedPlaybackState(
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    // 心动模式开关；可空 + 默认 null 保证旧存档 Gson 反序列化兼容，恢复侧按 OFF 归一。
    val heartbeatMode: Int? = null
)
