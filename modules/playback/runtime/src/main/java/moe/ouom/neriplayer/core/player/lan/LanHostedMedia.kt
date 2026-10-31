package moe.ouom.neriplayer.core.player.lan

import android.net.Uri
import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

/** Borrows the original read capability; neither playback nor server retirement owns the user file. */
internal data class LanHostedMedia(
    val descriptor: LanLocalMedia,
    val audio: LanAudioFile,
    val presentation: LanSharedPresentation,
    val cover: File?
) {
    fun read(requested: LanLocalMedia, track: ListenTogetherTrack): Pair<LanAudioFile, SongItem> {
        check(requested == descriptor && track.stableKey == descriptor.stableKey) { "AUDIO_CONTENT" }
        check(audio.source.byteLength == descriptor.byteLength) { "AUDIO_CONTENT" }
        audio.source.checkCurrent()
        return audio to lanLocalSessionSong(track, descriptor, presentation, audio.originalUri, cover)
    }
}

internal fun lanLocalSessionSong(track: ListenTogetherTrack, media: LanLocalMedia,
    metadata: LanSharedPresentation, file: File, cover: File?): SongItem =
    lanLocalSessionSong(track, media, metadata, Uri.fromFile(file), cover)

internal fun lanLocalSessionSong(track: ListenTogetherTrack, media: LanLocalMedia,
    metadata: LanSharedPresentation, uri: Uri, cover: File?): SongItem =
    SongItem(id = track.audioId.take(15).toLong(16), name = metadata.name, artist = metadata.artist,
        album = metadata.album, albumId = 0, durationMs = media.durationMs, coverUrl = cover?.absolutePath,
        mediaUri = uri.toString(), localFilePath = uri.path.takeIf { uri.scheme == "file" }, channelId = "local",
        audioId = track.audioId, sourceStableKey = "lan-session:${track.stableKey}", userLyricOffsetMs = metadata.lyricOffsetMs)
