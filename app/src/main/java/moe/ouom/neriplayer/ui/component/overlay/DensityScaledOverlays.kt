package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal val LocalOverlaySurfaceScale = compositionLocalOf { 1f }

internal fun shouldScaleOverlaySurface(surfaceScale: Float): Boolean =
    abs(surfaceScale - 1f) > 0.001f

internal fun resolveScaledOverlaySurfaceMaxWidth(
    availableWidthPx: Int,
    minimumWidthPx: Int,
    surfaceScale: Float
): Int {
    if (availableWidthPx == Constraints.Infinity) {
        return availableWidthPx
    }
    val normalizedScale = if (surfaceScale.isFinite()) {
        surfaceScale.coerceAtLeast(0f)
    } else {
        1f
    }
    return (availableWidthPx * normalizedScale)
        .roundToInt()
        .coerceIn(minimumWidthPx, availableWidthPx)
}

@Composable
internal fun DensityScaledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val surfaceScale = LocalOverlaySurfaceScale.current
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = Modifier.scaleOverlaySurfaceWidth(surfaceScale).then(modifier),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DensityScaledModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Top)
    },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val dragHandleInteractionSource = remember { MutableInteractionSource() }

    // 保持 Sheet 贴边，密度缩放由 LocalDensity 传递给内部内容
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        // 绕过 Material 默认的 TooltipBox，避免按住把手时出现矩形遮罩
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = {
            if (dragHandle != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = dragHandleInteractionSource,
                            indication = null
                        ) {
                            scope.launch {
                                when (sheetState.currentValue) {
                                    SheetValue.Expanded -> {
                                        sheetState.hide()
                                        if (!sheetState.isVisible) {
                                            onDismissRequest()
                                        }
                                    }
                                    SheetValue.PartiallyExpanded -> sheetState.expand()
                                    SheetValue.Hidden -> sheetState.show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    dragHandle()
                }
            }
            content()
        }
    )
}

private fun Modifier.scaleOverlaySurfaceWidth(surfaceScale: Float): Modifier {
    if (!shouldScaleOverlaySurface(surfaceScale)) {
        return this
    }
    return layout { measurable, constraints ->
        val scaledConstraints = constraints.copy(
            maxWidth = resolveScaledOverlaySurfaceMaxWidth(
                availableWidthPx = constraints.maxWidth,
                minimumWidthPx = constraints.minWidth,
                surfaceScale = surfaceScale
            )
        )
        val placeable = measurable.measure(scaledConstraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
}
