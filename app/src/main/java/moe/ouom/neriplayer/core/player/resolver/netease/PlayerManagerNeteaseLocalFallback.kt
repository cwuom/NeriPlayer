package moe.ouom.neriplayer.core.player.resolver.netease

import androidx.core.net.toUri
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.url.buildLocalPlaybackAudioInfo
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import kotlin.math.absoluteValue

internal const val NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS = 8_000L
private val localFallbackNonTextRegex = Regex("[^\\p{L}\\p{N}]+")

internal suspend fun PlayerManager.tryResolveNeteaseMatchedLocalSource(song: SongItem): SongUrlResult? {
    if (!neteaseLocalSourceFallbackEnabled) return null

    val localSongs = localRepo.playlists.value.flatMap { it.songs }
    val candidates = selectNeteaseLocalFallbackCandidates(song, localSongs)
    if (candidates.isEmpty()) return null

    for (candidate in candidates) {
        val localMediaUri = AudioDownloadManager.resolvePermittedLocalPlaybackUri(
            context = application,
            song = candidate,
            rawLocalReference = localMediaSource(candidate)
        ) ?: continue
        if (!isReadableLocalMediaUri(localMediaUri)) continue
        val playableUrl = toPlayableLocalUrl(localMediaUri) ?: localMediaUri
        NPLogger.w(
            "NERI-PlayerManager",
            "Netease source unavailable, fallback to matched local audio: " +
                "song=${song.name}, matchedSongId=${candidate.matchedSongId}, " +
                "local=${candidate.localFileName ?: localMediaUri}, " +
                "songDurationMs=${song.durationMs}, localDurationMs=${candidate.durationMs}"
        )
        val playableAudioInfo = runCatching { playableUrl.toUri() }.getOrNull()
            ?.let { buildLocalPlaybackAudioInfo(it, application) }
            ?: PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL)
        return SongUrlResult.Success(
            url = playableUrl,
            audioInfo = playableAudioInfo.copy(isNeteaseLocalFallback = true),
            isNeteaseLocalFallback = true
        )
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "Matched local audio not readable, skip local fallback: song=${song.name}"
    )
    return null
}

internal fun selectNeteaseLocalFallbackCandidates(
    song: SongItem,
    localSongs: List<SongItem>
): List<SongItem> {
    val localCandidates = localSongs.filter { candidate ->
        LocalSongSupport.isLocalSong(candidate)
    }
    if (localCandidates.isEmpty()) return emptyList()

    val targetId = song.id.toString()
    val exact = localCandidates.filter { candidate ->
        candidate.matchedLyricSource == MusicPlatform.CLOUD_MUSIC &&
            candidate.matchedSongId == targetId &&
            isNeteaseLocalFallbackDurationCompatible(song, candidate)
    }
    val exactKeys = exact.mapTo(mutableSetOf()) { it.stableKey() }
    val rest = localCandidates.filterNot { it.stableKey() in exactKeys }
    val fuzzy = rest.filter { candidate ->
        matchesNeteaseLocalFallbackMetadata(song, candidate)
    }
    return (sortByDurationCloseness(song, exact) + sortByDurationCloseness(song, fuzzy))
        .distinctBy { it.stableKey() }
}

private fun isNeteaseLocalFallbackDurationCompatible(
    song: SongItem,
    candidate: SongItem
): Boolean {
    if (song.durationMs <= 0L || candidate.durationMs <= 0L) return true
    return (candidate.durationMs - song.durationMs).absoluteValue <=
        NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS
}

internal fun matchesNeteaseLocalFallbackMetadata(song: SongItem, candidate: SongItem): Boolean {
    if (song.durationMs <= 0L || candidate.durationMs <= 0L) return false
    val durationDiff = (candidate.durationMs - song.durationMs).absoluteValue
    if (durationDiff > NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS) return false

    val targetTitle = compactLocalFallbackText(song.originalName ?: song.name)
    if (targetTitle.isEmpty()) return false
    if (compactLocalFallbackText(candidate.displayName()) != targetTitle) return false

    val targetArtist = compactLocalFallbackText(song.originalArtist ?: song.artist)
    if (targetArtist.isEmpty()) return true
    return compactLocalFallbackText(candidate.displayArtist()) == targetArtist
}

private fun sortByDurationCloseness(song: SongItem, candidates: List<SongItem>): List<SongItem> {
    if (candidates.size <= 1) return candidates
    return candidates.sortedBy { candidate ->
        if (song.durationMs > 0L && candidate.durationMs > 0L) {
            (candidate.durationMs - song.durationMs).absoluteValue
        } else {
            Long.MAX_VALUE
        }
    }
}

private fun compactLocalFallbackText(value: String): String {
    return localFallbackNonTextRegex.replace(value.lowercase(), "")
}
