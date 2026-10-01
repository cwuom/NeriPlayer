package moe.ouom.neriplayer.core.player.service.artwork

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import kotlin.time.Duration.Companion.milliseconds

private const val NOTIFICATION_ARTWORK_SIZE_PX = 256
private const val MEDIA_ARTWORK_MAX_RETRY_ATTEMPTS = 2

internal enum class PlaybackArtworkChange {
    SOURCE_RESOLVED,
    BITMAP_READY,
    RESOLUTION_FINISHED_EMPTY,
}

internal class PlaybackArtworkSnapshot(
    val coverSource: String?,
    val mediaBitmap: Bitmap?,
    val notificationBitmap: Bitmap?,
    val mediaReady: Boolean,
    val notificationReady: Boolean,
    val pending: Boolean,
)

internal class PlaybackArtworkOwner(
    private val resolver: PlaybackCoverSourceResolver,
    private val loader: PlaybackArtworkBitmapLoader,
    private val clock: PlaybackArtworkClock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onChange: (PlaybackArtworkChange) -> Unit,
) {
    private var activeSong: SongItem? = null
    private var currentCoverSongKey: String? = null
    private var currentCoverSource: String? = null
    private var currentMediaArtwork: Bitmap? = null
    private var currentMediaArtworkOwnerSongKey: String? = null
    private var currentMediaArtworkSource: String? = null
    private var currentNotificationLargeIcon: Bitmap? = null
    private var currentNotificationLargeIconOwnerSongKey: String? = null
    private var currentNotificationLargeIconSource: String? = null
    private var artworkLoadInFlightSource: String? = null
    private var artworkLoadInFlightSongKey: String? = null
    private var artworkLoadInFlightGeneration = 0L
    private var artworkLoadGeneration = 0L
    private var artworkLoadJob: Job? = null
    private var lastArtworkLoadFailedSource: String? = null
    private var lastArtworkLoadFailedSongKey: String? = null
    private var lastArtworkLoadFailedAtElapsedRealtime = -1L
    private var artworkRetryJob: Job? = null
    private var artworkRetryAttemptCount = 0
    private var coverResolutionInFlightSongKey: String? = null
    private var coverResolutionJob: Job? = null
    private var coverResolutionGeneration = 0L
    private var recoveredCoverSongKey: String? = null
    private var recoveredCoverSource: String? = null
    private var recoveredCoverImmediateSource: String? = null

    fun observe(song: SongItem?): PlaybackArtworkSnapshot {
        val songKey = observedSongKey(song)
        val immediateSource = observedImmediateSource(song)
        val source = resolveMetadataCoverSourceWithRecovery(
            songKey = songKey,
            immediateCoverSource = immediateSource,
            retainedSongKey = currentCoverSongKey,
            retainedCoverSource = currentCoverSource,
            recoverySongKey = recoveredCoverSongKey,
            recoveryCoverSource = recoveredCoverSource,
            recoveryImmediateCoverSource = recoveredCoverImmediateSource,
        )
        discardStaleRecovery(songKey, immediateSource)
        activeSong = song
        updateObservedSource(songKey, source)
        if (song == null) {
            clearForNoSong()
        } else {
            resolveCoverSourceAsyncIfNeeded(song, songKey, immediateSource)
        }
        requestLargeIconIfNeeded(currentCoverSource)
        return snapshot(songKey)
    }

    private fun observedSongKey(song: SongItem?): String? = song?.playbackVisualKey()

    private fun observedImmediateSource(song: SongItem?): String? = song?.let(resolver::immediate)

    private fun updateObservedSource(songKey: String?, source: String?) {
        if (songKey == currentCoverSongKey && source == currentCoverSource) return
        currentCoverSongKey = songKey
        currentCoverSource = source
        cancelPendingWork()
    }

    fun snapshot(songKey: String?): PlaybackArtworkSnapshot = PlaybackArtworkSnapshot(
        coverSource = currentCoverSource,
        mediaBitmap = currentMediaArtwork,
        notificationBitmap = currentNotificationLargeIcon,
        mediaReady = isArtworkReadyForSource(
            currentMediaArtwork != null, currentMediaArtworkOwnerSongKey, songKey,
            currentMediaArtworkSource, currentCoverSource,
        ),
        notificationReady = isArtworkReadyForSource(
            currentNotificationLargeIcon != null, currentNotificationLargeIconOwnerSongKey,
            songKey, currentNotificationLargeIconSource, currentCoverSource,
        ),
        pending = songKey != null && (
            !currentCoverSource.isNullOrBlank() || coverResolutionInFlightSongKey == songKey
        ),
    )

    fun snapshotFor(song: SongItem?): PlaybackArtworkSnapshot = snapshot(song?.playbackVisualKey())

    fun close() {
        activeSong = null
        cancelPendingWork()
        clearForNoSong()
        currentCoverSongKey = null
        currentCoverSource = null
    }

    private fun discardStaleRecovery(songKey: String?, immediateSource: String?) {
        if (recoveredCoverSongKey == songKey && immediateSource?.trim() == recoveredCoverImmediateSource) return
        recoveredCoverSongKey = null
        recoveredCoverSource = null
        recoveredCoverImmediateSource = null
    }

    private fun cancelPendingWork() {
        coverResolutionGeneration += 1L
        coverResolutionJob?.cancel()
        coverResolutionJob = null
        coverResolutionInFlightSongKey = null
        artworkLoadGeneration += 1L
        artworkLoadJob?.cancel()
        artworkLoadJob = null
        artworkLoadInFlightSource = null
        artworkLoadInFlightSongKey = null
        artworkLoadInFlightGeneration = 0L
        artworkRetryJob?.cancel()
        artworkRetryJob = null
        artworkRetryAttemptCount = 0
        clearFailure()
    }

    private fun clearForNoSong() {
        coverResolutionJob?.cancel()
        coverResolutionJob = null
        coverResolutionInFlightSongKey = null
        currentMediaArtwork = null
        currentMediaArtworkOwnerSongKey = null
        currentMediaArtworkSource = null
        currentNotificationLargeIcon = null
        currentNotificationLargeIconOwnerSongKey = null
        currentNotificationLargeIconSource = null
        recoveredCoverSongKey = null
        recoveredCoverSource = null
        recoveredCoverImmediateSource = null
        clearFailure()
        artworkLoadGeneration += 1L
        coverResolutionGeneration += 1L
    }

    private fun clearFailure() {
        lastArtworkLoadFailedSource = null
        lastArtworkLoadFailedSongKey = null
        lastArtworkLoadFailedAtElapsedRealtime = -1L
    }

    private fun resolveCoverSourceAsyncIfNeeded(
        song: SongItem,
        songKey: String?,
        immediateSource: String?,
        forceRefresh: Boolean = false,
        expectedCoverSource: String? = currentCoverSource,
    ) {
        if (!shouldStartCoverResolution(song, songKey, immediateSource, forceRefresh)) return
        if (!prepareCoverResolutionSlot(songKey, forceRefresh)) return
        val generation = ++coverResolutionGeneration
        coverResolutionInFlightSongKey = songKey
        coverResolutionJob = scope.launch {
            runCoverResolution(song, checkNotNull(songKey), immediateSource, expectedCoverSource, generation)
        }
    }

    private fun shouldStartCoverResolution(
        song: SongItem, songKey: String?, immediateSource: String?, forceRefresh: Boolean,
    ): Boolean {
        if (songKey == null) return false
        if (forceRefresh) return resolver.isLocal(song)
        if (!immediateSource.isNullOrBlank()) return false
        return needsUnresolvedCover(songKey)
    }

    private fun needsUnresolvedCover(songKey: String): Boolean =
        currentCoverSongKey != songKey || currentCoverSource.isNullOrBlank()

    private fun prepareCoverResolutionSlot(songKey: String?, forceRefresh: Boolean): Boolean {
        if (!hasActiveCoverResolution(songKey)) return true
        if (!forceRefresh) return false
        coverResolutionJob?.cancel()
        return true
    }

    private fun hasActiveCoverResolution(songKey: String?): Boolean =
        coverResolutionInFlightSongKey == songKey && isCoverResolutionJobActive()

    private fun isCoverResolutionJobActive(): Boolean = coverResolutionJob?.isActive == true

    private suspend fun runCoverResolution(
        song: SongItem, songKey: String, immediateSource: String?,
        expectedCoverSource: String?, generation: Long,
    ) {
        var retryOldSource = false
        try {
            val source = resolveSafely(song, immediateSource)
            if (generation == coverResolutionGeneration) {
                retryOldSource = handleResolvedCoverSource(songKey, source, immediateSource, expectedCoverSource)
            }
        } finally {
            finishCoverResolution(generation, retryOldSource, immediateSource, songKey)
        }
    }

    private fun finishCoverResolution(
        generation: Long, retryOldSource: Boolean, immediateSource: String?, songKey: String,
    ) {
        if (generation != coverResolutionGeneration) return
        coverResolutionInFlightSongKey = null
        coverResolutionJob = null
        if (retryOldSource) scheduleArtworkRetry(checkNotNull(immediateSource), songKey)
    }

    private fun handleResolvedCoverSource(
        songKey: String, source: String?, immediateSource: String?, expectedCoverSource: String?,
    ): Boolean {
        val currentSongKey = observedSongKey(activeSong)
        if (!shouldCommitCoverSourceRecovery(currentSongKey, songKey, currentCoverSource, expectedCoverSource)) return false
        val resolvedSource = normalizedArtworkReference(source)
        if (resolvedSource == null) {
            return handleMissingCoverSource(currentSongKey, songKey, immediateSource, expectedCoverSource)
        }
        commitResolvedSource(songKey, resolvedSource, immediateSource)
        return false
    }

    private fun handleMissingCoverSource(
        currentSongKey: String?, songKey: String, immediateSource: String?, expectedCoverSource: String?,
    ): Boolean {
        if (currentSongKey != songKey) return false
        onChange(PlaybackArtworkChange.RESOLUTION_FINISHED_EMPTY)
        return shouldRetryUnresolvedImmediate(immediateSource, expectedCoverSource)
    }

    private fun shouldRetryUnresolvedImmediate(immediateSource: String?, expectedCoverSource: String?): Boolean =
        normalizedArtworkReference(immediateSource) != null && currentCoverSource == expectedCoverSource

    private suspend fun resolveSafely(song: SongItem, failedSource: String?): String? = try {
        withContext(ioDispatcher) { resolver.resolve(song, failedSource) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.d("NERI-APS", "Deferred cover resolve failed: ${error.message}")
        null
    }

    private fun commitResolvedSource(songKey: String, source: String, immediateSource: String?) {
        if (isSameResolvedSource(songKey, source)) {
            requestLargeIconIfNeeded(source)
            return
        }
        recoveredCoverSongKey = songKey
        recoveredCoverSource = source.trim()
        recoveredCoverImmediateSource = normalizedArtworkReference(immediateSource)
        currentCoverSongKey = songKey
        currentCoverSource = source.trim()
        onChange(PlaybackArtworkChange.SOURCE_RESOLVED)
    }

    private fun isSameResolvedSource(songKey: String, source: String): Boolean =
        currentCoverSongKey == songKey && currentCoverSource == source

    private fun requestLargeIconIfNeeded(source: String?) {
        val normalized = normalizedArtworkReference(source) ?: return
        if (!shouldLoadCurrentArtwork(normalized)) return
        requestLargeIconAsync(normalized, currentCoverSongKey)
    }

    private fun shouldLoadCurrentArtwork(source: String): Boolean {
        val ready = isArtworkReadyForSource(
            currentMediaArtwork != null, currentMediaArtworkOwnerSongKey, currentCoverSongKey,
            currentMediaArtworkSource, source,
        )
        return shouldRequestArtworkLoad(
                coverSource = source,
                artworkReady = ready,
                inFlightCoverSource = artworkLoadInFlightSource,
                lastFailedCoverSource = lastArtworkLoadFailedSource,
                lastFailureAtElapsedRealtime = lastArtworkLoadFailedAtElapsedRealtime,
                nowElapsedRealtime = clock.elapsedRealtime(),
                currentSongKey = currentCoverSongKey,
                inFlightSongKey = artworkLoadInFlightSongKey,
                lastFailedSongKey = lastArtworkLoadFailedSongKey,
            )
    }

    private fun requestLargeIconAsync(source: String, songKey: String?) {
        val generation = ++artworkLoadGeneration
        artworkLoadInFlightSource = source
        artworkLoadInFlightSongKey = songKey
        artworkLoadInFlightGeneration = generation
        artworkLoadJob?.cancel()
        artworkLoadJob = scope.launch {
            try {
                val bitmap = withContext(ioDispatcher) { loader.load(source) }
                if (bitmap == null) {
                    markArtworkLoadFailed(source, "drawable was null", songKey, generation)
                    return@launch
                }
                val icon = withContext(ioDispatcher) { bitmap.scaledToMaxDimension(NOTIFICATION_ARTWORK_SIZE_PX) }
                commitBitmap(source, songKey, generation, bitmap, icon)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                markArtworkLoadFailed(source, error.message, songKey, generation)
            }
        }
    }

    private fun acceptsLoad(source: String, songKey: String?, generation: Long): Boolean =
        shouldAcceptArtworkLoadCallback(
            generation, artworkLoadGeneration, artworkLoadInFlightGeneration,
            source, artworkLoadInFlightSource, songKey, artworkLoadInFlightSongKey,
        )

    private fun clearInFlightLoad() {
        artworkLoadInFlightSource = null
        artworkLoadInFlightSongKey = null
        artworkLoadInFlightGeneration = 0L
        artworkLoadJob = null
    }

    private fun commitBitmap(
        source: String, songKey: String?, generation: Long, bitmap: Bitmap, icon: Bitmap,
    ) {
        if (!acceptsLoad(source, songKey, generation)) return
        clearInFlightLoad()
        if (!isCurrentCover(source, songKey)) return
        clearFailure()
        artworkRetryJob?.cancel()
        artworkRetryJob = null
        artworkRetryAttemptCount = 0
        currentMediaArtwork = bitmap
        currentMediaArtworkOwnerSongKey = songKey
        currentMediaArtworkSource = source
        currentNotificationLargeIcon = icon
        currentNotificationLargeIconOwnerSongKey = songKey
        currentNotificationLargeIconSource = source
        onChange(PlaybackArtworkChange.BITMAP_READY)
        NPLogger.d("NERI-APS", "cover bitmap=${bitmap.width}x${bitmap.height}, bytes=${bitmap.byteCount / 1024 / 1024}MB")
    }

    private fun isCurrentCover(source: String, songKey: String?): Boolean =
        source == currentCoverSource && songKey == currentCoverSongKey

    private fun markArtworkLoadFailed(
        source: String, reason: String?, songKey: String?, generation: Long,
    ) {
        if (!acceptsLoad(source, songKey, generation)) return
        clearInFlightLoad()
        recordCurrentArtworkFailure(source, songKey)
        NPLogger.d("NERI-APS", "Cover load failed: ${reason ?: "unknown"}")
    }

    private fun recordCurrentArtworkFailure(source: String, songKey: String?) {
        if (!isCurrentCover(source, songKey)) return
        lastArtworkLoadFailedSource = source
        lastArtworkLoadFailedSongKey = songKey
        lastArtworkLoadFailedAtElapsedRealtime = clock.elapsedRealtime()
        retryOrResolveFailedCover(source, songKey)
    }

    private fun retryOrResolveFailedCover(source: String, songKey: String?) {
        val song = activeSong
        val local = song?.let(resolver::isLocal) == true
        val customRemote = hasMatchingCustomRemoteCover(song, source)
        val resolve = shouldResolveLocalArtworkFailure(song, songKey, local, customRemote)
        if (resolve) {
            resolveCoverSourceAsyncIfNeeded(
                checkNotNull(song), songKey, source, forceRefresh = true, expectedCoverSource = source,
            )
        }
        if (!shouldDeferArtworkRetryToCoverResolver(local && !customRemote, resolve)) {
            scheduleArtworkRetry(source, songKey)
        }
    }

    private fun hasMatchingCustomRemoteCover(song: SongItem?, source: String): Boolean {
        val custom = song?.customCoverUrl?.trim() ?: return false
        return custom == source && resolveRemoteMetadataArtworkUri(custom) != null
    }

    private fun shouldResolveLocalArtworkFailure(
        song: SongItem?, songKey: String?, local: Boolean, customRemote: Boolean,
    ): Boolean = song != null && local && !customRemote && song.playbackVisualKey() == songKey

    private fun scheduleArtworkRetry(source: String, songKey: String?) {
        if (artworkRetryAttemptCount >= MEDIA_ARTWORK_MAX_RETRY_ATTEMPTS) return
        artworkRetryAttemptCount += 1
        artworkRetryJob?.cancel()
        artworkRetryJob = scope.launch {
            delay(MEDIA_ARTWORK_RETRY_COOLDOWN_MS.milliseconds)
            if (!isCurrentCover(source, songKey)) return@launch
            if (snapshot(songKey).mediaReady) return@launch
            requestLargeIconIfNeeded(source)
        }
    }

    private fun Bitmap.scaledToMaxDimension(maxDimensionPx: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimensionPx) return this
        val scale = maxDimensionPx.toFloat() / longestSide
        return scale((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    }
}
