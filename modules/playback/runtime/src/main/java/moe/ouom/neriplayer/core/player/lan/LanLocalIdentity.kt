package moe.ouom.neriplayer.core.player.lan

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal object LanLocalIdentity {
    private val resolved = ConcurrentHashMap<String, ListenTogetherTrack>()
    fun reference(song: SongItem): String? = song.localMediaUri()?.toString()
    fun track(song: SongItem): ListenTogetherTrack? {
        if (song.sourceStableKey?.startsWith("lan-session:") == true && song.channelId == "local") {
            val hash = song.audioId ?: return null
            return ListenTogetherTrack("local:$hash", "local", hash, name = song.displayName(), artist = song.displayArtist(),
                album = song.album, durationMs = song.durationMs)
        }
        val reference = reference(song) ?: return null
        resolved[reference]?.let { return it }
        val key = MessageDigest.getInstance("SHA-256").digest(reference.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ListenTogetherTrack("local:$key", "local", key, name = song.displayName(),
            artist = song.displayArtist(), album = song.album, durationMs = song.durationMs)
    }
    fun bind(song: SongItem, track: ListenTogetherTrack) { reference(song)?.let { resolved[it] = track } }
    fun clear() = resolved.clear()
}
