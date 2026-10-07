package moe.ouom.neriplayer.core.player.timer

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
 * File: moe.ouom.neriplayer.core.player/SleepTimerManager
 * Created: 2026/1/6
 */

import moe.ouom.neriplayer.data.model.playback.SleepTimerMode
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import android.os.SystemClock
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.units.minute
import moe.ouom.neriplayer.common.units.second
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定时器模式
 */

/**
 * 定时器状态
 */

/**
 * 睡眠定时器管理器
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onTimerExpired: () -> Unit,
    private val onTimerStateChanged: (SleepTimerState) -> Unit = {},
    private val nowMsProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var timerJob: Job? = null

    private val _timerState = MutableStateFlow(SleepTimerState())
    val timerState: StateFlow<SleepTimerState> = _timerState

    /**
     * 启动倒计时定时器
     * @param minutes 倒计时分钟数
     * @param finishCurrentOnExpiry 倒计时结束后是否播放完当前歌曲
     */
    fun startCountdown(
        minutes: Int,
        finishCurrentOnExpiry: Boolean = false
    ) {
        cancel(notifyStateChanged = false)
        val totalMillis = minutes.coerceAtLeast(0).minute
        val timerMode = if (finishCurrentOnExpiry) {
            SleepTimerMode.COUNTDOWN_FINISH_CURRENT
        } else {
            SleepTimerMode.COUNTDOWN
        }
        updateTimerState(
            SleepTimerState(
                isActive = true,
                mode = timerMode,
                remainingMillis = totalMillis,
                totalMillis = totalMillis
            )
        )
        val deadlineMs = nowMsProvider() + totalMillis

        timerJob = scope.launch {
            var remaining = totalMillis
            while (isActive && remaining > 0L) {
                delay(min(1.second, remaining).milliseconds)
                remaining = (deadlineMs - nowMsProvider()).coerceAtLeast(0L)
                _timerState.value = _timerState.value.copy(remainingMillis = remaining)
            }
            if (!isActive) {
                return@launch
            }
            if (remaining <= 0) {
                if (timerMode == SleepTimerMode.COUNTDOWN_FINISH_CURRENT) {
                    updateTimerState(
                        SleepTimerState(
                            isActive = true,
                            mode = SleepTimerMode.FINISH_CURRENT
                        )
                    )
                } else {
                    onTimerExpired()
                    updateTimerState(SleepTimerState())
                }
            }
        }
    }

    /**
     * 启动"播放完当前歌曲后停止"模式
     */
    fun startFinishCurrent() {
        cancel(notifyStateChanged = false)
        updateTimerState(
            SleepTimerState(
                isActive = true,
                mode = SleepTimerMode.FINISH_CURRENT
            )
        )
    }

    /**
     * 启动"播放完播放列表后停止"模式
     */
    fun startFinishPlaylist() {
        cancel(notifyStateChanged = false)
        updateTimerState(
            SleepTimerState(
                isActive = true,
                mode = SleepTimerMode.FINISH_PLAYLIST
            )
        )
    }

    /**
     * 取消定时器
     */
    fun cancel(notifyStateChanged: Boolean = true) {
        timerJob?.cancel()
        timerJob = null
        updateTimerState(SleepTimerState(), notifyStateChanged)
    }

    /**
     * 检查是否应该在歌曲结束时停止
     * @param isLastInPlaylist 是否是播放列表中的最后一首
     * @return true 表示应该停止播放
     */
    fun shouldStopOnTrackEnd(isLastInPlaylist: Boolean): Boolean {
        val state = _timerState.value
        return when {
            !state.isActive -> false
            state.mode == SleepTimerMode.FINISH_CURRENT -> true
            state.mode == SleepTimerMode.FINISH_PLAYLIST && isLastInPlaylist -> true
            else -> false
        }
    }

    /**
     * 格式化剩余时间为可读字符串
     */
    fun formatRemainingTime(): String = formatSleepTimerRemaining(_timerState.value)

    // 通知专用: > 60s 时只显示分钟, 减少通知重建频率
    fun formatRemainingTimeForNotification(): String =
        formatSleepTimerNotificationRemaining(_timerState.value)

    private fun updateTimerState(
        state: SleepTimerState,
        notifyStateChanged: Boolean = true
    ) {
        _timerState.value = state
        if (notifyStateChanged) {
            onTimerStateChanged(state)
        }
    }
}

internal fun formatSleepTimerRemaining(state: SleepTimerState): String {
    if (!state.isActive || !state.mode.isCountdownMode()) {
        return ""
    }

    val totalSeconds = state.remainingMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal fun formatSleepTimerNotificationRemaining(state: SleepTimerState): String {
    if (!state.isActive || !state.mode.isCountdownMode()) {
        return ""
    }

    val totalSeconds = state.remainingMillis / 1000
    if (totalSeconds <= 60) {
        return formatSleepTimerRemaining(state)
    }
    val hours = totalSeconds / 3600
    val displayMinutes = ((totalSeconds % 3600) + 59) / 60
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%d:%02d:00", hours, displayMinutes.coerceAtMost(59))
        else -> String.format(Locale.ROOT, "%d:00", displayMinutes)
    }
}

/** 服务按通知上可见的剩余时间去重，计时器逐秒推进时不必逐秒重建通知快照和查询小组件 */
internal fun sleepTimerNotificationKey(state: SleepTimerState): Pair<SleepTimerState, String> =
    state.copy(remainingMillis = 0L) to formatSleepTimerNotificationRemaining(state)

private fun SleepTimerMode.isCountdownMode(): Boolean {
    return this == SleepTimerMode.COUNTDOWN ||
        this == SleepTimerMode.COUNTDOWN_FINISH_CURRENT
}
