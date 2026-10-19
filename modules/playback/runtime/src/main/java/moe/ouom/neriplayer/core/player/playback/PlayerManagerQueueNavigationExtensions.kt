@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.currentPositionMsOrZero
import moe.ouom.neriplayer.core.player.durationMsOrZero
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.consumeHeartbeatReadyNextSong
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatModeState
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatReadyNextSong
import moe.ouom.neriplayer.core.player.queue.heartbeat.insertHeartbeatSongAndPlay
import moe.ouom.neriplayer.core.player.queue.heartbeat.requestHeartbeatSelectionAsync
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.queue.ListenTogetherTrackFinishPlan
import moe.ouom.neriplayer.data.model.playback.queue.PlaybackFailureAdvanceAction
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.data.model.playback.queue.QueueNavigationStep
import moe.ouom.neriplayer.data.model.playback.queue.QueueTrackCompletion
import moe.ouom.neriplayer.core.player.queue.heartbeat.tryAdvanceWithHeartbeat
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueNavigationOwner
import moe.ouom.neriplayer.core.player.queue.policy.failure.resolvePlaybackFailureAdvanceAction

private fun PlayerManager.handleListenTogetherTrackFinishedIfNeeded(): Boolean {
    if (!canHandleListenTogetherTrackFinish()) return false
    sendListenTogetherTrackFinish()
    return true
}

private fun PlayerManager.canHandleListenTogetherTrackFinish(): Boolean {
    if (!isListenTogetherActive()) return false
    return PlayerQueueNavigationOwner.hasSelectedTrack(currentPlaylist.size, currentIndex)
}

private fun PlayerManager.sendListenTogetherTrackFinish() {
    val finishPositionMs = PlayerQueueNavigationOwner.trackFinishPosition(
        song = _currentSongFlow.value,
        playerDurationMs = player.durationMsOrZero,
        playerPositionMs = player.currentPositionMsOrZero,
    )
    val finishPlan = resolvedListenTogetherFinishPlan()
    NPLogger.d(
        "NERI-PlayerManager",
        "listen together track finished: currentIndex=$currentIndex, nextIndex=${finishPlan.nextIndex}, shouldAdvance=${finishPlan.shouldAdvance}, finishPositionMs=$finishPositionMs"
    )
    pause(commandSource = PlaybackCommandSource.REMOTE_SYNC)
    _playbackPositionMs.value = finishPositionMs
    emitPlaybackCommand(
        type = "TRACK_FINISHED",
        source = PlaybackCommandSource.LOCAL,
        currentIndex = finishPlan.nextIndex,
        positionMs = finishPositionMs,
        shouldPlay = finishPlan.shouldAdvance
    )
}

private fun PlayerManager.resolvedListenTogetherFinishPlan(): ListenTogetherTrackFinishPlan {
    val finishPlan = PlayerQueueNavigationOwner.listenTogetherFinishPlan(
        queueSize = currentPlaylist.size,
        currentIndex = currentIndex,
        repeatMode = repeatModeSetting,
    )
    return if (reshuffledListenTogetherFinish(finishPlan)) {
        finishPlan.copy(nextIndex = currentIndex)
    } else {
        finishPlan
    }
}

private fun PlayerManager.reshuffledListenTogetherFinish(finishPlan: ListenTogetherTrackFinishPlan): Boolean {
    if (!finishPlan.shouldAdvance) return false
    return reshuffleListenTogetherControllerFinish()
}

private fun PlayerManager.reshuffleListenTogetherControllerFinish(): Boolean {
    if (!isCurrentUserControllerInListenTogether()) return false
    return reshuffleCurrentQueueForRepeatAllCycle()
}

