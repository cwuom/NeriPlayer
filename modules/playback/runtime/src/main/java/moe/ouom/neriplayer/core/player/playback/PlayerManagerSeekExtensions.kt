package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lan.LanPlaybackBinding
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.policy.pending.PendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.SeekExecutionAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.resolveSeekExecutionAction
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForSeek
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager

internal fun PlayerManager.seekToImpl(
    positionMs: Long,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (LanPlaybackBinding.route("SEEK", commandSource, position = positionMs / 1000.0)) return
    if (!acceptSeekCommand(commandSource)) return
    val resolvedPositionMs = positionMs.coerceAtLeast(0L)
    playbackPositionGeneration += 1L
    logSeekRequested(resolvedPositionMs, commandSource)
    val preparedSeek = preparePlaybackSeek(resolvedPositionMs)
    applyPlaybackSeek(resolvedPositionMs, commandSource, preparedSeek)
    refreshPlaybackUrlAfterSeek(resolvedPositionMs, commandSource, preparedSeek)
}

private fun PlayerManager.logSeekRequested(positionMs: Long, commandSource: PlaybackCommandSource) {
    NPLogger.d(
        "NERI-PlayerManager",
        "seekTo requested: positionMs=$positionMs, source=$commandSource, currentSong=${_currentSongFlow.value?.name}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
}

private fun PlayerManager.acceptSeekCommand(commandSource: PlaybackCommandSource): Boolean {
    if (!initialized) return false
    return allowsLocalRoomSeek(commandSource)
}

private fun PlayerManager.allowsLocalRoomSeek(commandSource: PlaybackCommandSource): Boolean =
    !shouldBlockLocalRoomControl(commandSource)

private data class PreparedPlaybackSeek(
    val song: SongItem?,
    val durationMs: Long,
    val expeditedYouTubeRecovery: Boolean,
    val executionAction: SeekExecutionAction,
    val pendingAction: PendingSeekAction
)

private fun PlayerManager.preparePlaybackSeek(positionMs: Long): PreparedPlaybackSeek {
    val currentSong = _currentSongFlow.value
    val currentUrl = _currentMediaUrl.value
    val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
    val knownDurationMs = knownSeekDuration(currentSong)
    val shouldExpediteYouTubeSeekRecovery =
        YouTubeSeekRefreshPolicy.shouldUseExpeditedRecoveryAfterSeek(
            song = currentSong,
            currentUrl = currentUrl,
            previousPositionMs = currentPositionMs,
            targetPositionMs = positionMs,
            durationMs = knownDurationMs
        )
    val shouldRefreshYouTubeUrlBeforeSeek = shouldRefreshYouTubeUrlForSeek(
        currentSong,
        currentUrl,
        shouldExpediteYouTubeSeekRecovery
    )
    val pendingLoadActive = isPendingMediaLoadActive()
    // 正在装载新媒体时交给现有 pending-load 流程，避免替旧媒体启动一条并行刷新
    val seekExecutionAction = resolveSeekExecutionAction(
        pendingLoadActive = pendingLoadActive,
        urlRefreshRequested = shouldRefreshYouTubeUrlBeforeSeek
    )
    rememberYouTubeSeekPosition(
        positionMs,
        shouldRefreshYouTubeUrlBeforeSeek,
        shouldExpediteYouTubeSeekRecovery
    )
    val pendingSeekAction = resolvePendingSeekAction(
        pendingLoadActive = pendingLoadActive,
        requestedPositionMs = positionMs
    )
    applyPendingSeekPosition(pendingSeekAction)
    return PreparedPlaybackSeek(
        song = currentSong,
        durationMs = knownDurationMs,
        expeditedYouTubeRecovery = shouldExpediteYouTubeSeekRecovery,
        executionAction = seekExecutionAction,
        pendingAction = pendingSeekAction
    )
}

private fun shouldRefreshYouTubeUrlForSeek(
    song: SongItem?,
    currentUrl: String?,
    expeditedRecovery: Boolean
): Boolean {
    if (expeditedRecovery) return true
    return YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, currentUrl)
}

private fun PlayerManager.knownSeekDuration(song: SongItem?): Long =
    maxOf(player.duration, song?.durationMs ?: 0L).coerceAtLeast(0L)

private fun PlayerManager.rememberYouTubeSeekPosition(
    positionMs: Long,
    refreshUrl: Boolean,
    expeditedRecovery: Boolean
) {
    if (refreshUrl) {
        rememberPendingSeekPosition(positionMs)
        playbackProgressOwner.setExpeditedYouTubeSeekRecoveryPending(expeditedRecovery)
    } else {
        clearPendingSeekPosition()
    }
}

private fun PlayerManager.applyPendingSeekPosition(pendingSeekAction: PendingSeekAction) {
    pendingSeekAction.pendingSeekPositionMs?.let(::rememberPendingSeekPosition)
    pendingMediaLoadPositionMs = pendingSeekAction.exposedPositionMs
}

private fun PlayerManager.applyPlaybackSeek(
    positionMs: Long,
    commandSource: PlaybackCommandSource,
    preparedSeek: PreparedPlaybackSeek
) {
    seekPlayerIfReady(positionMs, preparedSeek.executionAction)
    syncSeekOutputs(positionMs)
    persistLongFormPlaybackProgress(
        song = preparedSeek.song,
        positionMs = positionMs,
        durationMs = preparedSeek.durationMs
    )
    scheduleStatePersist(
        positionMs = preparedSeek.pendingAction.persistPositionMs,
        shouldResumePlayback = shouldResumePlaybackSnapshot()
    )
    emitPlaybackCommand(
        type = "SEEK",
        source = commandSource,
        positionMs = positionMs,
        currentIndex = currentIndex
    )
}

private fun PlayerManager.seekPlayerIfReady(positionMs: Long, action: SeekExecutionAction) {
    if (action.seekPlayerNow) player.seekTo(positionMs)
}

private fun PlayerManager.syncSeekOutputs(positionMs: Long) {
    if (lyriconEnabled) {
        LyriconManager.setPosition(positionMs)
    }
    updateExternalBluetoothLyricLine(positionMs)
    playbackStatsOwner.onManualSeek(positionMs)
    _playbackPositionMs.value = positionMs
}

private fun PlayerManager.refreshPlaybackUrlAfterSeek(
    positionMs: Long,
    commandSource: PlaybackCommandSource,
    preparedSeek: PreparedPlaybackSeek
) {
    if (preparedSeek.executionAction.refreshUrlInBackground) {
        refreshCurrentSongUrl(
            resumePositionMs = positionMs,
            allowFallback = false,
            reason = seekRefreshReason(preparedSeek.expeditedYouTubeRecovery),
            bypassCooldown = true,
            resumePlaybackAfterRefresh = shouldResumePlaybackSnapshot(),
            resumedPlaybackCommandSource = commandSource,
            youtubeRecoveryStrategy = youtubePlaybackRecoveryStrategyForSeek()
        )
    }
}

private fun seekRefreshReason(expedited: Boolean): String =
    if (expedited) "youtube_seek_expedited_url_refresh" else "youtube_seek_url_refresh"
