package moe.ouom.neriplayer.data.model.playlist

import java.util.Locale
import moe.ouom.neriplayer.data.model.SongItem

data class LocalArtistSummary(
    val name: String,
    val songs: List<SongItem>
) {
    val id: Long
        get() = localArtistStableId(name)

    val stableKey: String
        get() = localArtistStableKey(name)

    val coverSong: SongItem?
        get() = songs.firstOrNull()
}

fun localArtistStableKey(name: String): String {
    return name.trim().lowercase(Locale.ROOT)
}

fun localArtistStableId(name: String): Long {
    val key = localArtistStableKey(name)
    var hash = FNV_64_OFFSET_BASIS
    key.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= FNV_64_PRIME
    }
    return hash and Long.MAX_VALUE
}

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L

private const val FNV_64_PRIME = 1099511628211L
