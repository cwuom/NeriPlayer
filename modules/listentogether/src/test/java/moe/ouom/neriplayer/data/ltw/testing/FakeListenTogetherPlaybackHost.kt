package moe.ouom.neriplayer.data.ltw.testing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

internal class FakeListenTogetherPlaybackHost : ListenTogetherPlaybackHost {
    override val currentSongFlow = MutableStateFlow<SongItem?>(null)
    override val currentQueueFlow = MutableStateFlow<List<SongItem>>(emptyList())
    override val isPlayingFlow = MutableStateFlow(false)
    override val playWhenReadyFlow = MutableStateFlow(false)
    override val playerPlaybackStateFlow = MutableStateFlow(1)
    override val playbackPositionFlow = MutableStateFlow(0L)
    override val currentMediaUrlFlow = MutableStateFlow<String?>(null)
    override val repeatModeFlow = MutableStateFlow(0)
    override val shuffleModeFlow = MutableStateFlow(false)
    override val playbackCommandFlow = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 8)
    override var shuffleRestorePlaylistReference: List<SongItem>? = null
    var syncPlaybackRate = 1f
    override val listenTogetherSyncPlaybackRate get() = syncPlaybackRate
    var safetyPause = false
    var waitForStream = false
    var resolutionPending = false
    var streamUnavailable = false
    var requiresAuthoritativeStream = false
    var pendingMediaLoad = false
    var transportActive = false
    var transportFailure: Throwable? = null
    var usableDirectStream = false
    var streamUrls = emptyList<String>()
    val calls = mutableListOf<String>()
    val commandSources = mutableListOf<PlaybackCommandSource>()

    override fun pauseImpl(forcePersist: Boolean, commandSource: PlaybackCommandSource, allowFadeOut: Boolean, debugReason: String) {
        calls += "pause:$debugReason"
        commandSources += commandSource
        isPlayingFlow.value = false
        playWhenReadyFlow.value = false
    }
    override fun play(commandSource: PlaybackCommandSource) {
        calls += "play"
        commandSources += commandSource
        playWhenReadyFlow.value = true
    }
    override fun seekTo(positionMs: Long, commandSource: PlaybackCommandSource) {
        calls += "seek:$positionMs"
        commandSources += commandSource
        playbackPositionFlow.value = positionMs
    }
    override fun playPlaylist(queue: List<SongItem>, currentIndex: Int, commandSource: PlaybackCommandSource) {
        calls += "playlist:$currentIndex"
        commandSources += commandSource
        currentQueueFlow.value = queue
        currentSongFlow.value = queue.getOrNull(currentIndex)
        playWhenReadyFlow.value = true
    }
    override fun applyRemoteQueueUpdate(queue: List<SongItem>, currentIndex: Int) {
        calls += "queue:$currentIndex"
        currentQueueFlow.value = queue
        currentSongFlow.value = queue.getOrNull(currentIndex)
    }
    override fun applyListenTogetherPlaybackMode(repeatMode: Int?, shuffleEnabled: Boolean?) {
        calls += "mode:$repeatMode:$shuffleEnabled"
        repeatMode?.let { repeatModeFlow.value = it }
        shuffleEnabled?.let { shuffleModeFlow.value = it }
    }
    override fun resetListenTogetherSyncPlaybackRate() {
        calls += "rate:reset"
        syncPlaybackRate = 1f
    }
    override fun setListenTogetherSyncPlaybackRate(rate: Float) {
        calls += "rate:$rate"
        syncPlaybackRate = rate
    }
    override fun retryListenTogetherSafetyPauseResume() { calls += "safety:retry" }
    override fun completeListenTogetherSafetyPauseResume() { calls += "safety:complete" }
    override fun clearListenTogetherSafetyPause() { calls += "safety:clear"; safetyPause = false }
    override fun shouldHoldListenTogetherPlaybackForSafetyPause(causeType: String?) = safetyPause
    override fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem) = waitForStream
    override fun isListenTogetherLocalResolutionPendingFor(song: SongItem) = resolutionPending
    override fun isListenTogetherAuthoritativeStreamConfirmedUnavailable(song: SongItem) = streamUnavailable
    override fun currentPlaybackRequiresListenTogetherAuthoritativeStream() = requiresAuthoritativeStream
    override fun currentListenTogetherShareableStreamUrls() = streamUrls
    override fun isPendingMediaLoadActive() = pendingMediaLoad
    override fun isTransportActive(): Boolean {
        transportFailure?.let { throw it }
        return transportActive
    }
    override fun currentSong() = currentSongFlow.value
    override fun stableKey(song: SongItem) = with(TestSongMapper) { song.toListenTogetherTrackOrNull()?.stableKey }
    override fun playbackPositionMs() = playbackPositionFlow.value
    override fun playbackResolutionPending() = resolutionPending
    override suspend fun resolveShareableStreamUrls(song: SongItem) = ListenTogetherStreamResolution(streamUrls, false)
    override fun hasUsableLocalDirectStream(track: ListenTogetherTrack) = usableDirectStream
}
