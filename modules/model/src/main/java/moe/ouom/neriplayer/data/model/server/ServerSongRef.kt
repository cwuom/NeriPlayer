package moe.ouom.neriplayer.data.model.server

import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import moe.ouom.neriplayer.data.model.SongItem

/** Reversible, account-scoped identity. No credentials or server addresses are stored here. */
data class ServerSongRef(val profileId: String, val songId: String) {
    val audioId: String get() = "v1:$profileId:${encode(songId)}"
    val mediaUri: String get() = "neri-server://$profileId/song/${encode(songId)}"
    val cacheKey: String get() = "subsonic:$audioId:raw:v1"
    // Compatibility value for SongItem's Long fields; deduplication and API requests use the full ref.
    val numericId: Long get() = surrogateId("song:$audioId")

    fun resourceUrl(kind: String = "stream", id: String = songId): String {
        require(kind == "stream" || kind == "cover")
        return "https://$RESOURCE_HOST/v1/$profileId/$kind/${encode(id)}"
    }

    companion object {
        const val CHANNEL = "subsonic"
        const val RESOURCE_HOST = "neri-server.invalid"

        fun from(song: SongItem): ServerSongRef? = fromAudioId(song.audioId)
            ?.takeIf { song.channelId == CHANNEL }
            ?: fromMediaUri(song.mediaUri)

        fun fromAudioId(raw: String?): ServerSongRef? = safely {
            val parts = raw?.split(':', limit = 3) ?: return@safely null
            if (parts.size != 3 || parts[0] != "v1") return@safely null
            reference(parts[1], parts[2])
        }

        fun fromMediaUri(raw: String?): ServerSongRef? = safely {
            val uri = URI(raw ?: return@safely null)
            if (uri.scheme != "neri-server" || uri.rawAuthority != uri.host || uri.rawQuery != null || uri.rawFragment != null) return@safely null
            val parts = uri.path.split('/')
            if (parts.size != 3 || parts[1] != "song") return@safely null
            reference(uri.host ?: return@safely null, parts[2])
        }

        fun reference(profile: String, encodedId: String): ServerSongRef {
            require(UUID.fromString(profile).toString() == profile)
            require(encodedId.length in 1..8192)
            val id = String(Base64.getUrlDecoder().decode(encodedId), Charsets.UTF_8)
            require(id.isNotEmpty() && encode(id) == encodedId)
            return ServerSongRef(profile, id)
        }

        fun encode(raw: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))

        fun surrogateId(raw: String): Long = ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        ).long or Long.MIN_VALUE

        private inline fun safely(block: () -> ServerSongRef?): ServerSongRef? =
            try { block() } catch (_: IllegalArgumentException) { null }
            catch (_: java.net.URISyntaxException) { null }
    }
}

fun SongItem.isServerSong(): Boolean = channelId == ServerSongRef.CHANNEL ||
    mediaUri?.startsWith("neri-server://") == true
