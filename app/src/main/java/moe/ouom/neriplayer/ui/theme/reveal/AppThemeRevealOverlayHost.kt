package moe.ouom.neriplayer.ui.theme.reveal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay

internal data class AppThemeRevealPresentation(
    val originInWindow: Offset,
    val fallbackColorArgb: Int,
    val captureToken: Int,
    val reverse: Boolean = false
)

internal fun appThemeRevealPresentation(
    originInWindow: Offset?,
    fallbackColorArgb: Int?,
    captureToken: Int,
    reverse: Boolean = false
): AppThemeRevealPresentation? {
    if (originInWindow == null || fallbackColorArgb == null) return null
    return AppThemeRevealPresentation(originInWindow, fallbackColorArgb, captureToken, reverse)
}

/** Going back to light collapses the dark snapshot into the toggle instead of expanding. */
internal fun shouldReverseThemeReveal(nextDark: Boolean): Boolean = !nextDark

@Composable
internal fun AppThemeRevealOverlayHost(
    presentation: AppThemeRevealPresentation?,
    snapshot: ImageBitmap?,
    startRadiusPx: Float,
    onFinished: (Int) -> Unit
) {
    themeRevealContent(presentation, snapshot, startRadiusPx, onFinished)()
}

private fun themeRevealContent(
    presentation: AppThemeRevealPresentation?,
    snapshot: ImageBitmap?,
    startRadiusPx: Float,
    onFinished: (Int) -> Unit
): @Composable () -> Unit {
    if (presentation == null) return {}
    return {
        ThemeRevealOverlay(
            snapshot = snapshot,
            fallbackColor = Color(presentation.fallbackColorArgb),
            originInWindow = presentation.originInWindow,
            modifier = Modifier.fillMaxSize(),
            startRadiusPx = startRadiusPx,
            reverse = presentation.reverse,
            durationMillis = if (presentation.reverse) {
                THEME_REVEAL_REVERSE_DURATION_MILLIS
            } else {
                THEME_REVEAL_DURATION_MILLIS
            },
            onFinished = { onFinished(presentation.captureToken) }
        )
    }
}
