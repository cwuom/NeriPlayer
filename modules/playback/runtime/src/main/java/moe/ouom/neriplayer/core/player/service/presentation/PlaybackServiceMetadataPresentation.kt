package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationTitle
import android.media.MediaMetadata
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothMetadataText
import moe.ouom.neriplayer.core.player.metadata.resolveExternalBluetoothMetadataText
import moe.ouom.neriplayer.core.player.metadata.shouldUseExternalBluetoothLyrics
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds

internal fun serviceMetadataText(
    song: SongItem?,
    payload: ExternalBluetoothLyricPayload,
    audioDeviceType: Int?,
    forceSendLyrics: Boolean,
    mode: BluetoothMetadataMode = BluetoothMetadataMode.SongAndLyrics,
    normalAlbum: String? = null,
): ExternalBluetoothMetadataText = resolveExternalBluetoothMetadataText(
    normalTitle = serviceNotificationTitle(song),
    normalArtist = serviceMetadataArtist(song),
    payload = payload,
    useBluetoothLyrics = shouldUseExternalBluetoothLyrics(audioDeviceType, payload, forceSendLyrics),
    normalAlbum = normalAlbum ?: serviceMetadataAlbum(song),
    mode = if (audioDeviceType?.let(::isBluetoothOutputType) == true) mode else BluetoothMetadataMode.Lyrics,
)

private fun serviceMetadataArtist(song: SongItem?): String = song?.displayArtist().orEmpty()

private fun serviceMetadataAlbum(song: SongItem?): String? = song?.album?.trim()?.takeIf(String::isNotBlank)

internal fun serviceMetadataMediaId(song: SongItem?, queue: List<SongItem>, index: Int): String? {
    val queuedSong = queue.getOrNull(index) ?: return null
    val songKey = song?.stableKey() ?: return null
    if (queuedSong.stableKey() != songKey) return null
    return CarMediaIds.song(CarMediaIds.QUEUE, index, songKey)
}

internal fun serviceMetadataSnapshot(
    song: SongItem?,
    text: ExternalBluetoothMetadataText,
    artwork: PlaybackArtworkSnapshot,
    queueSize: Int = 0,
    queueIndex: Int = -1,
    artworkUri: String? = null,
    mediaId: String? = null,
): PlaybackMetadataSnapshot = PlaybackMetadataSnapshot(
    songKey = song?.playbackVisualKey(),
    title = text.title,
    artist = text.artist,
    album = text.album,
    displayTitle = text.displayTitle,
    displaySubtitle = text.displaySubtitle,
    displayDescription = text.displayDescription,
    durationMs = (song?.durationMs ?: 0L).coerceAtLeast(0L),
    coverSource = artwork.coverSource,
    largeIconReady = artwork.mediaReady,
    mediaId = mediaId,
    trackNumber = if (queueIndex in 0 until queueSize) queueIndex.toLong() + 1L else 0L,
    numTracks = queueSize.toLong(),
    artworkUri = artworkUri,
)

internal fun serviceMediaMetadata(
    snapshot: PlaybackMetadataSnapshot,
    artwork: PlaybackArtworkSnapshot,
): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, snapshot.displayTitle)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.displaySubtitle)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, snapshot.mediaId)
        .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, snapshot.trackNumber)
        .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, snapshot.numTracks)
        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork.mediaBitmap.takeIf { artwork.mediaReady })
        .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork.mediaBitmap.takeIf { artwork.mediaReady })
        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork.notificationBitmap.takeIf { artwork.notificationReady })
    addOptionalAlbum(builder, snapshot.album)
    addOptionalDescription(builder, snapshot.displayDescription)
    addArtworkUri(builder, snapshot.artworkUri)
    return builder.build()
}

private fun addOptionalAlbum(builder: MediaMetadata.Builder, album: String?) {
    if (album != null) builder.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
}

private fun addOptionalDescription(builder: MediaMetadata.Builder, description: String?) {
    if (description != null) builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, description)
}

private fun addArtworkUri(builder: MediaMetadata.Builder, artworkUri: String?) {
    if (artworkUri == null) return
    builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUri)
    builder.putString(MediaMetadata.METADATA_KEY_ART_URI, artworkUri)
    builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artworkUri)
}
