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
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatOfficialAvailableFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatRoomAutoOffFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatRoomRejectedFlow
import moe.ouom.neriplayer.core.player.queue.heartbeat.heartbeatUserEnabledFlow
import moe.ouom.neriplayer.ui.feedback.dismissCurrentNeriSnackbar
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

/** 提示的停留时长。比 Snackbar 默认的 Short（约 4 秒）略短，够看清又不挡操作。 */
private const val HeartbeatFailureSnackbarVisibleMs = 3_000L

/**
 * 心动模式的底部提示。
 *
 * 三类事件各一条，都只停留 3 秒（Snackbar 默认的 Short 约 4 秒，略长且会挡操作）：
 *
 * 1. **候选耗尽**——连续多次选不出歌、播放器已回退原有模式。这是功能真没帮上忙的时刻。
 *    官方接口临时失败不算：那会静默降级到本地推荐，心动模式仍在工作。
 * 2. **进一起听房间被自动关闭**——房间里的播放由房间控制，心动模式选歌不会生效，
 *    必须让用户知道开关被动了，否则会以为是自己误触。
 * 3. **房间里想开启被拒绝**——点了没反应最难受，明确告诉他为什么。
 *
 * 另外还有一条**成功**提示，是全项目唯一的例外：未登录（或官方接口不可用）时
 * 用户主动开启心动模式，会额外说明「登录后可使用云端推荐」，见 [shouldShowHeartbeatLocalHint]。
 * 之所以破例，是因为「现在用的是本地推荐」这件事用户无法从界面其它地方得知，
 * 而它直接决定推荐质量；其余成功场景一律不提示，状态恢复也不算「用户主动开启」。
 *
 * 样式沿用项目原有的 Snackbar（`NeriOverlaySnackbarHost` 渲染在播放页底部），
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

    val heartbeatRoomAutoOff by PlayerManager.heartbeatRoomAutoOffFlow.collectAsStateWithLifecycle()
    val heartbeatRoomRejected by PlayerManager.heartbeatRoomRejectedFlow.collectAsStateWithLifecycle()
    val messageRoomAutoOff = stringResource(CoreCommonR.string.heartbeat_room_auto_disabled)
    val messageRoomRejected = stringResource(CoreCommonR.string.heartbeat_room_cannot_enable)

    // 两个计数器都只增不减，所以「数值变了」就等于「又发生了一次」。
    // 首组合同样跳过：计数器会随进程状态恢复而带上历史值，那不是本次发生的事。
    var isFirstRoomEvent: Boolean by remember { mutableStateOf(true) }
    var lastRoomAutoOff: Int by remember { mutableStateOf(0) }
    var lastRoomRejected: Int by remember { mutableStateOf(0) }
    LaunchedEffect(heartbeatRoomAutoOff, heartbeatRoomRejected) {
        if (isFirstRoomEvent) {
            isFirstRoomEvent = false
            lastRoomAutoOff = heartbeatRoomAutoOff
            lastRoomRejected = heartbeatRoomRejected
            return@LaunchedEffect
        }
        // 两个计数器可能同时变化，优先说「被拒绝」：那是用户刚做的操作，更该被解释。
        val message = when {
            heartbeatRoomRejected > lastRoomRejected -> messageRoomRejected
            heartbeatRoomAutoOff > lastRoomAutoOff -> messageRoomAutoOff
            else -> return@LaunchedEffect
        }
        lastRoomAutoOff = heartbeatRoomAutoOff
        lastRoomRejected = heartbeatRoomRejected
        scope.launch {
            launch { snackbarHostState.showNeriSnackbar(message = message) }
            delay(HeartbeatFailureSnackbarVisibleMs)
            snackbarHostState.dismissCurrentNeriSnackbar()
        }
    }

    // 全项目唯一的「成功也提示」：用户主动开启、且当前走的是本地推荐时，
    // 顺带告诉他登录后能换成云端推荐。其余成功场景一律不提示。
    val heartbeatUserEnabled by PlayerManager.heartbeatUserEnabledFlow.collectAsStateWithLifecycle()
    val heartbeatOfficialAvailable by PlayerManager.heartbeatOfficialAvailableFlow
        .collectAsStateWithLifecycle()
    val messageEnabledLocalHint = stringResource(CoreCommonR.string.heartbeat_mode_enabled_local_hint)

    var isFirstUserEnable: Boolean by remember { mutableStateOf(true) }
    var lastUserEnabled: Int by remember { mutableStateOf(0) }
    LaunchedEffect(heartbeatUserEnabled, heartbeatOfficialAvailable) {
        if (isFirstUserEnable) {
            isFirstUserEnable = false
            lastUserEnabled = heartbeatUserEnabled
            return@LaunchedEffect
        }
        // 官方可用性变化也会走到这里，但那时计数没涨，直接忽略。
        if (heartbeatUserEnabled <= lastUserEnabled) return@LaunchedEffect
        lastUserEnabled = heartbeatUserEnabled
        if (!shouldShowHeartbeatLocalHint(officialAvailable = heartbeatOfficialAvailable)) {
            return@LaunchedEffect
        }
        scope.launch {
            launch { snackbarHostState.showNeriSnackbar(message = messageEnabledLocalHint) }
            delay(HeartbeatFailureSnackbarVisibleMs)
            snackbarHostState.dismissCurrentNeriSnackbar()
        }
    }
}

/**
 * 开启心动模式后要不要提示「当前是本地推荐」。
 *
 * 只在官方接口不可用时提示：官方可用时用户得到的就是他期待的效果，不需要解释。
 * 这个函数是任务书「本地降级不得表述为官方推荐」这条合规要求的唯一判定点，
 * 因此单独存在、单独被测试覆盖。
 */
internal fun shouldShowHeartbeatLocalHint(officialAvailable: Boolean): Boolean = !officialAvailable

/**
 * 心动模式开关的文案判定（任务书 §四 步骤 2 文案表）。
 *
 * 注意：界面上实际使用的是 [shouldShowHeartbeatLocalHint] + `heartbeat_mode_enabled_local_hint`
 * 这条更完整的文案，本函数**当前不被界面调用**。保留它和
 * [moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingHeartbeatFeedbackTest] 的原因：
 * 它记录了「开启/关闭 × 官方可用/本地」这张完整文案表的映射关系，
 * 将来若要区分「关闭」提示可直接接回去，不必重新推导。
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
