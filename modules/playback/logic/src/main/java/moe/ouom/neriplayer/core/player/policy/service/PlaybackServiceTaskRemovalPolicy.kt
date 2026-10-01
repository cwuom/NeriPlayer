package moe.ouom.neriplayer.core.player.policy.service

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player.service.lifecycle/PlaybackServiceTaskRemovalPolicy
 * Updated: 2026/3/23
 */

fun shouldStopPlaybackOnTaskRemoved(
    hasPlaybackSurfaceContent: Boolean,
    transportActive: Boolean,
): Boolean {
    return hasPlaybackSurfaceContent && transportActive
}

fun resolveTaskRemovedTransportActive(
    playerTransportActive: Boolean,
    listenTogetherRemotePlaying: Boolean,
): Boolean {
    return playerTransportActive || listenTogetherRemotePlaying
}

data class TaskRemovedPlaybackAction(
    val stopPlaybackImmediately: Boolean,
    val persistPlaybackState: Boolean,
    val stopServiceAfterPersist: Boolean,
    val updateNotificationAfterPersist: Boolean,
)

data class TaskRemovedPlaybackCallbacks(
    val stopPlaybackImmediately: () -> Unit,
    val persistPlaybackState: suspend (String) -> Boolean,
    val stopForegroundIfStarted: (String) -> Unit,
    val stopSelf: () -> Unit,
    val updateNotification: () -> Unit,
    val onPlaybackStopFailure: (Throwable) -> Unit,
    val onNotificationUpdateFailure: (Throwable) -> Unit,
)

fun resolveTaskRemovedPlaybackAction(
    hasPlaybackSurfaceContent: Boolean,
    playerTransportActive: Boolean,
    listenTogetherRemotePlaying: Boolean,
    hasItems: Boolean,
): TaskRemovedPlaybackAction {
    val transportActive = resolveTaskRemovedTransportActive(
        playerTransportActive = playerTransportActive,
        listenTogetherRemotePlaying = listenTogetherRemotePlaying,
    )
    val stopPlaybackImmediately = shouldStopPlaybackOnTaskRemoved(
        hasPlaybackSurfaceContent = hasPlaybackSurfaceContent,
        transportActive = transportActive,
    )
    return TaskRemovedPlaybackAction(
        stopPlaybackImmediately = stopPlaybackImmediately,
        persistPlaybackState = stopPlaybackImmediately || hasItems,
        stopServiceAfterPersist = stopPlaybackImmediately,
        updateNotificationAfterPersist = hasItems && !stopPlaybackImmediately,
    )
}

suspend fun executeTaskRemovedPlaybackAction(
    action: TaskRemovedPlaybackAction,
    callbacks: TaskRemovedPlaybackCallbacks,
) {
    if (action.stopPlaybackImmediately) {
        runCatching { callbacks.stopPlaybackImmediately() }
            .onFailure(callbacks.onPlaybackStopFailure)
    }
    val playbackStatePersisted = persistTaskRemovedPlaybackState(action, callbacks)
    if (action.updateNotificationAfterPersist) {
        runCatching { callbacks.updateNotification() }
            .onFailure(callbacks.onNotificationUpdateFailure)
    }
    if (action.stopServiceAfterPersist) {
        if (playbackStatePersisted) {
            callbacks.stopForegroundIfStarted("task_removed")
            callbacks.stopSelf()
        } else {
            runCatching { callbacks.updateNotification() }
                .onFailure(callbacks.onNotificationUpdateFailure)
        }
    }
}

private suspend fun persistTaskRemovedPlaybackState(
    action: TaskRemovedPlaybackAction,
    callbacks: TaskRemovedPlaybackCallbacks
): Boolean {
    if (!action.persistPlaybackState) return true
    val reason = if (action.stopPlaybackImmediately) "task_removed" else "inactive_task_removed"
    return callbacks.persistPlaybackState(reason)
}
