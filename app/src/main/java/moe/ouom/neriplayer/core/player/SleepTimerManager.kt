package moe.ouom.neriplayer.core.player

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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 定时器模式
 */
enum class SleepTimerMode {
    /** 倒计时模式 */
    COUNTDOWN,
    /** 播放完当前歌曲后停止 */
    FINISH_CURRENT,
    /** 播放完播放列表后停止 */
    FINISH_PLAYLIST
}

/**
 * 定时器状态
 */
data class SleepTimerState(
    val isActive: Boolean = false,
    val mode: SleepTimerMode = SleepTimerMode.COUNTDOWN,
    val remainingMillis: Long = 0,
    val totalMillis: Long = 0
)

/**
 * 睡眠定时器管理器
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onTimerExpired: () -> Unit
) {
    private var timerJob: Job? = null

    private val _timerState = MutableStateFlow(SleepTimerState())
    val timerState: StateFlow<SleepTimerState> = _timerState

    /** 预设时间选项（分钟） */
    val presetMinutes = listOf(15, 30, 45, 60, 90, 120)

    /**
     * 启动倒计时定时器
     * @param minutes 倒计时分钟数
     */
    fun startCountdown(minutes: Int) {
        cancel()
        val totalMillis = minutes * 60 * 1000L
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.COUNTDOWN,
            remainingMillis = totalMillis,
            totalMillis = totalMillis
        )

        timerJob = scope.launch {
            var remaining = totalMillis
            while (isActive && remaining > 0) {
                delay(1000)
                remaining -= 1000
                _timerState.value = _timerState.value.copy(remainingMillis = remaining)
            }
            if (remaining <= 0) {
                onTimerExpired()
                _timerState.value = SleepTimerState()
            }
        }
    }

    /**
     * 启动"播放完当前歌曲后停止"模式
     */
    fun startFinishCurrent() {
        cancel()
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.FINISH_CURRENT
        )
    }

    /**
     * 启动"播放完播放列表后停止"模式
     */
    fun startFinishPlaylist() {
        cancel()
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.FINISH_PLAYLIST
        )
    }

    /**
     * 取消定时器
     */
    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _timerState.value = SleepTimerState()
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
    fun formatRemainingTime(): String {
        val state = _timerState.value
        if (!state.isActive || state.mode != SleepTimerMode.COUNTDOWN) {
            return ""
        }

        val totalSeconds = state.remainingMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
}
