package moe.ouom.neriplayer.core.player.dlna.audio

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

/**
 * Normalizes NeriPlayer SongItem title, artist, and album fields for DLNA DIDL-Lite
 * and container-embedded tags, stripping internal source prefixes like "Netease"
 * and "Bilibili|<cid>|<bvid>" so hardware renderers (e.g. Shanling EM7) display
 * clean, human-readable artist and album metadata.
 */
object DlnaTrackMetadataNormalizer {

    data class NormalizedMetadata(
        val title: String,
        val artist: String,
        val album: String
    )

    fun normalize(
        song: SongItem,
        source: PlaybackAudioSource,
        biliFallbackAlbum: String = "Bilibili",
        neteaseFallbackAlbum: String = "NetEase Cloud Music",
        youtubeFallbackAlbum: String = "YouTube Music",
        localFallbackAlbum: String = "Local Music"
    ): NormalizedMetadata {
        val title = (song.customName?.takeIf { it.isNotBlank() } ?: song.name).trim()
            .ifBlank { "Unknown Title" }
        val artist = (song.customArtist?.takeIf { it.isNotBlank() } ?: song.artist).trim()
            .ifBlank {
                when {
                    source == PlaybackAudioSource.BILIBILI ||
                        song.channelId.equals("bilibili", ignoreCase = true) ||
                        song.album.startsWith(SongSourceTags.BILIBILI, ignoreCase = true) -> biliFallbackAlbum
                    else -> "Unknown Artist"
                }
            }
        val album = normalizeAlbum(
            rawAlbum = song.album,
            channelId = song.channelId,
            source = source,
            biliFallbackAlbum = biliFallbackAlbum,
            neteaseFallbackAlbum = neteaseFallbackAlbum,
            youtubeFallbackAlbum = youtubeFallbackAlbum,
            localFallbackAlbum = localFallbackAlbum
        )
        return NormalizedMetadata(title = title, artist = artist, album = album)
    }

    private fun normalizeAlbum(
        rawAlbum: String,
        channelId: String?,
        source: PlaybackAudioSource,
        biliFallbackAlbum: String,
        neteaseFallbackAlbum: String,
        youtubeFallbackAlbum: String,
        localFallbackAlbum: String
    ): String {
        val trimmed = rawAlbum.trim()
        if (trimmed.equals(SongSourceTags.BILIBILI, ignoreCase = true) ||
            trimmed.startsWith("${SongSourceTags.BILIBILI}|", ignoreCase = true) ||
            (trimmed.isBlank() && (source == PlaybackAudioSource.BILIBILI || channelId.equals("bilibili", ignoreCase = true)))
        ) {
            return biliFallbackAlbum
        }
        if (trimmed.startsWith(SongSourceTags.NETEASE, ignoreCase = true) &&
            source == PlaybackAudioSource.NETEASE
        ) {
            val stripped = trimmed.substring(SongSourceTags.NETEASE.length).trim()
            return stripped.ifBlank { neteaseFallbackAlbum }
        }
        if (trimmed.isBlank()) {
            return when (source) {
                PlaybackAudioSource.NETEASE -> neteaseFallbackAlbum
                PlaybackAudioSource.BILIBILI -> biliFallbackAlbum
                PlaybackAudioSource.YOUTUBE_MUSIC -> youtubeFallbackAlbum
                PlaybackAudioSource.LOCAL, PlaybackAudioSource.SUBSONIC -> localFallbackAlbum
            }
        }
        if (LocalSongSupport.isPlaceholderAlbum(trimmed) || trimmed.equals("local", ignoreCase = true)) {
            return localFallbackAlbum
        }
        return trimmed
    }
}