internal fun PlayerManager.handleTrackEnded() {
    clearPendingSeekPosition()
    val finishedSong = _currentSongFlow.value
    val finishedDurationMs = PlayerQueueNavigationOwner.finishedDuration(
        song = finishedSong,
        reportedDurationMs = playbackDurationFlow.value,
    )
    persistLongFormPlaybackProgress(
        song = finishedSong,
        positionMs = finishedDurationMs,
        durationMs = finishedDurationMs
    )
    _playbackPositionMs.value = 0L
    val isLastInPlaylist = currentIndex >= currentPlaylist.lastIndex
    NPLogger.d(
        "NERI-PlayerManager",
        "handleTrackEnded: currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, repeatMode=$repeatModeSetting, shuffle=${player.shuffleModeEnabled}, isLastInPlaylist=$isLastInPlaylist"
    )
    finishTrackEnd(isLastInPlaylist)
}

private fun PlayerManager.finishTrackEnd(isLastInPlaylist: Boolean) {
    if (handleListenTogetherTrackFinishedIfNeeded()) return
    finishLocalTrackEnd(isLastInPlaylist)
}

private fun PlayerManager.finishLocalTrackEnd(isLastInPlaylist: Boolean) {
    if (sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
        pause()
        sleepTimerManager.cancel()
        return
    }
    dispatchTrackCompletion()
}

private fun PlayerManager.dispatchTrackCompletion() {
    val action = PlayerQueueNavigationOwner.trackCompletion(
        queueSize = currentPlaylist.size,
        currentIndex = currentIndex,
        repeatMode = repeatModeSetting,
    )
    if (action == QueueTrackCompletion.STOP) {
        stopPlaybackPreservingQueue()
        return
    }
    markAutoTrackAdvance()
    playAfterTrackCompletion(action)
}

private fun PlayerManager.playAfterTrackCompletion(action: QueueTrackCompletion) {
    if (action == QueueTrackCompletion.REPLAY_CURRENT) {
        playAtIndex(
            index = currentIndex,
            commandSource = activePlaybackCommandSource,
            allowRememberedLongFormPosition = false
        )
        return
    }
    // 心动模式优先：开启且能选出下一首时接管本次推进，否则完全沿用原有 nextStep 行为
    if (heartbeatAdvanceOrFallback(
            reason = "trackCompletion:$action",
            fallback = {
                nextImpl(
                    force = PlayerQueueNavigationOwner.forceWrapAfterCompletion(action),
                    commandSource = activePlaybackCommandSource,
                    bypassLoudVolumeWarning = true
                )
            }
        )
    ) {
        return
    }
}

/**
 * 心动模式推进包装：命中心动候选时不再执行 [fallback]。
 *
 * 之所以不把 [tryAdvanceWithHeartbeat] 直接写成 `if (...)` 的布尔判断，
 * 是因为官方接口需要挂起，而这里处于同步调用链上；由调用方提供
 * [PlayerManager.launchHeartbeatIo] 风格的桥接会破坏异常传播，
 * 因此改为在同步侧只做「开关 + 候选已就绪」判断（见心跳仓库的就绪快照）。
 */
private fun PlayerManager.heartbeatAdvanceOrFallback(
    reason: String,
    fallback: () -> Unit,
): Boolean {
    if (heartbeatModeState != HeartbeatMode.ON) {
        fallback()
        return false
    }
    val ready = heartbeatReadyNextSong
    if (ready == null) {
        // 没有就绪的预取结果：沿用原有推进，同时异步补一次选择，供下一次使用
        requestHeartbeatSelectionAsync(reason)
        fallback()
        return false
    }
    consumeHeartbeatReadyNextSong()
    insertHeartbeatSongAndPlay(ready, activePlaybackCommandSource)
    return true
}
internal fun PlayerManager.advanceAfterPlaybackFailure(
    source: String,
    commandSource: PlaybackCommandSource = activePlaybackCommandSource
) {
    clearPendingSeekPosition()
    persistCurrentLongFormPlaybackProgress()
    _playbackPositionMs.value = 0L

    val action = resolvePlaybackFailureAdvanceAction(
        currentIndex = currentIndex,
        playlistSize = currentPlaylist.size,
        repeatMode = repeatModeSetting
    )
    NPLogger.d(
        "NERI-PlayerManager",
        "advanceAfterPlaybackFailure: source=$source, action=$action, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, repeatMode=$repeatModeSetting, shuffle=${player.shuffleModeEnabled}"
    )

    dispatchFailureAdvance(action, commandSource)
}

