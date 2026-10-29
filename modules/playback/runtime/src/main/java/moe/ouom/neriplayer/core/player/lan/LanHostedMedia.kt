package moe.ouom.neriplayer.core.player.lan

import android.net.Uri
import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

/** Borrows the server-owned immutable files; no HTTP transfer or second cached copy. */
internal data class LanHostedMedia(
    val descriptor: LanLocalMedia,
    val audio: LanAudioFile,
    val presentation: LanSharedPresentation,
    val cover: File?
) {
    fun read(requested: LanLocalMedia, track: ListenTogetherTrack): Pair<LanAudioFile, SongItem> {
        check(requested == descriptor && track.stableKey == descriptor.stableKey) { "AUDIO_CONTENT" }
        check(audio.file.isFile && audio.file.length() == descriptor.byteLength) { "LOCAL_READ" }
        return audio to lanLocalSessionSong(track, descriptor, presentation, audio.file, cover)
    }
}

internal fun lanLocalSessionSong(track: ListenTogetherTrack, media: LanLocalMedia,
    metadata: LanSharedPresentation, file: File, cover: File?): SongItem =
    SongItem(id = track.audioId.take(15).toLong(16), name = metadata.name, artist = metadata.artist,
        album = metadata.album, albumId = 0, durationMs = media.durationMs, coverUrl = cover?.absolutePath,
        mediaUri = Uri.fromFile(file).toString(), localFilePath = file.absolutePath, channelId = "local",
        audioId = track.audioId, sourceStableKey = "lan-session:${track.stableKey}", userLyricOffsetMs = metadata.lyricOffsetMs)
