package moe.ouom.neriplayer.core.player.runtime.progress

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingMediaLoadPosition
import moe.ouom.neriplayer.core.player.policy.progress.LONG_FORM_PLAYBACK_MIN_DURATION_MS
import moe.ouom.neriplayer.core.player.policy.progress.resolveLongFormPlaybackPositionForPersistence
import moe.ouom.neriplayer.core.player.policy.progress.resolveLongFormPlaybackResumePosition
import moe.ouom.neriplayer.data.model.SongItem

interface PlaybackProgressPort {
    fun rememberLongFormEnabled(): Boolean
    fun rememberedPosition(song: SongItem): Long
    fun writeRememberedPosition(song: SongItem, positionMs: Long)
    fun currentSong(): SongItem?
    fun reportedPositionMs(): Long
    fun playerPositionMs(): Long?
    fun playerDurationMs(): Long?
    fun updateQueuedDurationIfUnknown(song: SongItem, durationMs: Long): Boolean
    fun replaceCurrentSong(song: SongItem)
    fun scheduleImmediateStatePersist()
    fun pendingMediaLoadActive(): Boolean
    fun pendingMediaLoadPositionMs(): Long
}

class PlaybackProgressOwner(
    private val port: PlaybackProgressPort,
    private val sameSong: (SongItem, SongItem?) -> Boolean,
    private val nowElapsedMs: () -> Long
) {
    private val mutableDuration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = mutableDuration
    private var lastLongFormPersistAtMs = 0L
    private var lastStatsUpdateAtMs = 0L
    @Volatile
    private var pendingSeekPositionMs = C.TIME_UNSET
    var expeditedYouTubeSeekRecoveryPending = false
        private set

    fun resetPersistenceClock() {
        lastLongFormPersistAtMs = 0L
    }

    fun resetStatsClock() {
        lastStatsUpdateAtMs = 0L
    }

    fun shouldRecordStats(intervalMs: Long): Boolean {
        val now = nowElapsedMs()
        if (lastStatsUpdateAtMs != 0L && now - lastStatsUpdateAtMs < intervalMs) return false
        lastStatsUpdateAtMs = now
        return true
    }

    fun setDuration(durationMs: Long) {
        mutableDuration.value = durationMs.coerceAtLeast(0L)
    }

    fun onCurrentSongPublished(song: SongItem?) {
        setDuration(song?.durationMs ?: 0L)
    }

    fun resolveExpectedPauseDuration(songDurationMs: Long?, fallbackDurationMs: Long): Long =
        songDurationMs?.takeIf { it > 0L } ?: fallbackDurationMs

    fun shouldFlushShortLocalSong(expectedDurationMs: Long): Boolean =
        expectedDurationMs in 1L..SHORT_LOCAL_SONG_MAX_DURATION_MS

    fun persistPreviousSongProgress(previousSong: SongItem?, nextSong: SongItem?) {
        if (previousSong == null || sameSong(previousSong, nextSong)) return
        persistLongFormProgress(previousSong, port.reportedPositionMs(), mutableDuration.value)
        resetPersistenceClock()
    }

    fun resolveRememberedStartPosition(
        song: SongItem,
        requestedPositionMs: Long,
        allowRememberedPosition: Boolean
    ): Long {
        val requested = requestedPositionMs.coerceAtLeast(0L)
        if (!allowRememberedPosition || !port.rememberLongFormEnabled() ||
            song.durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) return requested
        return resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = song.durationMs,
            requestedPositionMs = requested,
            rememberedPositionMs = port.rememberedPosition(song),
            allowRememberedPosition = allowRememberedPosition
        )
    }

    fun persistLongFormProgress(song: SongItem?, positionMs: Long, durationMs: Long) {
        val current = song ?: return
        val effectiveDuration = maxOf(current.durationMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L))
        val rememberedPosition = resolveLongFormPlaybackPositionForPersistence(
            enabled = port.rememberLongFormEnabled(),
            durationMs = effectiveDuration,
            positionMs = positionMs
        ) ?: return
        port.writeRememberedPosition(current, rememberedPosition)
    }

    fun persistCurrentLongFormProgress() {
        val song = port.currentSong() ?: return
        persistLongFormProgress(
            song,
            maxOf(port.reportedPositionMs(), port.playerPositionMs() ?: 0L),
            maxOf(mutableDuration.value, port.playerDurationMs() ?: 0L)
        )
    }

    fun persistPeriodicLongFormProgress(positionMs: Long, intervalMs: Long) {
        val song = port.currentSong() ?: return
        val durationMs = maxOf(song.durationMs, mutableDuration.value)
        if (!port.rememberLongFormEnabled() || durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) return
        val now = nowElapsedMs()
        if (now - lastLongFormPersistAtMs < intervalMs) return
        lastLongFormPersistAtMs = now
        persistLongFormProgress(song, positionMs, durationMs)
    }

    fun maybeUpdateSongDuration(song: SongItem, durationMs: Long) {
        val resolved = durationMs.takeIf { it > 0L } ?: return
        val queuedChanged = port.updateQueuedDurationIfUnknown(song, resolved)
        val currentChanged = updateMatchingCurrentSongDuration(song, resolved)
        if (queuedChanged || currentChanged) port.scheduleImmediateStatePersist()
    }

    private fun updateMatchingCurrentSongDuration(song: SongItem, durationMs: Long): Boolean {
        val current = port.currentSong()?.takeIf { sameSong(it, song) } ?: return false
        val changed = current.durationMs <= 0L
        if (changed) port.replaceCurrentSong(current.copy(durationMs = durationMs))
        setDuration(durationMs)
        return changed
    }

    fun maybeBackfillCurrentSongDurationFromPlayer() {
        val song = port.currentSong() ?: return
        val playerDuration = port.playerDurationMs()?.takeIf { it > 0L } ?: return
        setDuration(playerDuration)
        maybeUpdateSongDuration(song, playerDuration)
    }

    fun pendingSeekPositionOrNull(): Long? = pendingSeekPositionMs.takeIf { it != C.TIME_UNSET }

    fun rememberPendingSeekPosition(positionMs: Long) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun setExpeditedYouTubeSeekRecoveryPending(pending: Boolean) {
        expeditedYouTubeSeekRecoveryPending = pending
    }

    fun clearPendingSeekPosition() {
        pendingSeekPositionMs = C.TIME_UNSET
        expeditedYouTubeSeekRecoveryPending = false
    }

    fun resolveDisplayedPosition(actualPositionMs: Long): Long {
        val pendingLoad = port.pendingMediaLoadActive()
        val actual = resolvePendingMediaLoadPosition(
            pendingLoadActive = pendingLoad,
            requestedPositionMs = port.pendingMediaLoadPositionMs(),
            livePlayerPositionMs = actualPositionMs
        )
        if (pendingLoad) return actual
        val pending = pendingSeekPositionOrNull() ?: return actual
        return if (kotlin.math.abs(actual - pending) <= PENDING_SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeekPosition()
            actual
        } else {
            pending
        }
    }

    private companion object {
        const val PENDING_SEEK_POSITION_TOLERANCE_MS = 1_500L
        const val SHORT_LOCAL_SONG_MAX_DURATION_MS = 5_000L
    }
}
