package moe.ouom.neriplayer.listentogether.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.url.ShareableListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrls
import moe.ouom.neriplayer.listentogether.playback.authoritativeStreamUrlsForCurrentTrack
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherAuthoritativeStreamAvailability
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.listentogether.playback.shouldDeferControllerLinkResolution
import moe.ouom.neriplayer.listentogether.playback.shouldPublishControllerLinkUnavailable
import moe.ouom.neriplayer.listentogether.playback.shouldRequestListenTogetherControllerLink
import moe.ouom.neriplayer.listentogether.playback.shouldRetryControllerLinkResolution
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.util.units.SECOND_MS

internal interface ListenTogetherLinkSessionPort {
    fun sessionState(): ListenTogetherSessionState
    fun roomState(): ListenTogetherRoomState?
    fun publish(event: ListenTogetherEvent, reason: String, noteSync: Boolean): Boolean
}

internal interface ListenTogetherLinkPlaybackPort {
    fun currentSong(): SongItem?
    fun playbackPositionMs(): Long
    fun playbackResolutionPending(): Boolean
    suspend fun resolveShareableStreamUrls(song: SongItem): ShareableListenTogetherStreamResolution
    fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean
}

internal interface ListenTogetherLinkEventPort {
    fun requestLink(
        stableKey: String,
        currentIndex: Int,
        track: ListenTogetherTrack,
        forceRefresh: Boolean
    ): ListenTogetherEvent

    fun linkReady(
        stableKey: String,
        positionMs: Long,
        streamUrlsOverride: List<String>
    ): ListenTogetherEvent?

    fun linkUnavailable(stableKey: String): ListenTogetherEvent?
}

internal data class ListenTogetherControllerLinkTiming(
    val requestThrottleMs: Long = 4 * SECOND_MS,
    val playbackResolutionPollMs: Long = 200L,
    val playbackResolutionPollCount: Int = 40,
    val resolutionRetryDelayMs: Long = 2 * SECOND_MS,
    val resolutionAttempts: Int = 3
)

