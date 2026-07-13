package moe.ouom.neriplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

@Parcelize
data class SongItem(
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
    val sourceStableKey: String? = null,
    val streamUrl: String? = null,
    val neteaseArtists: List<NeteaseArtistSummary>? = emptyList(),
    val addedAt: Long = 0L,
    val syncMembershipTokens: List<SyncCausalToken>? = emptyList()
) : Parcelable
