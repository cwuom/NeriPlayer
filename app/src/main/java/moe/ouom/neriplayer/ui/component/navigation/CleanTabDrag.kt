package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import android.content.Context
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
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
    /**
     * Pressed size of the enhanced lens relative to the resting pill, per axis. Separate
     * underdamped springs (after miuix / Kyant's DampedDragAnimation) make the lens pop and
     * settle like jelly as it swells and as it deflates
     */
    internal val lensScaleX = Animatable(1f)
    internal val lensScaleY = Animatable(1f)
    /** Horizontal pill velocity in px/s; feeds the squash-and-stretch of the moving lens. */
    internal val velocity = Animatable(0f)
    var dragging by mutableStateOf(false)
        internal set
    /** Bar-local x of the finger for the touch glow; NaN while untouched. */
    internal var glowCenter by mutableFloatStateOf(Float.NaN)
    internal val glowAlpha = Animatable(0f)
    internal val glowSpread = Animatable(1f)
    var hoveredIndex by mutableIntStateOf(-1)
        internal set
    /** True while a finger rests on the bar. */
    var pressed by mutableStateOf(false)
        internal set
    /** Signed jelly deformation of the enhanced lens: stretched while positive, squashed below 0. */
    internal var jelly by mutableFloatStateOf(0f)
        private set
    private var jellyVelocity = 0f

    /** The lens is moving or held, so the jelly spring has something to follow. */
    fun jellyActive(): Boolean = pressed || pillCenterX.isRunning

    /**
     * Runs the jelly spring frame by frame until the lens is released, settled and still. The
     * stretch follows the speed of the pill itself, so taps, drags and the release glide all
     * wobble the same way
     */
    suspend fun runJelly() {
        var spring = JellySpring(jelly, jellyVelocity)
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            val tabWidth = anyBounds()?.width ?: 0f
            val target = if (pillCenterX.isRunning) jellyTarget(pillCenterX.velocity, tabWidth) else 0f
            spring = stepJelly(spring, target, dt)
            jelly = spring.value
            jellyVelocity = spring.velocity
            if (!jellyActive() && spring.isAtRest()) break
        }
        jelly = 0f
        jellyVelocity = 0f
    }

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

    /** Swells the lens: glass effects follow a stiff spring, the size pops on softer ones. */
    fun inflateLens(scope: CoroutineScope, enhanced: Boolean) {
        scope.launch { dragProgress.animateTo(1f, spring(PressScaleDamping, PressScaleStiffness)) }
        if (!enhanced) return
        scope.launch { lensScaleX.animateTo(LensPressedScale, spring(LensScaleXDamping, LensScaleStiffness)) }
        scope.launch { lensScaleY.animateTo(LensPressedScale, spring(LensScaleYDamping, LensScaleStiffness)) }
    }

    /**
     * Deflates the lens once it has nearly reached [targetX] (when given), so a tap or a release
     * reads as one motion: the lens travels swollen and settles into the tab. A new press while
     * waiting keeps it swollen
     */
    suspend fun deflateLens(targetX: Float?, enhanced: Boolean) = coroutineScope {
        if (!enhanced) {
            dragProgress.animateTo(0f, spring(0.82f, 420f))
            return@coroutineScope
        }
        if (targetX != null) awaitLensNear(targetX)
        if (pressed) return@coroutineScope
        launch { dragProgress.animateTo(0f, spring(PressScaleDamping, PressScaleStiffness)) }
        launch { lensScaleX.animateTo(1f, spring(LensScaleXDamping, LensScaleStiffness)) }
        launch { lensScaleY.animateTo(1f, spring(LensScaleYDamping, LensScaleStiffness)) }
    }

    /** Suspends until the lens is close to [targetX] or has stopped, at most a short while. */
    suspend fun awaitLensNear(targetX: Float) {
        val threshold = (anyBounds()?.width ?: 0f) * TapDeflateDistanceFraction
        withTimeoutOrNull(TapDeflateMaxWaitMillis) {
            snapshotFlow { abs(pillCenterX.value - targetX) <= threshold || !pillCenterX.isRunning }
                .first { it }
        }
    }

    /**
     * After a tap on [touched], gives the click a moment to select it; when the selection stays
     * elsewhere (cancelled gesture, rejected navigation) the lens glides back to the selected tab
     */
    suspend fun returnToSelectionUnless(touched: Int) {
        delay(UnchosenTapGraceMillis)
        if (pressed || dragging || settledIndex < 0 || settledIndex == touched) return
        val target = bounds(settledIndex) ?: return
        pillCenterX.animateTo(target.center.x, spring(ReleaseDamping, ReleaseStiffness))
    }

    /** Glides to a newly selected tab; a pure layout change snaps so the pill never lags behind. */
    suspend fun settleOn(index: Int) {
        val target = bounds(index) ?: return
        val reselect = index != settledIndex
        settledIndex = index
        when {
            pillCenterX.value.isNaN() || !reselect -> if (!dragging) pillCenterX.snapTo(target.center.x)
            // 松手回弹已经在奔向这个 Tab 时不打断它，保留惯性和过冲
            pillCenterX.isRunning && pillCenterX.targetValue == target.center.x -> Unit
            !dragging -> pillCenterX.animateTo(target.center.x, NeriMotion.smooth())
        }
    }
}

// 松手时光晕放大到 4 倍并淡出，参照 FlClash 的触点光晕
private const val GlowReleaseGrowth = 4f
private const val GlowReleaseMillis = 320

