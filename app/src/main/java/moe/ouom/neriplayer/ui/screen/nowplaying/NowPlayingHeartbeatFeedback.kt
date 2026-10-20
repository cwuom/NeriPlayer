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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingHeartbeatFeedback
 */

package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatCandidateExhaustedFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatModeFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatOfficialAvailableFlow
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

/**
 * 心动模式的 Snackbar 反馈。
 *
 * 为什么单独成文件而不是塞进 `NowPlayingScreen.kt`：播放页已有 1900+ 物理行，
 * 而 `OwnedMainSourceLineBudgetTest` 把该文件卡在 2000 行以内（见
 * `LocalManagementLineBudgetTest.MAX_EXCLUSIVE`）。反馈逻辑放在这里，
 * 播放页只留一行调用。
 *
 * 触发时机：`PlayerManager.cycleHeartbeatMode()` 会同步改写 [heartbeatModeFlow]，
 * 但 onClick 里读到的仍是切换前的旧值，所以这里用 [LaunchedEffect] 监听状态变化再弹，
 * 并跳过首次组合，避免一进播放页就弹一次。
 */
@Composable
fun NowPlayingHeartbeatFeedback(
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val heartbeatMode by PlayerManager.heartbeatModeFlow.collectAsStateWithLifecycle()
    val heartbeatEnabled = heartbeatMode == HeartbeatMode.ON
    val heartbeatOfficialAvailable by PlayerManager
        .heartbeatOfficialAvailableFlow
        .collectAsStateWithLifecycle()

    val messageEnabled = stringResource(CoreCommonR.string.heartbeat_mode_enabled)
    val messageEnabledLocal = stringResource(CoreCommonR.string.heartbeat_mode_enabled_local)
    val messageDisabled = stringResource(CoreCommonR.string.heartbeat_mode_disabled)

    // 首次组合不弹：只对「用户点击后真的发生的状态变化」给反馈。
    var hasCollectedInitialMode: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(heartbeatMode) {
        if (!hasCollectedInitialMode) {
            hasCollectedInitialMode = true
            return@LaunchedEffect
        }
        scope.launch {
            snackbarHostState.showNeriSnackbar(
                message = resolveHeartbeatSnackbarMessage(
                    heartbeatEnabled = heartbeatEnabled,
                    officialAvailable = heartbeatOfficialAvailable,
                    messageEnabled = messageEnabled,
                    messageEnabledLocal = messageEnabledLocal,
                    messageDisabled = messageDisabled,
                )
            )
        }
    }

    // 候选耗尽（false -> true 上升沿）时单独提示：与上面的模式切换反馈完全独立，
    // 用户没有点任何按钮也可能触发，所以只认状态上升、不认首次组合的初始值。
    // 拿到一次候选即自动复位为 false，所以同一个耗尽状态只会弹一次。
    val heartbeatCandidatesExhausted by PlayerManager
        .heartbeatCandidateExhaustedFlow
        .collectAsStateWithLifecycle()
    val messageCandidatesExhausted = stringResource(
        CoreCommonR.string.heartbeat_candidates_insufficient
    )
    var hasCollectedInitialExhausted: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(heartbeatCandidatesExhausted) {
        if (!heartbeatCandidatesExhausted) return@LaunchedEffect
        if (!hasCollectedInitialExhausted) {
            hasCollectedInitialExhausted = true
            return@LaunchedEffect
        }
        scope.launch {
            snackbarHostState.showNeriSnackbar(message = messageCandidatesExhausted)
        }
    }
}

/**
 * 心动模式开关的 Snackbar 文案判定（唯一集中点，便于单测）。
 *
 * 本地降级状态必须带「（本地推荐）」后缀：官方接口失败/未登录时用户需要知道推荐来源已变，
 * 但文案绝不提「官方推荐」「网易云心动算法」之类字样，只用中性表述。
 */
internal fun resolveHeartbeatSnackbarMessage(
    heartbeatEnabled: Boolean,
    officialAvailable: Boolean,
    messageEnabled: String,
    messageEnabledLocal: String,
    messageDisabled: String
): String = when {
    heartbeatEnabled && officialAvailable -> messageEnabled
    heartbeatEnabled -> messageEnabledLocal
    else -> messageDisabled
}