internal class ListenTogetherControllerLinkOwner(
    private val scope: CoroutineScope,
    private val session: ListenTogetherLinkSessionPort,
    private val playback: ListenTogetherLinkPlaybackPort,
    private val events: ListenTogetherLinkEventPort,
    private val timing: ListenTogetherControllerLinkTiming = ListenTogetherControllerLinkTiming(),
    private val elapsedRealtimeMs: () -> Long
) {
    private val availability = ListenTogetherAuthoritativeStreamAvailability()
    @Volatile
    private var resolveStableKey: String? = null
    @Volatile
    private var resolveJob: Job? = null
    @Volatile
    private var lastRequestedStableKey: String? = null
    @Volatile
    private var lastRequestedAtMs: Long = 0L

    fun cancelPending() {
        resolveJob?.cancel()
        resolveJob = null
        resolveStableKey = null
    }

    fun clear() {
        cancelPending()
        availability.clear()
        lastRequestedStableKey = null
        lastRequestedAtMs = 0L
    }

    fun isUnavailable(roomId: String?, stableKey: String?): Boolean =
        availability.isUnavailable(roomId, stableKey)

    fun clearAvailability() {
        availability.clear()
    }

    private fun isController(snapshot: ListenTogetherSessionState): Boolean =
        resolveListenTogetherSessionRole(
            sessionUserId = snapshot.userUuid,
            fallbackRole = snapshot.role,
            state = session.roomState()
        ) == "controller"

    private fun isConnectedController(): Boolean {
        val snapshot = session.sessionState()
        return snapshot.connectionState == ListenTogetherConnectionState.CONNECTED &&
            isController(snapshot)
    }

    private fun controllerRoomWithSharing(): ListenTogetherRoomState? {
        if (!isConnectedController()) return null
        val room = session.roomState() ?: return null
        return room.takeIf { it.settings.normalized().shareAudioLinks }
    }

    private fun currentStableKey(): String? =
        playback.currentSong()?.toListenTogetherTrackOrNull()?.stableKey

    private fun matchingCurrentStableKey(room: ListenTogetherRoomState): String? {
        val stableKey = currentStableKey() ?: return null
        return stableKey.takeIf { it == room.currentStableKey() }
    }

    fun maybePublishAfterAudioSharingEnabled(
        previousState: ListenTogetherRoomState?,
        currentState: ListenTogetherRoomState,
        reason: String
    ) {
        if (!audioSharingJustEnabled(previousState, currentState)) return
        val stableKey = matchingCurrentStableKey(currentState) ?: return
        resolveAndPublish(stableKey, "audio_links_enabled:$reason")
    }

    private fun audioSharingJustEnabled(
        previousState: ListenTogetherRoomState?,
        currentState: ListenTogetherRoomState
    ): Boolean = previousState?.settings.normalized().shareAudioLinks == false &&
        currentState.settings.normalized().shareAudioLinks

    fun reconcileAvailability(state: ListenTogetherRoomState) {
        availability.reconcile(
            roomId = state.roomId,
            stableKey = state.currentStableKey(),
            hasAuthoritativeStream = state.authoritativeStreamUrlsForCurrentTrack().isNotEmpty()
        )
    }

    fun markUnavailable(
        state: ListenTogetherRoomState,
        requestedStableKey: String?,
        signalId: String?
    ): Boolean {
        val stableKey = unavailableTarget(state, requestedStableKey) ?: return false
        val confirmed = availability.markUnavailable(state.roomId, stableKey, signalId)
        NPLogger.d(
            TAG,
            "markUnavailable(): roomId=${state.roomId}, stableKey=$stableKey, confirmed=$confirmed"
        )
        return !confirmed && availability.isAwaitingConfirmation(state.roomId, stableKey)
    }

    private fun unavailableTarget(
        state: ListenTogetherRoomState,
        requestedStableKey: String?
    ): String? {
        if (isController(session.sessionState())) return null
        val stableKey = state.currentStableKey() ?: return null
        if (!requestedTargetMatchesCurrent(requestedStableKey, stableKey)) return null
        if (state.authoritativeStreamUrlsForCurrentTrack().isNotEmpty()) return null
        return stableKey
    }

    private fun requestedTargetMatchesCurrent(requestedStableKey: String?, stableKey: String): Boolean {
        val requested = requestedStableKey?.trim() ?: return true
        if (requested.isEmpty()) return true
        return requested == stableKey
    }

    fun maybePublishCurrentLink(reason: String) {
        val roomState = controllerRoomWithSharing() ?: return
        val stableKey = matchingCurrentStableKey(roomState) ?: return
        resolveAndPublish(stableKey, reason)
    }

    private fun publishReadyIfPossible(
        stableKey: String,
        reason: String,
        streamUrlsOverride: List<String> = emptyList()
    ): Boolean {
        if (controllerRoomWithSharing() == null) return false
        if (currentStableKey() != stableKey) return false
        val event = events.linkReady(
            stableKey = stableKey,
            positionMs = playback.playbackPositionMs().coerceAtLeast(0L),
            streamUrlsOverride = streamUrlsOverride
        ) ?: return false
        NPLogger.d(TAG, "publishReadyIfPossible(): reason=$reason, eventId=${event.eventId}, stableKey=$stableKey")
        return session.publish(event, "publish_link_ready:$reason", noteSync = true)
    }

    private fun publishUnavailable(stableKey: String, reason: String): Boolean {
        val event = unavailableEventForCurrentTrack(stableKey) ?: return false
        NPLogger.d(TAG, "publishUnavailable(): reason=$reason, eventId=${event.eventId}, stableKey=$stableKey")
        return session.publish(event, "publish_link_unavailable:$reason", noteSync = true)
    }

    private fun unavailableEventForCurrentTrack(stableKey: String): ListenTogetherEvent? {
        if (controllerRoomWithSharing()?.currentStableKey() != stableKey) return null
        return events.linkUnavailable(stableKey)
    }

    fun maybeRequest(
        state: ListenTogetherRoomState,
        causeType: String?,
        force: Boolean = false,
        bypassThrottle: Boolean = false
    ) {
        val targetTrack = requestTarget(state, force) ?: return
        val stableKey = targetTrack.stableKey
        if (!shouldRequestListenTogetherControllerLink(
                force = force,
                controllerLinkUnavailable = availability.isUnavailable(state.roomId, stableKey)
            )
        ) return
        val nowElapsedMs = elapsedRealtimeMs()
        if (requestIsThrottled(stableKey, nowElapsedMs, bypassThrottle)) {
            NPLogger.d(TAG, "maybeRequest(): throttled, stableKey=$stableKey, causeType=$causeType")
            return
        }
        val event = events.requestLink(
            stableKey = stableKey,
            currentIndex = state.currentIndex,
            track = targetTrack.withStreamUrls(emptyList()),
            forceRefresh = force
        )
        lastRequestedStableKey = stableKey
        lastRequestedAtMs = nowElapsedMs
        NPLogger.d(TAG, "maybeRequest(): causeType=$causeType, eventId=${event.eventId}, stableKey=$stableKey")
        session.publish(event, "request_controller_link:$causeType", noteSync = false)
    }

    private fun requestTarget(state: ListenTogetherRoomState, force: Boolean): ListenTogetherTrack? {
        if (!isConnectedListener()) return null
        if (!roomAllowsLinkRequest(state)) return null
        val target = state.currentTrack() ?: return null
        if (!force && targetHasDirectStream(target)) return null
        return target.takeIf { it.stableKey.isNotBlank() }
    }

    private fun roomAllowsLinkRequest(state: ListenTogetherRoomState): Boolean =
        state.settings.normalized().shareAudioLinks && state.roomStatus == ListenTogetherRoomStatuses.ACTIVE

    private fun targetHasDirectStream(target: ListenTogetherTrack): Boolean =
        target.hasDirectStream() || playback.hasUsableLocalDirectStream(target)

    private fun isConnectedListener(): Boolean {
        val snapshot = session.sessionState()
        return snapshot.connectionState == ListenTogetherConnectionState.CONNECTED && !isController(snapshot)
    }

    private fun requestIsThrottled(stableKey: String, nowElapsedMs: Long, bypassThrottle: Boolean): Boolean =
        !bypassThrottle && lastRequestedStableKey == stableKey &&
            nowElapsedMs - lastRequestedAtMs < timing.requestThrottleMs

    private fun ListenTogetherTrack.hasDirectStream(): Boolean =
        normalizedDirectStreamUrl(streamUrl) != null ||
            streamUrls.any { normalizedDirectStreamUrl(it) != null }

    fun resolveAndPublish(stableKey: String, reason: String) {
        if (controllerRoomWithSharing() == null) return
        val song = currentSongFor(stableKey) ?: return
        if (isResolving(stableKey)) return
        cancelPending()
        resolveStableKey = stableKey
        launchResolution(song, stableKey, reason)
    }

    private fun currentSongFor(stableKey: String): SongItem? {
        val song = playback.currentSong() ?: return null
        return song.takeIf { it.toListenTogetherTrackOrNull()?.stableKey == stableKey }
    }

    private fun isResolving(stableKey: String): Boolean {
        val job = resolveJob ?: return false
        if (!job.isActive) return false
        return resolveStableKey == stableKey
    }

    private fun launchResolution(song: SongItem, stableKey: String, reason: String) {
        resolveJob = scope.launch {
            try {
                resolveLoop(song, stableKey, reason)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "resolveAndPublish(): failed, stableKey=$stableKey, reason=$reason, error=${error.message}")
            } finally {
                if (resolveStableKey == stableKey) {
                    resolveStableKey = null
                    resolveJob = null
                }
            }
        }
    }

    private suspend fun resolveLoop(song: SongItem, stableKey: String, reason: String) {
        for (attempt in 0 until timing.resolutionAttempts) {
            if (!resolveAttempt(song, stableKey, reason, attempt)) return
        }
    }

    private suspend fun resolveAttempt(
        song: SongItem,
        stableKey: String,
        reason: String,
        attempt: Int
    ): Boolean {
        if (!awaitPlaybackResolution(stableKey)) return false
        if (currentStableKey() != stableKey) return false
        val resolution = playback.resolveShareableStreamUrls(song)
        if (resolution.streamUrls.isNotEmpty()) {
            publishResolvedStream(stableKey, reason, resolution.streamUrls)
            return false
        }
        return handleMissingStream(stableKey, reason, attempt, resolution)
    }

    private fun publishResolvedStream(stableKey: String, reason: String, streamUrls: List<String>) {
        if (currentStableKey() != stableKey) return
        publishReadyIfPossible(stableKey, "resolved:$reason", streamUrls)
    }

    private suspend fun handleMissingStream(
        stableKey: String,
        reason: String,
        attempt: Int,
        resolution: ShareableListenTogetherStreamResolution
    ): Boolean {
        if (isPlaybackResolutionPending(stableKey)) return false
        if (shouldRetryControllerLinkResolution(
                attempt = attempt,
                maximumAttempts = timing.resolutionAttempts,
                hasShareableStream = false,
                playbackResolutionPending = false
            )
        ) {
            delay(timing.resolutionRetryDelayMs)
            return true
        }
        if (shouldPublishControllerLinkUnavailable(
                attempt = attempt,
                maximumAttempts = timing.resolutionAttempts,
                hasShareableStream = false,
                playbackResolutionPending = false
            )
        ) publishMissingStream(stableKey, reason, resolution.isPreviewOnly)
        return false
    }

    private fun publishMissingStream(stableKey: String, reason: String, previewOnly: Boolean) {
        if (publishReadyIfPossible(stableKey, "current_stream_fallback:$reason")) return
        publishUnavailable(stableKey, if (previewOnly) "preview_only:$reason" else "unavailable:$reason")
    }

    private fun isPlaybackResolutionPending(stableKey: String): Boolean =
        shouldDeferControllerLinkResolution(
            playbackResolutionPending = playback.playbackResolutionPending(),
            currentTrackStableKey = currentStableKey(),
            requestedStableKey = stableKey
        )

    private suspend fun awaitPlaybackResolution(stableKey: String): Boolean {
        repeat(timing.playbackResolutionPollCount) {
            if (!isPlaybackResolutionPending(stableKey)) return true
            delay(timing.playbackResolutionPollMs)
        }
        return !isPlaybackResolutionPending(stableKey)
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
