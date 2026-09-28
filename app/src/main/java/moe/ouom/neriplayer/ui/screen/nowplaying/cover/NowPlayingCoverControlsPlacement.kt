package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun nowPlayingProgressToControlsSpacing(wideLandscape: Boolean): Dp =
    if (wideLandscape) 14.dp else 10.dp

@Composable
internal fun ColumnScope.NowPlayingLeadingProgress(
    progressAtBottom: Boolean,
    wideLandscape: Boolean,
    progress: @Composable () -> Unit
) {
    if (progressAtBottom) return
    Spacer(Modifier.height(12.dp))
    progress()
    Spacer(Modifier.height(nowPlayingProgressToControlsSpacing(wideLandscape)))
}

@Composable
internal fun ColumnScope.NowPlayingLeadingControls(
    controlsAtBottom: Boolean,
    controls: @Composable () -> Unit
) {
    if (!controlsAtBottom) controls()
}

@Composable
internal fun ColumnScope.NowPlayingTrailingControls(
    controlsAtBottom: Boolean,
    progressAtBottom: Boolean,
    wideLandscape: Boolean,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    if (!controlsAtBottom) return
    NowPlayingTrailingProgress(progressAtBottom, wideLandscape, progress)
    controls()
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ColumnScope.NowPlayingTrailingProgress(
    progressAtBottom: Boolean,
    wideLandscape: Boolean,
    progress: @Composable () -> Unit
) {
    if (!progressAtBottom) return
    progress()
    Spacer(Modifier.height(nowPlayingProgressToControlsSpacing(wideLandscape)))
}
