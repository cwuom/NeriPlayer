package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

internal data class AdvancedGlassOverscrollBackdrop(
    val color: Color,
    val offsetY: MutableState<Float>
)

internal val LocalAdvancedGlassOverscrollBackdrop =
    staticCompositionLocalOf<AdvancedGlassOverscrollBackdrop?> { null }

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
                syncBackdropOffset?.invoke()
            }
        }
    private var rawDragY = 0f
    private var resistanceScalePx = 0f
    private var animationJob: Job? = null
    private var invalidatePlacement: (() -> Unit)? = null
    private var syncBackdropOffset: (() -> Unit)? = null
    private var launchAnimation: ((suspend CoroutineScope.() -> Unit) -> Job)? = null

    override val isInProgress: Boolean
        get() = abs(offsetY) > OFFSET_THRESHOLD_PX

    override val node: DelegatableNode = AdvancedGlassOverscrollNode(this)

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        if (source != NestedScrollSource.UserInput || resistanceScalePx <= 0f) {
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
        val hadActiveOverscroll = abs(offsetY) > OFFSET_THRESHOLD_PX && velocity.y != 0f
        if (hadActiveOverscroll) {
            startReturnAnimation(velocity.y)
            flingVelocity = if (sign(velocity.y) == sign(offsetY)) {
                Velocity(velocity.x, 0f)
            } else {
                Velocity(velocity.x, velocity.y / REVERSE_FLING_ATTENUATION)
            }
        }

        val consumed = performFling(flingVelocity)
        val remaining = flingVelocity - consumed
        if (!hadActiveOverscroll) {
            startReturnAnimation(remaining.y / POST_FLING_ATTENUATION)
        }
    }

    fun attach(
        resistanceScalePx: Float,
        invalidatePlacement: () -> Unit,
        syncBackdropOffset: () -> Unit,
        launchAnimation: (suspend CoroutineScope.() -> Unit) -> Job
    ) {
        this.resistanceScalePx = resistanceScalePx
        this.invalidatePlacement = invalidatePlacement
        this.syncBackdropOffset = syncBackdropOffset
        this.launchAnimation = launchAnimation
    }

    fun updateResistanceScale(resistanceScalePx: Float) {
        if (this.resistanceScalePx == resistanceScalePx) return
        this.resistanceScalePx = resistanceScalePx
        offsetY = dampedAdvancedGlassOverscrollOffset(rawDragY, resistanceScalePx)
    }

    fun currentOffsetY(): Float = offsetY

    fun detach() {
        animationJob?.cancel()
        animationJob = null
        resetOffset()
        invalidatePlacement = null
        syncBackdropOffset = null
        launchAnimation = null
    }

    private fun applyDrag(delta: Float) {
        rawDragY = boundedAdvancedGlassOverscrollRawDrag(
            rawDrag = rawDragY,
            delta = delta,
            resistanceScale = resistanceScalePx
        )
        offsetY = dampedAdvancedGlassOverscrollOffset(rawDragY, resistanceScalePx)
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
                advancedGlassOverscrollReturnPeriodSeconds(
                    offset = offsetY,
                    resistanceScale = resistanceScalePx
                )
            )
            animate(
                initialValue = offsetY,
                targetValue = 0f,
                initialVelocity = initialVelocity.coerceIn(
                    -resistanceScalePx * MAX_INITIAL_VELOCITY_SCALE_MULTIPLIER,
                    resistanceScalePx * MAX_INITIAL_VELOCITY_SCALE_MULTIPLIER
                ),
                animationSpec = spring(
                    dampingRatio = CRITICAL_DAMPING_RATIO,
                    stiffness = stiffness,
                    visibilityThreshold = OFFSET_THRESHOLD_PX
                )
            ) { value, _ ->
                offsetY = value.coerceIn(
                    -maxAdvancedGlassOverscrollOffset(resistanceScalePx),
                    maxAdvancedGlassOverscrollOffset(resistanceScalePx)
                )
                rawDragY = restoredAdvancedGlassOverscrollDrag(offsetY, resistanceScalePx)
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
            resistanceScalePx = resistanceScalePx(),
            invalidatePlacement = { invalidatePlacement() },
            syncBackdropOffset = { syncBackdropOffset() },
            launchAnimation = { block -> coroutineScope.launch(block = block) }
        )
    }

    override fun onDetach() {
        effect.detach()
        super.onDetach()
    }

    private fun syncBackdropOffset() {
        currentValueOf(LocalAdvancedGlassOverscrollBackdrop)?.offsetY?.value =
            effect.currentOffsetY()
    }

    private fun resistanceScalePx(): Float = with(currentValueOf(LocalDensity)) {
        OVERSCROLL_RESISTANCE_SCALE_DP.dp.toPx()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        effect.updateResistanceScale(resistanceScalePx())
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val offsetY = effect.currentOffsetY()
            if (offsetY == 0f) {
                placeable.place(0, 0)
            } else {
                placeable.placeWithLayer(0, 0) {
                    translationY = round(offsetY)
                }
            }
        }
    }
}

