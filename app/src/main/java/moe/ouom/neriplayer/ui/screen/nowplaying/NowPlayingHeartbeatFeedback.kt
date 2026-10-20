/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatCandidateExhaustedFlow
import moe.ouom.neriplayer.ui.feedback.dismissCurrentNeriSnackbar
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

/** 失败提示的停留时长。用户要求「一秒钟就行」，比 Snackbar 默认的 Short 短得多。 */
private const val HeartbeatFailureSnackbarVisibleMs = 1_000L

/**
 * 心动模式的失败提示。
 *
 * 只提示**失败**，不提示成功：开启 / 关闭心动模式是高频轻操作，
 * 每次都给一条底部提示属于噪音（用户明确要求「只有失败的时候才通知」）。
 *
 * 什么算失败：候选池连续 [moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateExhaustionTracker.MAX_CONSECUTIVE_NO_CANDIDATE]
 * 次选不出歌、播放器已回退到原有模式。这是功能真的没帮上忙的时刻，值得告诉用户。
 * 官方接口临时失败**不算**：那会静默降级到本地推荐，心动模式仍在工作，
 * 任务书 §四 步骤 2 也要求「官方临时失败不提示」。
 *
 * 样式沿用项目原有的 Snackbar（`NeriOverlaySnackbarHost` 渲染在播放页底部），
 * 但停留时长压到 1 秒：`SnackbarDuration.Short` 实际约 4 秒，对这条一句话提示太长。
 * 做法是照常 `showNeriSnackbar`，到点再主动 `dismiss`，不引入新的提示组件。
 *
 * 为什么单独成文件而不是塞进 `NowPlayingScreen.kt`：播放页已有 1900+ 物理行，
 * 而 `OwnedMainSourceLineBudgetTest` 把该文件卡在 2000 行以内（见
 * `LocalManagementLineBudgetTest.MAX_EXCLUSIVE`）。反馈逻辑放在这里，
 * 播放页只留一行调用。
 */
@Composable
fun NowPlayingHeartbeatFeedback(
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val heartbeatCandidatesExhausted by PlayerManager
        .heartbeatCandidateExhaustedFlow
        .collectAsStateWithLifecycle()
    val messageCandidatesExhausted = stringResource(
        CoreCommonR.string.heartbeat_candidates_insufficient
    )

    // 首次组合一律跳过：只对「本次会话内新发生的」耗尽上升沿给反馈，
    // 避免恢复状态时把上一次会话遗留的耗尽值弹出来。
    //
    // 注意这里的顺序：先判首组合再判状态值。写成「先判状态值再判首组合」会让
    // 初始值 false 时永远走不到首组合分支，导致**第一次**真实耗尽被吞掉、
    // 要第二次才提示（旧实现的 bug）。
    var isFirstComposition: Boolean by remember { mutableStateOf(true) }
    LaunchedEffect(heartbeatCandidatesExhausted) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        if (!heartbeatCandidatesExhausted) return@LaunchedEffect
        scope.launch {
            // 提示本身是挂起调用（会一直等到 Snackbar 消失），所以放到子协程里，
            // 让下面的计时器能按时把它收掉。
            launch { snackbarHostState.showNeriSnackbar(message = messageCandidatesExhausted) }
            delay(HeartbeatFailureSnackbarVisibleMs)
            snackbarHostState.dismissCurrentNeriSnackbar()
        }
    }
}

/**
 * 心动模式开关的文案判定（任务书 §四 步骤 2 文案表）。
 *
 * **当前 UI 不展示成功提示**（用户要求只提示失败），此函数因此不在界面上被调用。
 * 保留它和 [moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingHeartbeatFeedbackTest]
 * 的原因：任务书要求「本地降级不得表述为官方推荐」，这条合规映射需要有一个
 * 可测试的单一判定点；将来若恢复成功提示，直接接回去即可，不必重新推导文案。
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
