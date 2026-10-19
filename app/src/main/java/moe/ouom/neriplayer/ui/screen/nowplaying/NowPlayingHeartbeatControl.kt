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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingHeartbeatControl
 */

package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.cycleHeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatCandidateExhaustedFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatModeFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatOfficialAvailableFlow
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

/**
 * 主播放控制区的心动模式开关按钮。
 *
 * 只决定「下一首播什么」，当前歌曲继续播放，因此与循环模式一样是普通 [HapticIconButton]，
 * 不用主播放按钮的实心样式。图标是心跳脉冲折线
 * （[CoreCommonR.drawable.ic_heartbeat_pulse_24]）而不是爱心：爱心在本项目已被
 * 「收藏当前歌曲」语义占用，放在播放栏会让用户误解。
 *
 * 图标 tint：开启用 [activeTint]（调用方传入 `nowPlayingActiveIconColor`，与随机/循环
 * 同一套视觉语言），关闭用 [LocalContentColor]。本地降级（官方接口本次不可用）只在
 * Snackbar 文案后缀上说明，不改变图标。
 *
 * Snackbar 的触发时机：`PlayerManager.cycleHeartbeatMode()` 会同步改写 [heartbeatModeFlow]，
 * 但 onClick 里读到的仍是切换前的旧值，所以这里用 [LaunchedEffect] 监听状态变化再弹，
 * 并跳过首次组合，避免一进播放页就弹一次。
 */
@Composable
fun NowPlayingHeartbeatControl(
    modifier: Modifier = Modifier,
    buttonSize: Dp,
    iconSize: Dp,
    activeTint: Color,
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

    HapticIconButton(
        onClick = { PlayerManager.cycleHeartbeatMode() },
        modifier = modifier.size(buttonSize)
    ) {
        Icon(
            painter = painterResource(CoreCommonR.drawable.ic_heartbeat_pulse_24),
            contentDescription = stringResource(CoreCommonR.string.heartbeat_mode_content_description),
            modifier = Modifier.size(iconSize),
            tint = if (heartbeatEnabled) activeTint else LocalContentColor.current
        )
    }
}

/**
 * 主播放控制区开启心动模式后的 secondary 按钮数量
 * （随机 / 上一首 / 下一首 / 循环 / 心动 = 5 个，主播放键是 primary）。
 */
internal const val MainPlaybackControlsSecondaryButtonCountWithHeartbeat = 5

/** 与 `NowPlayingScreen.kt` 的 `NowPlayingMainControlsMinimumSpacing`（4.dp）保持一致。 */
internal val HeartbeatMainControlsMinimumSpacing: Dp = 4.dp

/**
 * 与 `NowPlayingScreen.kt` 的 `NowPlayingMainControlsLayout` 完全同构，
 * 只是让 secondary 按钮数量可参数化，供开启心动模式后的主播放控制区使用。
 */
internal data class HeartbeatMainControlsLayout(
    val secondaryButtonSize: Dp,
    val primaryButtonSize: Dp,
    val spacing: Dp
)

/**
 * 含心动模式开关的主播放控制区布局解析（六按钮版）。
 *
 * 与 `NowPlayingScreen.kt` 的 `resolveNowPlayingMainControlsLayout`（五按钮版）逻辑同构：
 * 先按可参数化的 secondary 数量算出所需按钮宽度，宽度不足时整体等比缩放，
 * 再按剩余空间决定间距（不超过 [preferredSpacing]，也不低于最小间距）。
 *
 * 原五按钮 resolver 一个字符都没改：封面操作栏仍走 `resolvePlaybackActionToolbarLayout`
 * （内部用 `PlaybackActionToolbarItemCount` = 5），`NowPlayingScreenTest` 里不带 itemCount
 * 参数、按 `secondaryButtonSize * 4 + primaryButtonSize + spacing * 4` 写死断言的两个
 * 窄屏用例走的也仍是原函数，行为不变。
 */
internal fun resolveNowPlayingMainControlsLayoutWithHeartbeat(
    availableWidth: Dp,
    secondaryButtonSize: Dp,
    primaryButtonSize: Dp,
    preferredSpacing: Dp,
    secondaryButtonCount: Int = MainPlaybackControlsSecondaryButtonCountWithHeartbeat
): HeartbeatMainControlsLayout {
    val safeSecondaryCount = secondaryButtonCount.coerceAtLeast(1)
    val gapCount = safeSecondaryCount - 1
    val requestedButtonWidth = secondaryButtonSize * safeSecondaryCount + primaryButtonSize
    val minimumSpacing = minOf(
        HeartbeatMainControlsMinimumSpacing,
        availableWidth / gapCount
    )
    val availableButtonWidth = (availableWidth - minimumSpacing * gapCount)
        .coerceAtLeast(0.dp)
    val buttonScale = if (
        requestedButtonWidth.value > 0f && requestedButtonWidth > availableButtonWidth
    ) {
        (availableButtonWidth.value / requestedButtonWidth.value).coerceIn(0f, 1f)
    } else {
        1f
    }
    val resolvedSecondaryButtonSize = secondaryButtonSize * buttonScale
    val resolvedPrimaryButtonSize = primaryButtonSize * buttonScale
    val maximumSpacing = (
            (availableWidth - resolvedSecondaryButtonSize * safeSecondaryCount -
                    resolvedPrimaryButtonSize) / gapCount
            ).coerceAtLeast(0.dp)
    return HeartbeatMainControlsLayout(
        secondaryButtonSize = resolvedSecondaryButtonSize,
        primaryButtonSize = resolvedPrimaryButtonSize,
        spacing = minOf(preferredSpacing, maximumSpacing)
    )
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
