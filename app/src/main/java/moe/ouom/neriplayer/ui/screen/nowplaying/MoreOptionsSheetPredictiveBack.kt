package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.component.common.PredictiveDismissState
import kotlin.math.min

private val SheetBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private val SheetBackMaxWidthReduction = 48.dp
private val SheetBackMaxHeightReduction = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.moreOptionsSheetPredictiveBack(
    state: PredictiveDismissState,
    sheetState: SheetState
): Modifier = graphicsLayer {
    val fraction = SheetBackEasing.transform(state.progress.coerceIn(0f, 1f))
    val sheetHeight = size.height
    scaleX = sheetBackScale(fraction, size.width, SheetBackMaxWidthReduction.toPx())
    scaleY = sheetBackScale(fraction, sheetHeight, SheetBackMaxHeightReduction.toPx())
    val sheetOffset = if (fraction > 0f && sheetHeight > 0f) sheetState.requireOffset() else 0f
    transformOrigin = TransformOrigin(
        pivotFractionX = 0.5f,
        pivotFractionY = if (sheetHeight > 0f) (sheetOffset + sheetHeight) / sheetHeight else 1f
    )
    // 原生在拖动定位后缩放，公共 modifier 位于定位前，需补回 (1 - scaleY) * sheetOffset
    // 此层只变换完整面板，圆角裁剪仍由原有 Surface 负责
    translationY = (1f - scaleY) * sheetOffset
}

private fun sheetBackScale(fraction: Float, sizePx: Float, reductionPx: Float): Float =
    if (sizePx > 0f) 1f - min(reductionPx, sizePx) * fraction / sizePx else 1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreOptionsSheetPredictiveContent(
    state: PredictiveDismissState,
    containerSize: IntSize,
    sheetState: SheetState,
    showDragHandle: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().graphicsLayer {
            val fraction = SheetBackEasing.transform(state.progress.coerceIn(0f, 1f))
            val sheetScaleX = sheetBackScale(fraction, containerSize.width.toFloat(), SheetBackMaxWidthReduction.toPx())
            val sheetScaleY = sheetBackScale(fraction, containerSize.height.toFloat(), SheetBackMaxHeightReduction.toPx())
            // 容器横纵缩放不同，统一补偿拖柄和内容，让文字与图标最终等比缩放
            scaleY = if (sheetScaleY != 0f) sheetScaleX / sheetScaleY else 1f
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
    ) {
        if (showDragHandle) MoreOptionsSheetDragHandle(sheetState, onDismissRequest)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreOptionsSheetDragHandle(sheetState: SheetState, onDismissRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().clickable(interactionSource = interactionSource, indication = null) {
            scope.launch {
                when (sheetState.currentValue) {
                    SheetValue.Expanded -> {
                        sheetState.hide()
                        if (!sheetState.isVisible) onDismissRequest()
                    }
                    SheetValue.PartiallyExpanded -> sheetState.expand()
                    SheetValue.Hidden -> sheetState.show()
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        BottomSheetDefaults.DragHandle()
    }
}
