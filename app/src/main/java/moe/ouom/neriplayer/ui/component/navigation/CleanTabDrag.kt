package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.NeriMotion
import kotlin.math.abs

/**
 * Press-and-slide tab switching for the clean tab bar. Item geometry is resolved from layout
 * coordinates relative to the bar at draw time, so bar slides, page recedes and other layer
 * transforms never desync the pill from its tab.
 */
@Stable
internal class CleanTabDragState {
    var bar: LayoutCoordinates? = null
    private val items = HashMap<Int, LayoutCoordinates>()
    var layoutVersion by mutableIntStateOf(0)
        private set
    internal val pillCenterX = Animatable(Float.NaN)
    /** Press progress, 0 at rest and 1 while a finger is on the bar; drives the lens swell. */
    internal val dragProgress = Animatable(0f)
    /** Horizontal pill velocity in px/s; feeds the squash-and-stretch of the moving lens. */
    internal val velocity = Animatable(0f)
    var dragging by mutableStateOf(false)
        internal set
    var hoveredIndex by mutableIntStateOf(-1)
        internal set

    private val lastCenters = HashMap<Int, Float>()

    /** Bumps [layoutVersion] whenever a tab appears or its center moves (tabs added/removed, resize). */
    fun reportItem(index: Int, coordinates: LayoutCoordinates) {
        items[index] = coordinates
        val center = bounds(index)?.center?.x ?: return
        if (lastCenters[index] != center) {
            lastCenters[index] = center
            layoutVersion++
        }
    }

    /** Forgets tabs past [count] so a removed tab never anchors the pill. */
    fun trimItems(count: Int) {
        if (items.keys.none { it >= count }) return
        items.keys.removeAll { it >= count }
        lastCenters.keys.removeAll { it >= count }
        layoutVersion++
    }

    /** Item bounds in bar-local space, independent of any transform above the bar. */
    fun bounds(index: Int): Rect? {
        val host = bar ?: return null
        val item = items[index] ?: return null
        if (!host.isAttached || !item.isAttached) return null
        return host.localBoundingBoxOf(item, clipBounds = false)
    }

    fun anyBounds(): Rect? = items.keys.firstNotNullOfOrNull(::bounds)

    /** Centers of the first and last tab, the travel range of the pill. */
    fun travelRange(): ClosedFloatingPointRange<Float>? {
        val centers = items.keys.mapNotNull { bounds(it)?.center?.x }
        if (centers.isEmpty()) return null
        return centers.min()..centers.max()
    }

    fun indexAt(x: Float): Int =
        items.keys.mapNotNull { index -> bounds(index)?.let { index to it } }
            .minByOrNull { (_, rect) -> abs(rect.center.x - x) }?.first ?: -1

    private var settledIndex = -1

    /** Glides to a newly selected tab; a pure layout change snaps so the pill never lags behind. */
    suspend fun settleOn(index: Int) {
        val target = bounds(index) ?: return
        val reselect = index != settledIndex
        settledIndex = index
        when {
            pillCenterX.value.isNaN() || !reselect -> if (!dragging) pillCenterX.snapTo(target.center.x)
            !dragging -> pillCenterX.animateTo(target.center.x, NeriMotion.smooth())
        }
    }
}

private const val PressScaleDamping = 1f
private const val PressScaleStiffness = 1000f

/**
 * Press-and-slide gesture after the iOS tab bar: touching the bar swells the lens over the
 * selected tab, sliding carries it with the finger (rubber-banding past the ends), and lifting
 * springs it into the nearest tab. A plain tap still reaches the tab's own click handler.
 */
internal fun Modifier.cleanTabDragGesture(
    state: CleanTabDragState,
    onSelect: (Int) -> Unit
): Modifier = composed {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            scope.launch { state.dragProgress.animateTo(1f, spring(PressScaleDamping, PressScaleStiffness)) }
            var dragging = false
            var travelled = 0f
            var last = down
            var velocityJob: Job? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    last = change
                    break
                }
                travelled += change.positionChange().x
                if (!dragging && abs(travelled) > viewConfiguration.touchSlop) {
                    dragging = true
                    state.dragging = true
                    state.hoveredIndex = state.indexAt(change.position.x)
                }
                if (dragging) {
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val x = state.travelRange()?.let { range -> rubberBand(change.position.x, range, size.width.toFloat()) }
                        ?: change.position.x
                    val v = tracker.calculateVelocity().x
                    scope.launch { state.pillCenterX.snapTo(x) }
                    // move events stop while the finger rests, so the stretch must relax on its own
                    velocityJob?.cancel()
                    velocityJob = scope.launch {
                        state.velocity.animateTo(v, spring(1f, 1400f))
                        state.velocity.animateTo(0f, spring(0.9f, 260f))
                    }
                    val index = state.indexAt(change.position.x)
                    if (index != state.hoveredIndex && index >= 0) {
                        state.hoveredIndex = index
                        context.performHapticFeedback()
                    }
                }
                last = change
            }
            scope.launch { state.dragProgress.animateTo(0f, spring(0.82f, 420f)) }
            velocityJob?.cancel()
            scope.launch { state.velocity.animateTo(0f, spring(0.5f, 300f)) }
            if (dragging) {
                val index = state.indexAt(last.position.x)
                state.dragging = false
                state.bounds(index)?.let { target ->
                    scope.launch { state.pillCenterX.animateTo(target.center.x, NeriMotion.bouncy()) }
                }
                if (index >= 0) onSelect(index)
            }
        }
    }
}

/** Lets the pill overshoot the end tabs by a damped fraction instead of stopping dead. */
private fun rubberBand(x: Float, range: ClosedFloatingPointRange<Float>, width: Float): Float {
    val overshoot = when {
        x < range.start -> x - range.start
        x > range.endInclusive -> x - range.endInclusive
        else -> return x
    }
    val limit = width * 0.06f
    val damped = limit * (1f - 1f / (abs(overshoot) / limit + 1f))
    return (if (overshoot < 0) range.start else range.endInclusive) + damped * kotlin.math.sign(overshoot)
}
