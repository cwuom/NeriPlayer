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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.heartbeat.heartbeatCandidateExhaustedFlow
import moe.ouom.neriplayer.core.player.heartbeat.heartbeatRoomAutoOffFlow
import moe.ouom.neriplayer.core.player.heartbeat.heartbeatRoomRejectedFlow
import moe.ouom.neriplayer.ui.feedback.dismissCurrentNeriSnackbar
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

/** 提示的停留时长。比 Snackbar 默认的 Short（约 4 秒）略短，够看清又不挡操作。 */
private const val HeartbeatFailureSnackbarVisibleMs = 3_000L

/** 等待「自己这条提示真正显示出来」时的轮询间隔 */
private const val HeartbeatSnackbarPollMs = 50L

/**
 * 显示一条限时提示，**只在这条提示真正显示出来之后才开始计时**。
 *
 * 播放页的 Snackbar 是共享的：前一条还在显示时，[showNeriSnackbar] 会排队等待。
 * 若在排队时就起 3 秒计时器，到点会把**别人的**提示提前收掉，自己这条反而还没露脸。
 * 因此先等到 `currentSnackbarData` 变成自己这条，再算停留时间。
 */
private suspend fun SnackbarHostState.showTimedHeartbeatSnackbar(message: String) {
    val text = message.trim()
    if (text.isEmpty()) return
    coroutineScope {
        val showJob = launch { showNeriSnackbar(message = text) }
        // 排队期间一直等自己成为当前提示；若提示被去重跳过，showJob 会立刻结束
        while (!showJob.isCompleted && currentSnackbarData?.visuals?.message != text) {
            delay(HeartbeatSnackbarPollMs)
        }
        if (showJob.isCompleted) return@coroutineScope
        delay(HeartbeatFailureSnackbarVisibleMs)
        if (currentSnackbarData?.visuals?.message == text) {
            dismissCurrentNeriSnackbar()
        }
    }
}

/**
 * 心动模式的底部提示。
 *
 * 三类事件各一条，都只停留 3 秒（Snackbar 默认的 Short 约 4 秒，略长且会挡操作）：
 *
 * 1. **候选耗尽**——连续多次拿不到官方推荐，按原队列继续播放。这是功能真没帮上忙的时刻。
 * 2. **进一起听房间被自动关闭**——房间里的播放由房间控制，心动模式选歌不会生效，
 *    必须让用户知道开关被动了，否则会以为是自己误触。
 * 3. **房间里想开启被拒绝**——点了没反应最难受，明确告诉他为什么。
 *
 * 成功开启不提示。
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
            snackbarHostState.showTimedHeartbeatSnackbar(messageCandidatesExhausted)
        }
    }

    val heartbeatRoomAutoOff by PlayerManager.heartbeatRoomAutoOffFlow.collectAsStateWithLifecycle()
    val heartbeatRoomRejected by PlayerManager.heartbeatRoomRejectedFlow.collectAsStateWithLifecycle()
    val messageRoomAutoOff = stringResource(CoreCommonR.string.heartbeat_room_auto_disabled)
    val messageRoomRejected = stringResource(CoreCommonR.string.heartbeat_room_cannot_enable)

    // 计数器都只增不减，所以「数值变了」就等于「又发生了一次」。
    // 首组合同样跳过：计数器会随进程状态恢复而带上历史值，那不是本次发生的事。
    var isFirstEvent: Boolean by remember { mutableStateOf(true) }
    var lastRoomAutoOff: Int by remember { mutableStateOf(0) }
    var lastRoomRejected: Int by remember { mutableStateOf(0) }
    LaunchedEffect(heartbeatRoomAutoOff, heartbeatRoomRejected) {
        val notice = if (isFirstEvent) {
            null
        } else {
            pickHeartbeatNotice(
                roomRejected = heartbeatRoomRejected > lastRoomRejected,
                roomAutoOff = heartbeatRoomAutoOff > lastRoomAutoOff,
            )
        }
        isFirstEvent = false
        lastRoomAutoOff = heartbeatRoomAutoOff
        lastRoomRejected = heartbeatRoomRejected
        val message = when (notice ?: return@LaunchedEffect) {
            HeartbeatNotice.ROOM_REJECTED -> messageRoomRejected
            HeartbeatNotice.ROOM_AUTO_OFF -> messageRoomAutoOff
        }
        scope.launch {
            snackbarHostState.showTimedHeartbeatSnackbar(message)
        }
    }
}

internal enum class HeartbeatNotice { ROOM_REJECTED, ROOM_AUTO_OFF }

/** 两个事件同时发生时优先说「被拒绝」：那是用户刚做的操作，更该被解释。 */
internal fun pickHeartbeatNotice(roomRejected: Boolean, roomAutoOff: Boolean): HeartbeatNotice? = when {
    roomRejected -> HeartbeatNotice.ROOM_REJECTED
    roomAutoOff -> HeartbeatNotice.ROOM_AUTO_OFF
    else -> null
}
