package moe.ouom.neriplayer.core.player.service.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

internal class PlaybackCoverSourceResolver(private val sources: PlaybackCoverSources) {
    fun immediate(song: SongItem): String? = sources.immediate(song)

    fun isLocal(song: SongItem): Boolean = sources.isLocal(song)

    suspend fun resolve(song: SongItem, failedSource: String?): String? {
        val localSong = sources.isLocal(song)
        if (localSong) {
            firstUsable(failedSource, safely { sources.peekLocal(song) }, safely { sources.nearby(song) })
                ?.let { return it }
        }
        currentCoroutineContext().ensureActive()
        usable(safely { sources.resolveLocal(song) }, failedSource)?.let { return it }

        currentCoroutineContext().ensureActive()
        val downloaded = sources.downloaded(song)
        rebindIndexedCover(song, downloaded, failedSource)?.let { return it }
        return remoteFallback(song, downloaded, localSong, failedSource)
    }

    private fun firstUsable(failedSource: String?, vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { usable(it, failedSource) }

    private fun usable(candidate: String?, failedSource: String?): String? {
        val normalized = normalizedArtworkReference(candidate) ?: return null
        if (normalized == failedSource?.trim()) return null
        if (resolveRemoteMetadataArtworkUri(normalized) != null) return normalized
        return normalized.takeIf(::isUsableSafely)
    }

    private fun isUsableSafely(reference: String): Boolean =
        safely { sources.isUsable(reference) } == true

    private suspend fun rebindIndexedCover(
        song: SongItem,
        downloaded: DownloadedArtworkReference?,
        failedSource: String?,
    ): String? {
        val references = listOfNotNull(downloaded?.coverPath, song.customCoverUrl, song.coverUrl)
            .mapNotNull { it.trim().takeIf(::isLocalCoverReference) }
            .distinct()
        for (reference in references) {
            currentCoroutineContext().ensureActive()
            val name = coverReferenceFileName(reference) ?: continue
            usable(rebindSafely(name, false), failedSource)?.let { return it }
            currentCoroutineContext().ensureActive()
            usable(rebindSafely(name, true), failedSource)?.let { return it }
        }
        return null
    }

    private suspend fun rebindSafely(name: String, forceRefresh: Boolean): String? = try {
        val result = sources.rebind(name, forceRefresh)
        currentCoroutineContext().ensureActive()
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.d("NERI-APS", "Managed cover lookup failed: file=$name, message=${error.message}")
        null
    }

    private fun remoteFallback(
        song: SongItem,
        downloaded: DownloadedArtworkReference?,
        localSong: Boolean,
        failedSource: String?,
    ): String? {
        val explicitCustomCover = resolveRemoteMetadataArtworkUri(song.customCoverUrl) != null
        if (!shouldAllowServiceRemoteCoverFallback(localSong, explicitCustomCover)) return null
        val candidates = if (localSong) listOfNotNull(song.customCoverUrl) else listOfNotNull(
            song.customCoverUrl, downloaded?.coverUrl, song.coverUrl, song.originalCoverUrl,
        )
        return candidates.firstOrNull { candidate ->
            val normalized = candidate.trim()
            resolveRemoteMetadataArtworkUri(normalized) != null && normalized != failedSource?.trim()
        }
    }

    private inline fun <T> safely(read: () -> T): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
