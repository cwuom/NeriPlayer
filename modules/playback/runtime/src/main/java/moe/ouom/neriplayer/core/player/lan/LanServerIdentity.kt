package moe.ouom.neriplayer.core.player.lan

import java.security.MessageDigest
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.server.isServerSong

internal fun isLanTemporaryServerSong(song: SongItem): Boolean =
    song.channelId == ServerSongRef.CHANNEL && song.sourceStableKey?.startsWith("lan-session:subsonic:") == true

internal fun lanServerTrack(song: SongItem): ListenTogetherTrack? {
    val id = if (isLanTemporaryServerSong(song)) {
        song.audioId ?: return null
    } else {
        if (!song.isServerSong()) return null
        val ref = ServerSongRef.from(song) ?: return null
        MessageDigest.getInstance("SHA-256").digest(ref.audioId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    return ListenTogetherTrack("subsonic:$id", ServerSongRef.CHANNEL, id, name = song.displayName(),
        artist = song.displayArtist(), album = song.album, durationMs = song.durationMs)
}
