package moe.ouom.neriplayer.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Press feedback for the clean style: content sinks by a constant ~3dp per edge (so wide
 * rows barely move and small tiles visibly yield) plus a soft highlight. Pure draw-phase
 * transform, so a press never recomposes or relayouts the pressed subtree.
 */
internal class CleanPressIndication(
    private val highlightColor: State<Color>
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        CleanPressIndicationNode(interactionSource, highlightColor)

    override fun equals(other: Any?): Boolean =
        other is CleanPressIndication && other.highlightColor == highlightColor

    override fun hashCode(): Int = highlightColor.hashCode()
}

private const val PressInsetDp = 3f
private const val MaxPressShrink = 0.06f
private const val PressHighlightAlpha = 0.07f

private class CleanPressIndicationNode(
    private val interactionSource: InteractionSource,
    private val highlightColor: State<Color>
) : Modifier.Node(), DrawModifierNode {
    private val pressProgress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.animateCleanPress(pressProgress) { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        val progress = pressProgress.value
        if (progress <= 0.001f && progress >= -0.001f) {
            drawContent()
            return
        }
        scale(cleanPressScale(progress, size, density)) {
            this@draw.drawContent()
            drawCleanPressHighlight(progress, highlightColor.value)
        }
    }
}

/**
 * Animates [progress] toward 1 while any press is held and springs it back on release.
 * Shared by the indication node and by callers that apply the sink on a whole layer.
 */
internal suspend fun InteractionSource.animateCleanPress(
    progress: Animatable<Float, AnimationVector1D>,
    onFrame: () -> Unit = {}
) = coroutineScope {
    var activePresses = 0
    interactions.collect { interaction ->
        when (interaction) {
            is PressInteraction.Press -> activePresses++
            is PressInteraction.Release,
            is PressInteraction.Cancel -> activePresses = (activePresses - 1).coerceAtLeast(0)
            else -> return@collect
        }
        val target = if (activePresses > 0) 1f else 0f
        launch {
            progress.animateTo(
                targetValue = target,
                animationSpec = if (target > 0f) {
                    spring(dampingRatio = 1f, stiffness = 1400f)
                } else {
                    spring(dampingRatio = 0.62f, stiffness = 420f)
                }
            ) { onFrame() }
        }
    }
}

internal fun cleanPressScale(progress: Float, size: Size, density: Float): Float {
    val longestSide = maxOf(size.width, size.height).coerceAtLeast(1f)
    val shrink = (PressInsetDp * 2f * density / longestSide).coerceAtMost(MaxPressShrink)
    return 1f - shrink * progress
}

internal fun DrawScope.drawCleanPressHighlight(progress: Float, color: Color) {
    val highlight = (PressHighlightAlpha * progress).coerceIn(0f, PressHighlightAlpha)
    if (highlight > 0f) {
        drawRect(color.copy(alpha = highlight))
    }
}

/**
 * Press progress for elements that clip or cast a shadow themselves. The indication only scales
 * what is drawn inside the clip, so a rounded outline would stay full size and slice the corners;
 * such callers scale their whole layer with [cleanPressLayerScale] instead.
 */
@Composable
internal fun rememberCleanPressProgress(interactionSource: InteractionSource): State<Float> {
    val progress = remember(interactionSource) { Animatable(0f) }
    LaunchedEffect(interactionSource, progress) {
        interactionSource.animateCleanPress(progress)
    }
    return progress.asState()
}

internal fun Modifier.cleanPressLayerScale(progress: State<Float>): Modifier = graphicsLayer {
    val scale = cleanPressScale(progress.value, size, density)
    scaleX = scale
    scaleY = scale
}

internal fun Modifier.cleanPressHighlight(progress: State<Float>, color: Color): Modifier =
    drawWithContent {
        drawContent()
        drawCleanPressHighlight(progress.value, color)
    }
