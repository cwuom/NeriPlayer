package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import kotlin.math.roundToInt

internal const val PlayPauseCornerPercentPaused = 50f
internal const val PlayPauseCornerPercentPlaying = 34f
internal const val PlayPauseCornerPercentPressed = 22f
internal const val CleanPlayPausePressedScale = 0.86f

/** Corner percent for the Material morphing container: circle at rest, squircle while playing, tighter under the finger */
internal fun resolvePlayPauseCornerPercent(isPlaying: Boolean, pressed: Boolean): Float = when {
    pressed -> PlayPauseCornerPercentPressed
    isPlaying -> PlayPauseCornerPercentPlaying
    else -> PlayPauseCornerPercentPaused
}

/**
 * Primary transport button. Material fills a tonal accent container whose corners morph with the
 * playback state; Clean drops the container so the glyph itself carries the weight and only
 * shrinks under the finger
 */
@Composable
fun NeriPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val context = LocalContext.current
    val click = {
        context.performHapticFeedback(HapticFeedbackEffect.Confirm)
        onClick()
    }
    val colors = MaterialTheme.colorScheme
    if (NeriDesign.isClean) {
        val scale by animateFloatAsState(
            targetValue = if (pressed && enabled) CleanPlayPausePressedScale else 1f,
            animationSpec = NeriMotion.bouncy(),
            label = "clean_play_pause_scale"
        )
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = click
                ),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                content = content
            )
        }
        return
    }
    val cornerPercent by animateFloatAsState(
        targetValue = resolvePlayPauseCornerPercent(isPlaying, pressed && enabled),
        animationSpec = NeriMotion.bouncy(),
        label = "material_play_pause_corner"
    )
    // CornerSize(Float) 是像素, 百分比必须走 Int 重载
    val shape = RoundedCornerShape(CornerSize(cornerPercent.roundToInt()))
    val container = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f)
    val contentColor = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button,
                onClick = click
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}
