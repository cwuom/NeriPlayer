package moe.ouom.neriplayer.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
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
            var activePresses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> activePresses++
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> activePresses = (activePresses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (activePresses > 0) 1f else 0f
                launch {
                    pressProgress.animateTo(
                        targetValue = target,
                        animationSpec = if (target > 0f) {
                            spring(dampingRatio = 1f, stiffness = 1400f)
                        } else {
                            spring(dampingRatio = 0.62f, stiffness = 420f)
                        }
                    ) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val progress = pressProgress.value
        if (progress <= 0.001f && progress >= -0.001f) {
            drawContent()
            return
        }
        val longestSide = maxOf(size.width, size.height).coerceAtLeast(1f)
        val shrink = (PressInsetDp * 2f * density / longestSide).coerceAtMost(MaxPressShrink)
        val scale = 1f - shrink * progress
        scale(scale) {
            this@draw.drawContent()
            val highlight = (PressHighlightAlpha * progress).coerceIn(0f, PressHighlightAlpha)
            if (highlight > 0f) {
                drawRect(highlightColor.value.copy(alpha = highlight))
            }
        }
    }
}