private fun PlayerManager.dispatchFailureAdvance(
    action: PlaybackFailureAdvanceAction,
    commandSource: PlaybackCommandSource,
) {
    if (action == PlaybackFailureAdvanceAction.STOP) {
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        return
    }
    // 播放失败时先用心动候选顶上（任务书 §3.5 步骤 5），拿不到才按原失败策略推进
    if (heartbeatAdvanceOrFallback(
            reason = "playbackFailure",
            fallback = {
                markAutoTrackAdvance()
                nextImpl(
                    force = PlayerQueueNavigationOwner.forceWrapAfterFailure(action),
                    commandSource = commandSource,
                    bypassLoudVolumeWarning = true,
                )
            },
        )
    ) {
        return
    }
}

internal fun PlayerManager.publishShuffledCurrentSong(shuffledQueue: PlayerQueueSnapshot?) {
    if (shuffledQueue == null) return
    bumpCurrentQueueDisplayRevision()
    setCurrentSongForPlayback(shuffledQueue.playlist.getOrNull(shuffledQueue.currentIndex))
}

private fun PlayerManager.reshuffleCurrentQueueForRepeatAllCycle(): Boolean {
    if (!mayReshuffleCurrentQueueForRepeatAllCycle()) return false
    return performRepeatAllShuffle()
}

private fun PlayerManager.performRepeatAllShuffle(): Boolean {
    val shuffledQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        PlayerQueueNavigationOwner.repeatAllShuffle(
            queue = snapshot,
        )
    } ?: return false
    NPLogger.d(
        "NERI-PlayerManager",
        "reshuffleCurrentQueueForRepeatAllCycle: queueSize=${shuffledQueue.playlist.size}, currentIndex=${shuffledQueue.currentIndex}"
    )
    return true
}

private fun PlayerManager.mayReshuffleCurrentQueueForRepeatAllCycle(): Boolean =
    PlayerQueueNavigationOwner.mayRepeatAllShuffle(
        shuffleEnabled = player.shuffleModeEnabled,
        repeatMode = repeatModeSetting,
        listenerCannotControlQueue = listenerCannotControlQueue(),
    )

private fun PlayerManager.listenerCannotControlQueue(): Boolean {
    if (!isListenTogetherActive()) return false
    return PlayerQueueNavigationOwner.listenerCannotControlQueue(isCurrentUserControllerInListenTogether())
}

internal fun PlayerManager.nextImpl(
    force: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false,
    allowRememberedLongFormPosition: Boolean = false
) {
    ensureInitialized()
    if (!initialized) return
    // 心动模式优先：用户主动跳过时也走心跳候选，避免「跳过一次就掉出心动歌流」（任务书 §3.5 步骤 6）
    if (heartbeatAdvanceOrFallback(
            reason = "userSkip",
            fallback = {
                requestNextTrack(force, commandSource, bypassLoudVolumeWarning, allowRememberedLongFormPosition)
            },
        )
    ) {
        return
    }
}

private fun PlayerManager.requestNextTrack(
    force: Boolean,
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
    allowRememberedLongFormPosition: Boolean,
) {
    if (shouldBlockLocalRoomControl(commandSource)) return
    val step = PlayerQueueNavigationOwner.nextStep(currentPlaylist.size, currentIndex, repeatModeSetting, force)
    confirmNextTrack(step, force, commandSource, bypassLoudVolumeWarning, allowRememberedLongFormPosition)
}

private fun PlayerManager.confirmNextTrack(
    step: QueueNavigationStep?,
    force: Boolean,
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
    allowRememberedLongFormPosition: Boolean,
) {
    if (step == null) {
        NPLogger.d("NERI-Player", "Already at the end of the playlist.")
        return
    }
    confirmNextTrackVolume(step, force, commandSource, bypassLoudVolumeWarning, allowRememberedLongFormPosition)
}