internal fun androidx.compose.ui.Modifier.drawAdvancedGlassOverscrollBackdrop(
    backdrop: AdvancedGlassOverscrollBackdrop
): androidx.compose.ui.Modifier = drawBehind {
    val fill = resolveAdvancedGlassOverscrollEdgeFill(
        offsetY = backdrop.offsetY.value,
        viewportHeight = size.height
    )
    if (fill != null) {
        drawRect(
            color = backdrop.color,
            topLeft = Offset(0f, fill.top),
            size = Size(size.width, fill.height)
        )
    }
}

internal data class AdvancedGlassOverscrollEdgeFill(
    val top: Float,
    val height: Float
)

internal fun resolveAdvancedGlassOverscrollEdgeFill(
    offsetY: Float,
    viewportHeight: Float
): AdvancedGlassOverscrollEdgeFill? {
    if (offsetY <= 0f || viewportHeight <= 0f) return null
    val height = round(offsetY).coerceAtMost(viewportHeight)
    if (height <= 0f) return null
    return AdvancedGlassOverscrollEdgeFill(
        top = 0f,
        height = height
    )
}

internal fun dampedAdvancedGlassOverscrollOffset(
    rawDrag: Float,
    resistanceScale: Float
): Float {
    if (rawDrag == 0f || resistanceScale <= 0f) return 0f
    val normalized = abs(rawDrag) / resistanceScale
    val maxOffset = maxAdvancedGlassOverscrollOffset(resistanceScale)
    return sign(rawDrag) * maxOffset * (1f - exp(-normalized))
}

internal fun restoredAdvancedGlassOverscrollDrag(
    offset: Float,
    resistanceScale: Float
): Float {
    if (offset == 0f || resistanceScale <= 0f) return 0f
    val maxOffset = maxAdvancedGlassOverscrollOffset(resistanceScale)
    val normalized = (abs(offset) / maxOffset).coerceIn(0f, 1f - INVERSE_EPSILON)
    return sign(offset) * -resistanceScale * ln(1f - normalized)
}

internal fun boundedAdvancedGlassOverscrollRawDrag(
    rawDrag: Float,
    delta: Float,
    resistanceScale: Float
): Float {
    val maxRawDrag = maxAdvancedGlassOverscrollRawDrag(resistanceScale)
    return (rawDrag + delta).coerceIn(-maxRawDrag, maxRawDrag)
}

internal fun maxAdvancedGlassOverscrollOffset(resistanceScale: Float): Float =
    (resistanceScale * MAX_OVERSCROLL_OFFSET_RATIO).coerceAtLeast(0f)

internal fun maxAdvancedGlassOverscrollRawDrag(resistanceScale: Float): Float =
    if (resistanceScale <= 0f) {
        0f
    } else {
        resistanceScale * -ln(INVERSE_EPSILON)
    }

internal fun advancedGlassOverscrollReturnPeriodSeconds(
    offset: Float,
    resistanceScale: Float
): Float {
    if (resistanceScale <= 0f) return MIN_RETURN_SPRING_PERIOD_SECONDS
    val progress = (
        abs(offset) / maxAdvancedGlassOverscrollOffset(resistanceScale)
    ).coerceIn(0f, 1f)
    return MIN_RETURN_SPRING_PERIOD_SECONDS +
        (MAX_RETURN_SPRING_PERIOD_SECONDS - MIN_RETURN_SPRING_PERIOD_SECONDS) * progress
}

private fun springStiffness(periodSeconds: Float): Float =
    ((2.0 * PI) / periodSeconds).pow(2.0).toFloat()

private const val OVERSCROLL_RESISTANCE_SCALE_DP = 108f
private const val OFFSET_THRESHOLD_PX = 1f
private const val CRITICAL_DAMPING_RATIO = 1f
private const val MIN_RETURN_SPRING_PERIOD_SECONDS = 0.44f
private const val MAX_RETURN_SPRING_PERIOD_SECONDS = 0.62f
private const val MAX_INITIAL_VELOCITY_SCALE_MULTIPLIER = 18f
private const val REVERSE_FLING_ATTENUATION = 2.13333f
private const val POST_FLING_ATTENUATION = 1.53333f
private const val MAX_OVERSCROLL_OFFSET_RATIO = 0.66f
private const val INVERSE_EPSILON = 0.0001f
