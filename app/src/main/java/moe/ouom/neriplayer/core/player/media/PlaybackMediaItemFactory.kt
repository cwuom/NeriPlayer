@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.url.stripListenTogetherStreamQualityMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels

internal object PlaybackMediaItemFactory {
    const val BILI_SOURCE_TAG = "Bilibili"

    fun cacheKey(
        song: SongItem,
        context: Context,
        youtubeQualityOverride: String?,
        youtubePreferM4a: Boolean,
        neteaseFallbackEnabled: () -> Boolean,
        youtubeQuality: () -> String,
        biliQuality: () -> String,
        neteaseQuality: () -> String
    ): String {
        if (LocalSongSupport.isLocalSong(song, context)) return "local-${song.stableKey().hashCode()}"
        if (isYouTubeSource(song)) {
            return youtubeSongCacheKey(song, youtubeQualityOverride, youtubePreferM4a, youtubeQuality)
        }
        if (isBiliSource(song)) return biliSongCacheKey(song, biliQuality)
        return neteaseCacheKey(song.id, neteaseQuality(), neteaseFallbackEnabled())
    }

    fun isYouTubeSource(song: SongItem): Boolean =
        song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicSong(song)

    private fun youtubeSongCacheKey(
        song: SongItem,
        qualityOverride: String?,
        preferM4a: Boolean,
        quality: () -> String
    ): String {
        val videoId = song.audioId ?: extractYouTubeMusicVideoId(song.mediaUri).orEmpty()
        return youtubeCacheKey(videoId, qualityOverride ?: quality(), preferM4a)
    }

    fun isBiliSource(song: SongItem): Boolean =
        song.channelId == ListenTogetherChannels.BILIBILI || song.album.startsWith(BILI_SOURCE_TAG)

    private fun biliSongCacheKey(song: SongItem, quality: () -> String): String {
        val songId = song.audioId ?: song.id.toString()
        val cid = song.subAudioId ?: biliCidFromAlbum(song.album)
        val qualityKey = quality()
        return if (cid == null) "bili-$songId-$qualityKey" else "bili-$songId-$cid-$qualityKey"
    }

    private fun biliCidFromAlbum(album: String): String? = album
        .substringAfter('|', "")
        .substringBefore('|')
        .takeIf { it.isNotBlank() }

    fun neteaseCacheKey(songId: Long, preferredQuality: String, useFallbackNamespace: Boolean): String {
        val quality = preferredQuality.trim().lowercase().ifBlank { "exhigh" }
        return if (useFallbackNamespace) {
            "netease-$songId-$quality-fallback-v1"
        } else {
            "netease-$songId-$quality"
        }
    }

    fun neteasePreviewCacheKey(songId: Long, preferredQuality: String): String {
        val quality = preferredQuality.trim().lowercase().ifBlank { "exhigh" }
        return "netease-preview-v1-$songId-$quality"
    }

    fun youtubeCacheKey(videoId: String, preferredQuality: String, preferM4a: Boolean): String =
        if (preferM4a) {
            "ytmusic-$videoId-$preferredQuality-stable-m4a"
        } else {
            "ytmusic-$videoId-$preferredQuality"
        }

    fun mediaItem(
        song: SongItem,
        url: String,
        cacheKey: String,
        mimeType: String?,
        safeCustomCacheKey: () -> String?
    ): MediaItem {
        val mediaUrl = stripListenTogetherStreamQualityMetadata(url)
        val mediaUri = mediaUrl.toUri()
        logFlacMediaItem(song, mediaUri, mimeType, cacheKey)
        return MediaItem.Builder()
            .setMediaId("${song.id}|${song.album}|${song.mediaUri.orEmpty()}")
            .setUri(mediaUri)
            .apply {
                applyMimeType(mimeType)
                applyCacheKey(mediaUrl, safeCustomCacheKey)
            }
            .build()
    }

    private fun MediaItem.Builder.applyMimeType(mimeType: String?) {
        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
    }

    private fun MediaItem.Builder.applyCacheKey(url: String, safeCustomCacheKey: () -> String?) {
        if (!isLocalMediaUrl(url)) safeCustomCacheKey()?.let(::setCustomCacheKey)
    }

    private fun isLocalMediaUrl(url: String): Boolean = url.startsWith("file://") ||
        url.startsWith("content://") ||
        url.startsWith("android.resource://") ||
        url.startsWith("/")

    private fun logFlacMediaItem(
        song: SongItem,
        uri: Uri,
        mimeType: String?,
        cacheKey: String
    ) {
        if (uri.path?.endsWith(".flac", ignoreCase = true) != true) return
        NPLogger.d(
            "NERI-PlayerManager",
            "build FLAC media item: songId=${song.id}, host=${uri.host ?: "local"}, " +
                "declaredMimeType=${mimeType ?: "missing"}, cacheKey=$cacheKey"
        )
    }
}