private fun PlayerManager.confirmNextTrackVolume(
    step: QueueNavigationStep,
    force: Boolean,
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
    allowRememberedLongFormPosition: Boolean,
) {
    logQueueNavigation("next", commandSource)
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = {
                nextImpl(force, commandSource, true, allowRememberedLongFormPosition)
            }
        )
    ) return
    applyNextTrack(step, force, commandSource, allowRememberedLongFormPosition)
}

private fun PlayerManager.applyNextTrack(
    step: QueueNavigationStep,
    force: Boolean,
    commandSource: PlaybackCommandSource,
    allowRememberedLongFormPosition: Boolean,
) {
    val useTransitionFade = queueNavigationTransitionFade()
    currentIndex = resolvedNextIndex(step)
    playAtIndex(
        currentIndex,
        useTrackTransitionFade = useTransitionFade,
        commandSource = commandSource,
        allowRememberedLongFormPosition = allowRememberedLongFormPosition
    )
    emitPlaybackCommand(
        type = "NEXT",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value,
        force = force
    )
}

private fun PlayerManager.resolvedNextIndex(step: QueueNavigationStep): Int =
    if (step.reshuffleAtWrap) reshuffledWrapIndex() else step.index

private fun PlayerManager.reshuffledWrapIndex(): Int =
    if (reshuffleCurrentQueueForRepeatAllCycle()) currentIndex else 0

internal fun PlayerManager.previousImpl(
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false
) {
    ensureInitialized()
    if (!initialized) return
    requestPreviousTrack(commandSource, bypassLoudVolumeWarning)
}

private fun PlayerManager.requestPreviousTrack(
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
) {
    if (shouldBlockLocalRoomControl(commandSource)) return
    val targetIndex = PlayerQueueNavigationOwner.previousIndex(currentPlaylist.size, currentIndex, repeatModeSetting)
    confirmPreviousTrack(targetIndex, commandSource, bypassLoudVolumeWarning)
}

private fun PlayerManager.confirmPreviousTrack(
    targetIndex: Int?,
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
) {
    if (targetIndex == null) {
        NPLogger.d("NERI-Player", "Already at the start of the playlist.")
        return
    }
    confirmPreviousTrackVolume(targetIndex, commandSource, bypassLoudVolumeWarning)
}

private fun PlayerManager.confirmPreviousTrackVolume(
    targetIndex: Int,
    commandSource: PlaybackCommandSource,
    bypassLoudVolumeWarning: Boolean,
) {
    logQueueNavigation("previous", commandSource)
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = { previousImpl(commandSource, true) }
        )
    ) return
    applyPreviousTrack(targetIndex, commandSource)
}

private fun PlayerManager.applyPreviousTrack(targetIndex: Int, commandSource: PlaybackCommandSource) {
    val useTransitionFade = queueNavigationTransitionFade()
    currentIndex = targetIndex
    playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PREVIOUS",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value
    )
}

private fun PlayerManager.queueNavigationTransitionFade(): Boolean =
    PlayerQueueNavigationOwner.shouldUseTransitionFade(
        fadeEnabled = playbackCrossfadeNextEnabled,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
    )

private fun PlayerManager.logQueueNavigation(action: String, commandSource: PlaybackCommandSource) {
    NPLogger.d(
        "NERI-PlayerManager",
        "$action requested: source=$commandSource, isShuffle=${player.shuffleModeEnabled}, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, stack=[${debugStackHint()}]"
    )
}

internal fun PlayerManager.cycleRepeatModeImpl(
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    cycleRepeatModeIfAllowed(commandSource)
}