private const val PressScaleDamping = 1f
private const val PressScaleStiffness = 1000f
// 跟手弹簧：临界阻尼紧跟手指不过冲，惯性感交给速度形变，参照 miuix
private const val FollowDamping = 1f
private const val FollowStiffness = 1000f
// 透镜按下放大到 78/56，宽高各用一条欠阻尼弹簧，鼓起和收回时像果冻一样弹一下
internal const val LensPressedScale = 78f / 56f
private const val LensScaleXDamping = 0.6f
private const val LensScaleYDamping = 0.7f
private const val LensScaleStiffness = 250f
// 点按后等点击生效的时长，超时仍未选中就让透镜回到选中的 Tab
private const val UnchosenTapGraceMillis = 260L
// 点按时滑向目标 Tab 的弹簧：不过冲，一段平滑到位
private const val PressGlideDamping = 0.86f
private const val PressGlideStiffness = 420f
// 点按时透镜离目标还剩多少个 Tab 宽就开始收回，以及最长等待
private const val TapDeflateDistanceFraction = 0.15f
private const val TapDeflateMaxWaitMillis = 320L
// 松手滑进落点：轻微过冲后落定
private const val ReleaseDamping = 0.75f
private const val ReleaseStiffness = 450f

/**
 * Press-and-slide gesture after the iOS tab bar: touching the bar swells the lens over the
 * selected tab, sliding carries it with the finger (rubber-banding past the ends), and lifting
 * springs it into the nearest tab. A plain tap still reaches the tab's own click handler.
 */
internal fun Modifier.cleanTabDragGesture(
    state: CleanTabDragState,
    enhanced: Boolean,
    onSelect: (Int) -> Unit
): Modifier = composed {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    pointerInput(state, enhanced) {
        // 手势协程被取消时也要放开按住状态，否则果冻弹簧会一直空转
        try {
            cleanTabGestures(state, enhanced, context, scope, onSelect)
        } finally {
            state.pressed = false
        }
    }
}

private suspend fun PointerInputScope.cleanTabGestures(
    state: CleanTabDragState,
    enhanced: Boolean,
    context: Context,
    scope: CoroutineScope,
    onSelect: (Int) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.pressed = true
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        state.inflateLens(scope, enhanced)
        state.glowCenter = down.position.x
        if (enhanced && !state.pillCenterX.value.isNaN()) {
            // 参照 FlClash：透镜在当前 Tab 鼓起的同时，一次滑到被按的 Tab 中心，不分两段走
            state.bounds(state.indexAt(down.position.x))?.let { target ->
                scope.launch {
                    state.pillCenterX.animateTo(target.center.x, spring(PressGlideDamping, PressGlideStiffness))
                }
            }
        }
        scope.launch {
            state.glowSpread.snapTo(1f)
            state.glowAlpha.animateTo(1f, spring(1f, 700f))
        }
        var dragging = false
        var travelled = 0f
        var last = down
        var lifted = false
        var velocityJob: Job? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                last = change
                lifted = true
                break
            }
            travelled += change.positionChange().x
            state.glowCenter = change.position.x
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
                scope.launch {
                    // 增强模式下透镜用弹簧追手指，略微滞后，带出惯性和果冻感
                    if (enhanced) {
                        state.pillCenterX.animateTo(x, spring(FollowDamping, FollowStiffness))
                    } else {
                        state.pillCenterX.snapTo(x)
                    }
                }
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
        state.pressed = false
        val tapped = if (enhanced && !dragging && lifted) state.indexAt(last.position.x) else -1
        val tapTarget = state.bounds(tapped)?.center?.x
        // 手指停在哪个 Tab 就切到哪个
        val landing = if (dragging) state.indexAt(last.position.x) else -1
        val landingTarget = state.bounds(landing)?.center?.x
        // 透镜保持鼓起滑到目标附近再收回，读作一个连贯动作，而不是先瘪再挪
        scope.launch { state.deflateLens(tapTarget ?: landingTarget, enhanced) }
        scope.launch { state.glowSpread.animateTo(GlowReleaseGrowth, tween(GlowReleaseMillis)) }
        scope.launch { state.glowAlpha.animateTo(0f, tween(GlowReleaseMillis)) }
        velocityJob?.cancel()
        scope.launch { state.velocity.animateTo(0f, spring(0.5f, 300f)) }
        if (enhanced && !dragging) {
            // 点按松手：透镜回到被点的 Tab 中心，点击随后选中它时不会被打断；手势被取消或点击
            // 没有切换 Tab 时，再回到真正选中的 Tab，透镜不会停在未选中的位置
            if (tapTarget != null && !(state.pillCenterX.isRunning && state.pillCenterX.targetValue == tapTarget)) {
                scope.launch { state.pillCenterX.animateTo(tapTarget, spring(PressGlideDamping, PressGlideStiffness)) }
            }
            scope.launch { state.returnToSelectionUnless(tapped) }
        }
        if (dragging) {
            // 透镜带着跟手时的速度滑进落点
            state.dragging = false
            landingTarget?.let { target ->
                scope.launch {
                    val spec = if (enhanced) spring(ReleaseDamping, ReleaseStiffness) else NeriMotion.bouncy<Float>()
                    state.pillCenterX.animateTo(target, spec)
                }
            }
            if (landing >= 0) onSelect(landing)
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
