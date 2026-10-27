package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateRect
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.CoverFlightFrame
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.addSmoothedRoundRect
import moe.ouom.neriplayer.ui.theme.supportsContinuousCornerOutline
import kotlin.math.roundToInt

private val CoverBoundsSpring = spring<Rect>(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = Rect.VisibilityThreshold
)
private val CoverCornerSpring = spring<Float>(
    stiffness = Spring.StiffnessMediumLow
)
private val CoverBoundsPreview = tween<Rect>(300, easing = LinearEasing)
private val CoverCornerPreview = tween<Float>(300, easing = LinearEasing)

private class CoverFlightAnimationSpecs(
    val bounds: FiniteAnimationSpec<Rect>,
    val cornerProgress: FiniteAnimationSpec<Float>
)

private val CoverIdleSpecs = CoverFlightAnimationSpecs(snap(), snap())
private val CoverOrdinarySpecs = CoverFlightAnimationSpecs(CoverBoundsSpring, CoverCornerSpring)
private val CoverPreviewSpecs = CoverFlightAnimationSpecs(CoverBoundsPreview, CoverCornerPreview)

private fun coverFlightAnimationSpecs(
    navigating: Boolean,
    predictiveBackPreviewActive: Boolean
): CoverFlightAnimationSpecs = when {
    !navigating -> CoverIdleSpecs
    predictiveBackPreviewActive -> CoverPreviewSpecs
    else -> CoverOrdinarySpecs
}

