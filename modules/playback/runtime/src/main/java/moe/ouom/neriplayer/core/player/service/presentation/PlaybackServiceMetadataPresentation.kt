package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationTitle
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.os.Parcel
import androidx.core.graphics.scale
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
import java.util.IdentityHashMap

private const val COLOR_OS_METADATA_MAX_BYTES = 512 * 1024
private val COLOR_OS_METADATA_ARTWORK_DIMENSIONS = intArrayOf(256, 128)
private val COLOR_OS_METADATA_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON,
)

internal fun serviceMetadataText(
    song: SongItem?,
    payload: ExternalBluetoothLyricPayload,
    audioDeviceType: Int?,
    forceSendLyrics: Boolean,
    mode: BluetoothMetadataMode = BluetoothMetadataMode.SongAndLyrics,
    normalAlbum: String? = null,
    colorOsLyricsEnabled: Boolean = false,
): ExternalBluetoothMetadataText {
    val text = resolveExternalBluetoothMetadataText(
        normalTitle = serviceNotificationTitle(song),
        normalArtist = serviceMetadataArtist(song),
        payload = payload,
        useBluetoothLyrics = shouldUseExternalBluetoothLyrics(audioDeviceType, payload, forceSendLyrics),
        normalAlbum = normalAlbum ?: serviceMetadataAlbum(song),
        mode = if (audioDeviceType?.let(::isBluetoothOutputType) == true) mode else BluetoothMetadataMode.Lyrics,
    )
    return if (colorOsLyricsEnabled) text.copy(
        title = serviceNotificationTitle(song), artist = serviceMetadataArtist(song),
    ) else text
}

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
    colorOsLyricInfo: String? = null,
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
    colorOsLyricInfo = colorOsLyricInfo,
)

internal fun serviceMediaMetadata(
    snapshot: PlaybackMetadataSnapshot,
    artwork: PlaybackArtworkSnapshot,
    includeBitmaps: Boolean = true,
): MediaMetadata {
    // ALBUM_ART 已被系统 UI、车机和蓝牙作为 ART 的回退, 同一张大图只写一次以控制 Binder 负载
    val mediaBitmap = artwork.mediaBitmap.takeIf { includeBitmaps && artwork.mediaReady }
    val iconBitmap = artwork.notificationBitmap.takeIf { includeBitmaps && artwork.notificationReady }
    val builder = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, snapshot.displayTitle)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.displaySubtitle)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, snapshot.mediaId)
        .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, snapshot.trackNumber)
        .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, snapshot.numTracks)
        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, mediaBitmap)
        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, iconBitmap)
    addOptionalAlbum(builder, snapshot.album)
    addOptionalDescription(builder, snapshot.displayDescription)
    addArtworkUri(builder, snapshot.artworkUri)
    val baseline = builder.build()
    val lyricInfo = snapshot.colorOsLyricInfo ?: return baseline
    val candidate = builder.putString("lyricInfo", lyricInfo)
        .putString("oplus.media.config", "1")
        .build()
    return if (fitsColorOsMetadataBudget(candidate)) candidate else fitColorOsMetadataArtwork(candidate, baseline)
}

private fun fitColorOsMetadataArtwork(candidate: MediaMetadata, baseline: MediaMetadata): MediaMetadata {
    val withoutBitmaps = MediaMetadata.Builder(candidate).apply {
        COLOR_OS_METADATA_BITMAP_KEYS.forEach { putBitmap(it, null) }
    }.build()
    if (!fitsColorOsMetadataBudget(withoutBitmaps)) return baseline
    for (maxDimensionPx in COLOR_OS_METADATA_ARTWORK_DIMENSIONS) {
        scaledColorOsMetadata(candidate, maxDimensionPx)?.let { return it }
    }
    return withoutBitmaps
}

private fun scaledColorOsMetadata(candidate: MediaMetadata, maxDimensionPx: Int): MediaMetadata? {
    val scaledBitmaps = IdentityHashMap<Bitmap, Bitmap>()
    var accepted = false
    return try {
        val builder = MediaMetadata.Builder(candidate)
        for (key in COLOR_OS_METADATA_BITMAP_KEYS) {
            val bitmap = candidate.getBitmap(key) ?: continue
            builder.putBitmap(key, scaledBitmaps.getOrPut(bitmap) { bitmap.scaledForMetadata(maxDimensionPx) })
        }
        builder.build().takeIf(::fitsColorOsMetadataBudget).also { accepted = it != null }
    } catch (_: RuntimeException) {
        null
    } finally {
        // 成功发布的缩略图由元数据持有，失败候选只回收本次创建的副本，保留封面 owner 的原图
        if (!accepted) recycleMetadataBitmapCopies(scaledBitmaps)
    }
}

private fun Bitmap.scaledForMetadata(maxDimensionPx: Int): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxDimensionPx) return this
    val scale = maxDimensionPx.toFloat() / longestSide
    return scale((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

private fun recycleMetadataBitmapCopies(scaledBitmaps: IdentityHashMap<Bitmap, Bitmap>) {
    scaledBitmaps.forEach { original, scaled -> if (scaled !== original) scaled.recycle() }
}

private fun fitsColorOsMetadataBudget(metadata: MediaMetadata): Boolean {
    val parcel = Parcel.obtain()
    return try {
        metadata.writeToParcel(parcel, 0)
        parcel.dataSize() <= COLOR_OS_METADATA_MAX_BYTES
    } catch (_: RuntimeException) {
        false
    } finally {
        parcel.recycle()
    }
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