private fun PlayerManager.cycleRepeatModeIfAllowed(commandSource: PlaybackCommandSource) {
    if (shouldBlockLocalRoomControl(commandSource)) return
    val previousMode = repeatModeSetting
    val newMode = PlayerQueueNavigationOwner.nextRepeatMode(previousMode)
    repeatModeSetting = newMode
    syncExoRepeatMode()
    _repeatModeFlow.value = newMode
    NPLogger.d(
        "NERI-PlayerManager",
        "cycleRepeatMode: previousMode=$previousMode, newMode=$newMode, exoRepeatMode=${player.repeatMode}"
    )
    scheduleStatePersist()
    emitPlaybackCommand(
        type = "PLAYBACK_MODE",
        source = commandSource,
        repeatMode = newMode,
        shuffleEnabled = player.shuffleModeEnabled
    )
}

internal fun PlayerManager.setShuffleImpl(
    enabled: Boolean,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    setShuffleIfAllowed(enabled, commandSource)
}

private fun PlayerManager.setShuffleIfAllowed(enabled: Boolean, commandSource: PlaybackCommandSource) {
    if (shouldBlockLocalRoomControl(commandSource)) return
    applyShuffleRequest(enabled, commandSource)
}

private fun PlayerManager.applyShuffleRequest(enabled: Boolean, commandSource: PlaybackCommandSource) {
    if (player.shuffleModeEnabled == enabled) {
        handleUnchangedShuffleMode(enabled)
        return
    }
    changeShuffleMode(enabled, commandSource)
}

private fun PlayerManager.handleUnchangedShuffleMode(enabled: Boolean) {
    if (!enabled) clearStaleShuffleRestoreSnapshot()
}

private fun PlayerManager.clearStaleShuffleRestoreSnapshot() {
    val hadRestoreSnapshot = queueStore.clearShuffleRestore()
    if (hadRestoreSnapshot) {
        statePersistenceWriter.invalidate()
        scheduleStatePersist()
    }
}

private fun PlayerManager.changeShuffleMode(enabled: Boolean, commandSource: PlaybackCommandSource) {
    NPLogger.d(
        "NERI-PlayerManager",
        "setShuffle: enabled=$enabled, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}"
    )
    updateQueueShuffleMode(enabled, commandSource)
    player.shuffleModeEnabled = enabled
    scheduleStatePersist()
    emitPlaybackCommand(
        type = "PLAYBACK_MODE",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        repeatMode = repeatModeSetting,
        shuffleEnabled = enabled
    )
}

private fun PlayerManager.updateQueueShuffleMode(enabled: Boolean, commandSource: PlaybackCommandSource) {
    if (commandSource == PlaybackCommandSource.REMOTE_SYNC) {
        queueStore.setShuffleMode(enabled, clearRestore = true)
        return
    }
    publishShuffledCurrentSong(queueStore.setLocalShuffle(enabled, _currentSongFlow.value))
}

internal fun PlayerManager.applyListenTogetherPlaybackModeImpl(
    repeatMode: Int?,
    shuffleEnabled: Boolean?
) {
    val update = PlayerQueueNavigationOwner.remoteModeUpdate(
        currentRepeatMode = repeatModeSetting,
        currentShuffleEnabled = shuffleModeFlow.value,
        requestedRepeatMode = repeatMode,
        requestedShuffleEnabled = shuffleEnabled,
    ) ?: return
    applyRemoteRepeatMode(update.repeatMode)
    applyRemoteShuffleMode(update.shuffleEnabled)
    scheduleStatePersist()
}

private fun PlayerManager.applyRemoteRepeatMode(repeatMode: Int?) {
    if (repeatMode == null) return
    repeatModeSetting = repeatMode
    syncRemoteRepeatModeIfInitialized()
    _repeatModeFlow.value = repeatModeSetting
}

private fun PlayerManager.syncRemoteRepeatModeIfInitialized() {
    if (isPlayerInitialized()) syncExoRepeatMode()
}

private fun PlayerManager.applyRemoteShuffleMode(enabled: Boolean?) {
    if (enabled == null) return
    queueStore.setShuffleMode(enabled)
    syncRemoteShuffleModeIfInitialized(enabled)
}

private fun PlayerManager.syncRemoteShuffleModeIfInitialized(enabled: Boolean) {
    if (isPlayerInitialized()) player.shuffleModeEnabled = enabled
}