/** 封面切换只移动一个图层，避免反向时在两份已录制尺寸之间交接 */
@Stable
class NowPlayingLyricsCoverFlight internal constructor(
    private val transition: Transition<Boolean>,
    private val parentFlight: State<SharedCoverFlightState?>
) {
    private data class Registration(val token: Any, val coordinates: LayoutCoordinates)
    private data class HeaderAnimation(
        val scale: State<Float>,
        val offsetY: State<Float>,
        val baseSizePx: Float
    )
    private data class HeaderPosition(val left: Float, val rowCenterY: Float)
    private data class Measurement(val token: Any, val bounds: Rect?)
    private data class BoundsAnimation(
        val state: State<Rect>,
        val navigating: Boolean,
        val cornerProgress: State<Float>?
    )

    private var root by mutableStateOf<Registration?>(null)
    private var cover: Registration? = null
    private var row: Registration? = null
    private var header: Registration? = null
    private var coverBounds by mutableStateOf<Rect?>(null)
    private var headerPosition by mutableStateOf<HeaderPosition?>(null)
    private var headerAnimation by mutableStateOf<HeaderAnimation?>(null)
    private var lastHeaderBounds by mutableStateOf<Rect?>(null)
    private var measurement by mutableStateOf<Measurement?>(null)
    private var boundsAnimation by mutableStateOf<BoundsAnimation?>(null)
    private var parentTarget: SharedCoverFlightState? = null
    private var coverCornerPx by mutableFloatStateOf(0f)
    private var lyricsCornerPx by mutableFloatStateOf(0f)
    private var continuousCoverCorners by mutableStateOf(true)
    private val parentFrameProvider: () -> CoverFlightFrame? = { parentFrame() }

    private val coverCornerProgress: Float
        get() = if (active) {
            boundsAnimation?.takeIf { it.navigating }?.cornerProgress?.value
                ?.coerceIn(0f, 1f) ?: if (transition.currentState) 0f else 1f
        } else if (transition.currentState) 0f else 1f

    internal val cornerPx: Float
        get() = lyricsCornerPx + (coverCornerPx - lyricsCornerPx) * coverCornerProgress

    internal fun cornerSmoothing(width: Float, height: Float): Float =
        if (continuousCoverCorners && supportsContinuousCornerOutline(width, height)) {
            coverCornerProgress
        } else 0f

    internal val hasGeometry: Boolean
        get() = coverBounds != null || lyricsBounds() != null

    internal val bounds: Rect?
        get() {
            val source = settledBounds()
            if (!active) return source
            val animation = boundsAnimation ?: return source
            if (!animation.navigating) return source
            val animatedBounds = animation.state.value
            return if (animatedBounds.isUsable()) animatedBounds else source
        }

    private val active: Boolean
        get() = transition.currentState != transition.targetState

    internal val shouldDraw: Boolean
        get() {
            if (!active) return false
            if (attachedCoordinates(root) == null) return false
            if (measuredBounds() == null) return false
            return !parentOwnsCover
        }

    internal val hidesEndpoints: Boolean
        get() = parentOwnsCover || shouldDraw

    private val parentOwnsCover: Boolean
        get() = parentFlight.value?.hidesCovers == true

    private fun settledBounds(): Rect? = targetBounds(transition.currentState).takeIf(Rect::isUsable)

    private fun measuredBounds(): Rect? {
        val owner = root ?: return null
        val current = measurement ?: return null
        return if (current.token === owner.token) current.bounds else null
    }

    internal fun targetBounds(lyrics: Boolean): Rect {
        val headerBounds = lyricsBounds()
        return if (lyrics) headerBounds ?: coverBounds ?: Rect.Zero
        else coverBounds ?: headerBounds ?: Rect.Zero
    }

    internal fun bindAnimation(
        bounds: State<Rect>,
        navigating: Boolean,
        cornerProgress: State<Float>? = null
    ) {
        boundsAnimation = BoundsAnimation(bounds, navigating, cornerProgress)
    }

    internal fun reportMeasurement(token: Any, bounds: Rect?) {
        measurement = Measurement(token, bounds)
    }

    internal fun reportRoot(
        token: Any,
        coordinates: LayoutCoordinates,
        coverCornerPx: Float,
        lyricsCornerPx: Float,
        continuousCoverCorners: Boolean
    ) {
        if (!coordinates.isAttached) return
        val previousRoot = root
        val parent = parentFlight.value
        val previousParent = parentTarget
        if (previousRoot != null && (previousParent !== parent || previousRoot.token !== token)) {
            previousParent?.detachVisualTarget(previousRoot.token)
        }
        root = Registration(token, coordinates)
        this.coverCornerPx = coverCornerPx
        this.lyricsCornerPx = lyricsCornerPx
        this.continuousCoverCorners = continuousCoverCorners
        updateCoverBounds()
        updateHeaderPosition()
        parentTarget = parent
        parent?.reportVisualTarget(token, coordinates, parentFrameProvider)
    }

    internal fun detachRoot(token: Any) {
        if (root?.token !== token) return
        parentTarget?.detachVisualTarget(token)
        parentTarget = null
        root = null
    }

    internal fun reportCover(token: Any, coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        cover = Registration(token, coordinates)
        updateCoverBounds()
    }

    internal fun detachCover(token: Any) {
        if (cover?.token === token) cover = null
    }

    internal fun reportRow(token: Any, coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        row = Registration(token, coordinates)
        updateHeaderPosition()
    }

    internal fun detachRow(token: Any) {
        if (row?.token !== token) return
        freezeHeaderBounds()
        row = null
    }

    internal fun reportHeader(
        token: Any,
        coordinates: LayoutCoordinates,
        scale: State<Float>,
        offsetY: State<Float>,
        baseSizePx: Float
    ) {
        if (!coordinates.isAttached) return
        header = Registration(token, coordinates)
        headerAnimation = HeaderAnimation(scale, offsetY, baseSizePx)
        updateHeaderPosition()
    }

    internal fun detachHeader(token: Any) {
        if (header?.token !== token) return
        freezeHeaderBounds()
        header = null
    }

    private fun updateCoverBounds() {
        // 端点保持原布局尺寸，缓存根布局内的坐标，外部容器缩放不会再次乘入飞行路径
        relativeBounds(cover)?.takeIf(Rect::isUsable)?.let { coverBounds = it }
    }

    private fun updateHeaderPosition() {
        val rowBounds = relativeBounds(row) ?: return
        val headerBounds = relativeBounds(header) ?: return
        headerPosition = HeaderPosition(headerBounds.left, rowBounds.center.y)
        lyricsBounds()?.let { lastHeaderBounds = it }
    }

    private fun relativeBounds(registration: Registration?): Rect? {
        val rootCoordinates = attachedCoordinates(root) ?: return null
        val coordinates = attachedCoordinates(registration) ?: return null
        if (rootCoordinates.findRootCoordinates() !== coordinates.findRootCoordinates()) return null
        return rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    private fun attachedCoordinates(registration: Registration?): LayoutCoordinates? {
        val current = registration ?: return null
        return if (current.coordinates.isAttached) current.coordinates else null
    }

    private fun lyricsBounds(): Rect? {
        val position = headerPosition ?: return lastHeaderBounds
        val animation = headerAnimation ?: return lastHeaderBounds
        // graphicsLayer 位移不会触发布局回调，目标直接观察原来的两个 spring 状态
        val size = animation.baseSizePx * animation.scale.value
        val top = position.rowCenterY - size / 2f + animation.offsetY.value
        return Rect(position.left, top, position.left + size, top + size)
            .takeIf(Rect::isUsable) ?: lastHeaderBounds
    }

    private fun freezeHeaderBounds() {
        lyricsBounds()?.let { lastHeaderBounds = it }
        headerPosition = null
        headerAnimation = null
    }

    private fun parentFrame(): CoverFlightFrame? {
        val frameBounds = if (active) measuredBounds() else settledBounds()
        val bounds = frameBounds ?: return null
        val lyrics = transition.targetState
        return CoverFlightFrame(
            bounds,
            cornerPx,
            continuousCorners = !lyrics && continuousCoverCorners &&
                supportsContinuousCornerOutline(bounds.width, bounds.height),
            cornerSmoothing = cornerSmoothing(bounds.width, bounds.height)
        )
    }
}

@Composable
internal fun rememberNowPlayingLyricsCoverFlight(
    transition: Transition<Boolean>,
    predictiveBackPreviewActive: Boolean = false
): NowPlayingLyricsCoverFlight {
    val parentFlight = rememberUpdatedState(LocalSharedCoverFlight.current)
    val flight = remember(transition) { NowPlayingLyricsCoverFlight(transition, parentFlight) }
    // 坐标尚未知时不注册零尺寸动画，第一份有效端点就是初始几何
    if (flight.hasGeometry) {
        BindNowPlayingLyricsCoverFlightAnimations(transition, flight, predictiveBackPreviewActive)
    }
    return flight
}

@Composable
private fun BindNowPlayingLyricsCoverFlightAnimations(
    transition: Transition<Boolean>,
    flight: NowPlayingLyricsCoverFlight,
    predictiveBackPreviewActive: Boolean
) {
    val navigating = transition.currentState != transition.targetState
    // 静止宿主不推进动画值，下一轮从当前布局重新起步，同轮反向保留原来的动画
    key(navigating) {
        val specs = coverFlightAnimationSpecs(navigating, predictiveBackPreviewActive)
        val bounds = transition.animateRect(
            transitionSpec = { specs.bounds },
            label = "lyrics_cover_bounds"
        ) { lyrics -> flight.targetBounds(lyrics) }
        val cornerProgress = transition.animateFloat(
            transitionSpec = { specs.cornerProgress },
            label = "lyrics_cover_corner_progress"
        ) { lyrics -> if (lyrics) 0f else 1f }
        flight.bindAnimation(bounds, navigating, cornerProgress)
    }
}

@Composable
internal fun Modifier.nowPlayingCoverFlightEndpoint(
    flight: NowPlayingLyricsCoverFlight
): Modifier {
    val token = remember(flight) { Any() }
    DisposableEffect(flight, token) {
        onDispose { flight.detachCover(token) }
    }
    return onGloballyPositioned { flight.reportCover(token, it) }
        .drawWithContent { if (!flight.hidesEndpoints) drawContent() }
}

@Composable
internal fun Modifier.nowPlayingLyricsCoverFlightRow(
    flight: NowPlayingLyricsCoverFlight
): Modifier {
    val token = remember(flight) { Any() }
    DisposableEffect(flight, token) {
        onDispose { flight.detachRow(token) }
    }
    return onGloballyPositioned { flight.reportRow(token, it) }
}

@Composable
internal fun Modifier.nowPlayingLyricsCoverFlightEndpoint(
    flight: NowPlayingLyricsCoverFlight,
    scale: State<Float>,
    offsetY: State<Float>,
    baseSize: Dp = 64.dp
): Modifier {
    val token = remember(flight) { Any() }
    val sizePx = with(LocalDensity.current) { baseSize.toPx() }
    DisposableEffect(flight, token) {
        onDispose { flight.detachHeader(token) }
    }
    return onGloballyPositioned {
        flight.reportHeader(token, it, scale, offsetY, sizePx)
    }.drawWithContent { if (!flight.hidesEndpoints) drawContent() }
}

@Composable
internal fun NowPlayingLyricsCoverFlightLayer(
    flight: NowPlayingLyricsCoverFlight,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val token = remember(flight) { Any() }
    val clean = NeriDesign.isClean
    val density = LocalDensity.current
    val coverCornerPx = with(density) { 24.dp.toPx() }
    val lyricsCornerPx = with(density) { 10.dp.toPx() }
    val coverClipPath = remember(flight) { Path() }
    DisposableEffect(flight, token) {
        onDispose { flight.detachRoot(token) }
    }
    Layout(
        modifier = modifier.fillMaxSize()
            .onGloballyPositioned {
                flight.reportRoot(token, it, coverCornerPx, lyricsCornerPx, clean)
            }
            .clearAndSetSemantics { }
            .drawWithContent { if (flight.shouldDraw) drawContent() },
        content = {
            content(Modifier.fillMaxSize().drawWithContent {
                // 同一份连续圆角同时用于绘制和父级交接，反向不能直接套用目标角型
                coverClipPath.reset()
                coverClipPath.addSmoothedRoundRect(
                    size.width, size.height, flight.cornerPx,
                    flight.cornerSmoothing(size.width, size.height)
                )
                clipPath(coverClipPath) { this@drawWithContent.drawContent() }
            })
        }
    ) { measurables, constraints ->
        // 同一次测量和放置使用同一份矩形，不能在两阶段分别读取正在变化的动画
        val bounds = flight.bounds
        flight.reportMeasurement(token, bounds)
        val width = bounds?.width?.roundToInt()?.coerceAtLeast(1) ?: 1
        val height = bounds?.height?.roundToInt()?.coerceAtLeast(1) ?: 1
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (bounds != null) {
                placeables.forEach { it.place(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            }
        }
    }
}

private fun Rect.isUsable(): Boolean = isFinite && !isEmpty
