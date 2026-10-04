package moe.ouom.neriplayer.core.player.service.car.library

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object CarMediaIds {
    private const val PREFIX = "neri-car:v1/"
    private const val MAX_ID_LENGTH = 1_024

    const val ROOT = "${PREFIX}root"
    const val CATALOGUE = "${PREFIX}catalogue"
    const val QUEUE = "${PREFIX}queue"
    const val PLAYLISTS = "${PREFIX}playlists"
    const val HISTORY = "${PREFIX}history"
    const val OFFLINE = "${PREFIX}offline"

    fun playlist(playlistId: Long): String = "${PREFIX}playlist/$playlistId"

    internal fun search(query: String): String = "${PREFIX}search/${encode(query)}"

    internal fun page(directoryId: String, start: Int, end: Int): String =
        "${PREFIX}page/${encode(directoryId)}/$start/$end"

    internal fun song(directoryId: String, index: Int, identity: String): String =
        "${PREFIX}song/${encode(directoryId)}/$index/${identityDigest(identity)}"

    internal fun parse(mediaId: String): CarMediaRoute? {
        if (mediaId.length > MAX_ID_LENGTH || !mediaId.startsWith(PREFIX)) return null
        val parts = mediaId.removePrefix(PREFIX).split('/')
        return when (parts.firstOrNull()) {
            "page" -> parsePage(parts)
            "song" -> parseSong(parts)
            else -> parseDirectory(mediaId)
        }
    }

    private fun parsePage(parts: List<String>): CarMediaRoute.Page? {
        if (parts.size != 4) return null
        val directory = decode(parts[1])?.let(::parseDirectory) ?: return null
        val start = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val end = parts[3].toIntOrNull()?.takeIf { it > start } ?: return null
        return CarMediaRoute.Page(directory, start, end)
    }

    private fun parseSong(parts: List<String>): CarMediaRoute.Song? {
        if (parts.size != 4) return null
        val directory = decode(parts[1])?.let(::parseDirectory) ?: return null
        val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val digest = parts[3].takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return CarMediaRoute.Song(directory, index, digest)
    }

    private fun parseDirectory(mediaId: String): CarMediaRoute.Directory? {
        return when (mediaId) {
            ROOT -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.ROOT)
            CATALOGUE -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.CATALOGUE)
            QUEUE -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.QUEUE)
            PLAYLISTS -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.PLAYLISTS)
            HISTORY -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.HISTORY)
            OFFLINE -> CarMediaRoute.Directory(mediaId, CarDirectoryKind.OFFLINE)
            else -> parseParameterizedDirectory(mediaId)
        }
    }

    private fun parseParameterizedDirectory(mediaId: String): CarMediaRoute.Directory? {
        val parts = mediaId.removePrefix(PREFIX).split('/')
        if (parts.size != 2 || !mediaId.startsWith(PREFIX)) return null
        return when (parts[0]) {
            "playlist" -> parts[1].toLongOrNull()?.let {
                CarMediaRoute.Directory(mediaId, CarDirectoryKind.PLAYLIST, playlistId = it)
            }
            "search" -> decode(parts[1])?.let(CarLibrarySearch::normalize)?.let {
                CarMediaRoute.Directory(mediaId, CarDirectoryKind.SEARCH, query = it)
            }
            else -> null
        }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    internal fun identityDigest(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}

internal enum class CarDirectoryKind { ROOT, CATALOGUE, QUEUE, PLAYLISTS, HISTORY, OFFLINE, PLAYLIST, SEARCH }

internal sealed interface CarMediaRoute {
    data class Directory(
        val mediaId: String,
        val kind: CarDirectoryKind,
        val playlistId: Long? = null,
        val query: String? = null
    ) : CarMediaRoute

    data class Page(val directory: Directory, val start: Int, val end: Int) : CarMediaRoute
    data class Song(val directory: Directory, val index: Int, val digest: String) : CarMediaRoute
}
