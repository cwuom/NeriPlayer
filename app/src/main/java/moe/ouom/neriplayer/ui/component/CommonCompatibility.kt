package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import moe.ouom.neriplayer.ui.component.common.blockUnderlyingTouches as newBlockUnderlyingTouches

fun Modifier.blockUnderlyingTouches(): Modifier =
    newBlockUnderlyingTouches()

@Composable
fun ThemeRevealOverlay(
    snapshot: ImageBitmap?,
    fallbackColor: Color,
    originInWindow: Offset,
    modifier: Modifier = Modifier,
    startRadiusPx: Float = 1f,
    legacySnapshotDim: Boolean = false,
    durationMillis: Int = 720,
    onFinished: () -> Unit
) {
    moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay(
        snapshot = snapshot,
        fallbackColor = fallbackColor,
        originInWindow = originInWindow,
        modifier = modifier,
        startRadiusPx = startRadiusPx,
        legacySnapshotDim = legacySnapshotDim,
        durationMillis = durationMillis,
        onFinished = onFinished
    )
}
