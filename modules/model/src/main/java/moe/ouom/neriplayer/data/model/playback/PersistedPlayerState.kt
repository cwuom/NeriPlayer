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
    val lyricSyncEdited: Boolean? = null
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
    val shuffleRestoreIndex: Int? = null
)

data class PersistedPlaybackState(
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null
)
