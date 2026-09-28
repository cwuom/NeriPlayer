package moe.ouom.neriplayer.core.player.service

import android.media.MediaMetadata
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothMetadataText
import moe.ouom.neriplayer.core.player.metadata.resolveExternalBluetoothMetadataText
import moe.ouom.neriplayer.core.player.metadata.shouldUseExternalBluetoothLyrics
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.playbackVisualKey

internal fun serviceMetadataText(
    song: SongItem?,
    payload: ExternalBluetoothLyricPayload,
    audioDeviceType: Int?,
    forceSendLyrics: Boolean,
): ExternalBluetoothMetadataText = resolveExternalBluetoothMetadataText(
    normalTitle = serviceNotificationTitle(song),
    normalArtist = serviceMetadataArtist(song),
    payload = payload,
    useBluetoothLyrics = shouldUseExternalBluetoothLyrics(audioDeviceType, payload, forceSendLyrics),
)

private fun serviceMetadataArtist(song: SongItem?): String = song?.displayArtist().orEmpty()

internal fun serviceMetadataSnapshot(
    song: SongItem?,
    text: ExternalBluetoothMetadataText,
    artwork: PlaybackArtworkSnapshot,
): PlaybackMetadataSnapshot = PlaybackMetadataSnapshot(
    songKey = song?.playbackVisualKey(),
    title = text.title,
    artist = text.artist,
    album = text.album,
    displayTitle = text.displayTitle,
    displaySubtitle = text.displaySubtitle,
    displayDescription = text.displayDescription,
    durationMs = song?.durationMs ?: 0L,
    coverSource = artwork.coverSource,
    largeIconReady = artwork.mediaReady,
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
        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork.mediaBitmap.takeIf { artwork.mediaReady })
    addOptionalAlbum(builder, snapshot.album)
    addOptionalDescription(builder, snapshot.displayDescription)
    addRemoteArtworkUri(builder, artwork.coverSource)
    return builder.build()
}

private fun addOptionalAlbum(builder: MediaMetadata.Builder, album: String?) {
    if (album != null) builder.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
}

private fun addOptionalDescription(builder: MediaMetadata.Builder, description: String?) {
    if (description != null) builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, description)
}

private fun addRemoteArtworkUri(builder: MediaMetadata.Builder, coverSource: String?) {
    val artworkUri = resolveRemoteMetadataArtworkUri(coverSource) ?: return
    builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUri)
}
