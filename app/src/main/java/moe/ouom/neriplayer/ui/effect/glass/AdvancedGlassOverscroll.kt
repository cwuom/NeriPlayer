package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

internal object AdvancedGlassOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = AdvancedGlassOverscrollEffect()

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = 1
}

private class AdvancedGlassOverscrollEffect : OverscrollEffect {
    private var offsetY = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidatePlacement?.invoke()
            }
        }
    private var rawDragY = 0f
    private var dragRangePx = 0f
    private var animationJob: Job? = null
    private var invalidatePlacement: (() -> Unit)? = null
    private var launchAnimation: ((suspend CoroutineScope.() -> Unit) -> Job)? = null

    override val isInProgress: Boolean
        get() = abs(offsetY) > OFFSET_THRESHOLD_PX

    override val node: DelegatableNode = AdvancedGlassOverscrollNode(this)

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        if (source != NestedScrollSource.UserInput || dragRangePx <= 0f) {
            return performScroll(delta)
        }

        animationJob?.cancel()

        var scrollDeltaY = delta.y
        var overscrollConsumedY = 0f
        if (
            abs(offsetY) > OFFSET_THRESHOLD_PX &&
            delta.y != 0f &&
            sign(delta.y) != sign(rawDragY)
        ) {
            val consumed = if (abs(rawDragY) <= abs(delta.y)) -rawDragY else delta.y
            if (abs(rawDragY) <= abs(delta.y)) {
                resetOffset()
                scrollDeltaY -= consumed
                overscrollConsumedY = consumed
            } else {
                applyDrag(consumed)
                scrollDeltaY = 0f
                overscrollConsumedY = delta.y
            }
        }

        val adjustedDelta = Offset(delta.x, scrollDeltaY)
        val scrollConsumed = performScroll(adjustedDelta)
        val unconsumedY = adjustedDelta.y - scrollConsumed.y
        if (unconsumedY != 0f) {
            applyDrag(unconsumedY)
        }

        return Offset(
            x = scrollConsumed.x,
            y = overscrollConsumedY + scrollConsumed.y + unconsumedY
        )
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        animationJob?.cancel()

        var flingVelocity = velocity
        if (abs(offsetY) > OFFSET_THRESHOLD_PX && velocity.y != 0f) {
            startReturnAnimation(velocity.y)
            flingVelocity = if (sign(velocity.y) == sign(offsetY)) {
                Velocity(velocity.x, 0f)
            } else {
                Velocity(velocity.x, velocity.y / REVERSE_FLING_ATTENUATION)
            }
        }

        val consumed = performFling(flingVelocity)
        val remaining = flingVelocity - consumed
        startReturnAnimation(remaining.y / POST_FLING_ATTENUATION)
    }

    fun attach(
        dragRangePx: Float,
        invalidatePlacement: () -> Unit,
        launchAnimation: (suspend CoroutineScope.() -> Unit) -> Job
    ) {
        this.dragRangePx = dragRangePx
        this.invalidatePlacement = invalidatePlacement
        this.launchAnimation = launchAnimation
    }

    fun updateDragRange(dragRangePx: Float) {
        if (this.dragRangePx == dragRangePx) return
        this.dragRangePx = dragRangePx
        rawDragY = rawDragY.coerceIn(-dragRangePx, dragRangePx)
        offsetY = dampedAdvancedGlassOverscrollOffset(rawDragY, dragRangePx)
    }

    fun currentOffsetY(): Float = offsetY

    fun detach() {
        animationJob?.cancel()
        animationJob = null
        resetOffset()
        invalidatePlacement = null
        launchAnimation = null
    }

    private fun applyDrag(delta: Float) {
        rawDragY = (rawDragY + delta).coerceIn(-dragRangePx, dragRangePx)
        offsetY = dampedAdvancedGlassOverscrollOffset(rawDragY, dragRangePx)
    }

    private fun startReturnAnimation(initialVelocity: Float = 0f) {
        if (abs(offsetY) <= OFFSET_THRESHOLD_PX && initialVelocity == 0f) {
            resetOffset()
            return
        }
        val launch = launchAnimation ?: return
        animationJob?.cancel()
        animationJob = launch {
            val stiffness = springStiffness(
                if (abs(initialVelocity) > HIGH_VELOCITY_THRESHOLD_PX_PER_SECOND) {
                    HIGH_VELOCITY_SPRING_PERIOD_SECONDS
                } else {
                    STANDARD_SPRING_PERIOD_SECONDS
                }
            )
            animate(
                initialValue = offsetY,
                targetValue = 0f,
                initialVelocity = initialVelocity.coerceIn(
                    -dragRangePx * MAX_INITIAL_VELOCITY_RANGE_MULTIPLIER,
                    dragRangePx * MAX_INITIAL_VELOCITY_RANGE_MULTIPLIER
                ),
                animationSpec = spring(
                    dampingRatio = CRITICAL_DAMPING_RATIO,
                    stiffness = stiffness,
                    visibilityThreshold = OFFSET_THRESHOLD_PX
                )
            ) { value, _ ->
                offsetY = value.coerceIn(-dragRangePx / 3f, dragRangePx / 3f)
                rawDragY = restoredAdvancedGlassOverscrollDrag(offsetY, dragRangePx)
            }
            resetOffset()
        }
    }

    private fun resetOffset() {
        offsetY = 0f
        rawDragY = 0f
    }
}

