package moe.ouom.neriplayer.ui.component.sheet

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
 * File: moe.ouom.neriplayer.ui.component.sheet/HalfExpandableSheetPanel
 * Created: 2025/10/17
 */

import android.content.res.Configuration
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 半屏档位再下拉这么多才进入「关闭」档：48dp 太容易误关，按真机反馈定为 96dp。 */
internal val HalfExpandableDismissDistance: Dp = 96.dp

/** 半屏档位上限：避免大屏上半屏过高。 */
private val HalfExpandableHalfHeightMax: Dp = 620.dp

/** 半屏档位占可用高度的比例。 */
private const val HalfExpandableHalfHeightFraction = 0.72f

/** 平板上面板与状态栏之间留出的空隙。 */
private val HalfExpandableTabletVerticalMargin: Dp = 24.dp

private const val HalfExpandableSnapDurationMs = 800

/**
 * 半屏面板的档位控制：由 [HalfExpandableSheetPanel] 提供给标题栏内容使用。
 */
internal class HalfExpandableSheetControls(
    val isFullScreen: Boolean,
    val landscape: Boolean,
    val toggleFullScreen: () -> Unit,
    val collapseToHalf: () -> Unit
)

/**
 * 可上下拖拽、在半屏与全屏之间吸附的面板容器。
 *
 * 评论面板与歌曲百科面板共用同一套半屏实现：竖屏默认 72% / 上限 620dp，横屏直接给满高度；
 * 标题栏（含勾柄）跟手改变高度，松手后吸附到全屏 / 半屏 / 关闭三档之一。
 *
 * @param onDismiss 勾柄点击与下拉超过 [HalfExpandableDismissDistance] 时的关闭回调。
 * @param header 标题栏内容；由本组件统一挂载拖拽手势与勾柄。
 * @param content 面板内容，位于标题栏下方，可用 `Modifier.weight(1f)` 占据剩余高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HalfExpandableSheetPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    panelTestTag: String? = null,
    headerTestTag: String? = null,
    header: @Composable HalfExpandableSheetControls.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = configuration.smallestScreenWidthDp >= 600
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val safeDrawing = WindowInsets.safeDrawing
    // 平板面板与状态栏之间留一点空间，短手机横屏仍优先保留内容高度
    val availableHeight = if (tablet) {
        (windowHeight - with(density) {
            (safeDrawing.getTop(this) + safeDrawing.getBottom(this)).toDp()
        } - HalfExpandableTabletVerticalMargin).coerceAtLeast(0.dp)
    } else {
        windowHeight
    }
    // 横屏直接使用可用高度，避免标题栏挤掉内容
    val halfHeightPx = with(density) {
        (if (landscape) {
            availableHeight
        } else {
            (availableHeight * HalfExpandableHalfHeightFraction)
                .coerceAtMost(HalfExpandableHalfHeightMax)
        }).toPx()
    }
    val fullHeightPx = with(density) { availableHeight.toPx() }
    val dismissDistancePx = with(density) { HalfExpandableDismissDistance.toPx() }
    // 不能用 Animatable：awaitEachGesture 的 block 是受限挂起作用域，里面调不了 snapTo / animateTo。
    // 所以跟手直接写状态值，吸附用 animate() 在普通协程里逐帧回写同一个状态。
    var panelHeightPx by remember { mutableFloatStateOf(halfHeightPx) }
    var panelAnimJob by remember { mutableStateOf<Job?>(null) }
    // 记录「吸附目标档位」而不是只看瞬间像素高度：窗口尺寸变化（旋转 / 分屏）后，
    // 旧的全屏像素高度可能低于新中线，用 isFullScreen 判断会把全屏错误降成半屏。
    var targetFull by remember { mutableStateOf(false) }
    // 档位判定必须用 derivedStateOf：面板高度每帧都在变，直接读普通变量在组合期之外拿不到新值
    val isFullScreen by remember(halfHeightPx, fullHeightPx) {
        derivedStateOf { panelHeightPx >= (halfHeightPx + fullHeightPx) / 2f }
    }
    val scope = rememberCoroutineScope()
    // 档位吸附复用项目既有规格（tween + LinearOutSlowInEasing），不新增独立动画体系；
    // 上滑进全屏与下滑回半屏走同一条吸附动画，保证两个方向的动作一致
    val panelSnapAnimation = tween<Float>(
        durationMillis = HalfExpandableSnapDurationMs,
        easing = LinearOutSlowInEasing
    )
    val settlePanel: (Float) -> Unit = { target ->
        targetFull = target == fullHeightPx
        panelAnimJob?.cancel()
        panelAnimJob = scope.launch {
            animate(panelHeightPx, target, animationSpec = panelSnapAnimation) { value, _ ->
                panelHeightPx = value
            }
        }
    }
    // 屏幕尺寸变化（旋转 / 分屏）时把面板重新落到「用户选定档位」对应的新高度
    LaunchedEffect(halfHeightPx, fullHeightPx) {
        panelAnimJob?.cancel()
        panelHeightPx = if (targetFull) fullHeightPx else halfHeightPx
    }

    val controls = HalfExpandableSheetControls(
        isFullScreen = isFullScreen,
        landscape = landscape,
        toggleFullScreen = { settlePanel(if (isFullScreen) halfHeightPx else fullHeightPx) },
        collapseToHalf = { settlePanel(halfHeightPx) }
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { panelHeightPx.toDp() })
            .let { if (panelTestTag != null) it.testTag(panelTestTag) else it }
            .bottomSheetScrollGuard { false }
    ) {
        // 勾柄 + 标题栏整条都支持上下拖拽：拖动时面板高度跟手变化，松手吸附到 全屏 / 半屏 / 关闭 三档之一。
        // 手势在 Initial pass 抢下纵向位移：外层 ModalBottomSheet 的拖拽走 Main pass，
        // 若留到 Main pass 竞争会先被 sheet 消费，表现为「按住上方拖不动」。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (headerTestTag != null) it.testTag(headerTestTag) else it }
                .pointerInput(fullHeightPx, halfHeightPx, landscape) {
                    if (landscape) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startHeight = panelHeightPx
                        var totalDy = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            // 只接管纵向拖动，横向位移留给其它手势
                            if (delta.y == 0f || delta.y.absoluteValue < delta.x.absoluteValue) continue
                            if (!dragging) {
                                // 起手拖动时先打断上一次吸附动画，否则动画会继续覆盖手指位置
                                dragging = true
                                panelAnimJob?.cancel()
                            }
                            totalDy += delta.y
                            change.consume()
                            // 跟手：往上拖面板变高，往下拖面板变矮（手指位移 1:1 映射到高度）
                            panelHeightPx = (startHeight - totalDy).coerceIn(0f, fullHeightPx)
                        }
                        if (!dragging) return@awaitEachGesture
                        // 松手吸附：比半屏再低 96dp 就是「关闭」档；越过半屏与全屏的中线就是「全屏」档；其余回半屏。
                        // 一次手势只结算一次，所以不会出现「下拉横条直接从全屏关掉」。
                        when {
                            panelHeightPx <= halfHeightPx - dismissDistancePx -> onDismiss()
                            panelHeightPx >= (halfHeightPx + fullHeightPx) / 2f -> settlePanel(fullHeightPx)
                            else -> settlePanel(halfHeightPx)
                        }
                    }
                }
        ) {
            // 勾柄：外观与 BottomSheetDefaults 一致，点击仍然关闭面板
            if (!landscape) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    BottomSheetDefaults.DragHandle()
                }
            }
            with(controls) { header() }
        }
        content()
    }
}