private class AdvancedGlassOverscrollNode(
    private val effect: AdvancedGlassOverscrollEffect
) : androidx.compose.ui.Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutModifierNode {

    override fun onAttach() {
        super.onAttach()
        effect.attach(
            dragRangePx = dragRangePx(),
            invalidatePlacement = { invalidatePlacement() },
            launchAnimation = { block -> coroutineScope.launch(block = block) }
        )
    }

    override fun onDetach() {
        effect.detach()
        super.onDetach()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        effect.updateDragRange(dragRangePx())
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                translationY = round(effect.currentOffsetY())
            }
        }
    }

    private fun dragRangePx(): Float = with(currentValueOf(LocalDensity)) {
        (MAX_OVERSCROLL_OFFSET_DP * 3f).dp.toPx()
    }
}

internal fun dampedAdvancedGlassOverscrollOffset(
    rawDrag: Float,
    dragRange: Float
): Float {
    if (rawDrag == 0f || dragRange <= 0f) return 0f
    val normalized = (abs(rawDrag) / dragRange).coerceIn(0f, 1f)
    val damped = normalized - normalized * normalized +
        normalized * normalized * normalized / 3f
    return sign(rawDrag) * damped * dragRange
}

internal fun restoredAdvancedGlassOverscrollDrag(
    offset: Float,
    dragRange: Float
): Float {
    if (offset == 0f || dragRange <= 0f) return 0f
    val normalized = (abs(offset) / dragRange).coerceIn(0f, 1f / 3f)
    val restored = 1.0 - (1.0 - 3.0 * normalized).coerceAtLeast(0.0).pow(1.0 / 3.0)
    return sign(offset) * restored.toFloat() * dragRange
}

private fun springStiffness(periodSeconds: Float): Float =
    ((2.0 * PI) / periodSeconds).pow(2.0).toFloat()

private const val MAX_OVERSCROLL_OFFSET_DP = 108f
private const val OFFSET_THRESHOLD_PX = 1f
private const val CRITICAL_DAMPING_RATIO = 1f
private const val STANDARD_SPRING_PERIOD_SECONDS = 0.4f
private const val HIGH_VELOCITY_SPRING_PERIOD_SECONDS = 0.55f
private const val HIGH_VELOCITY_THRESHOLD_PX_PER_SECOND = 5000f
private const val MAX_INITIAL_VELOCITY_RANGE_MULTIPLIER = 6f
private const val REVERSE_FLING_ATTENUATION = 2.13333f
private const val POST_FLING_ATTENUATION = 1.53333f
