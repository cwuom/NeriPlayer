package moe.ouom.neriplayer.ui.animation

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.ui.theme.addSmoothedRoundRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TARGET_LAYOUT_WAIT_MS = 420L
private const val OPEN_DAMPING = 0.86f
private const val OPEN_STIFFNESS = 300f
private const val CLOSE_DAMPING = 0.92f
private const val CLOSE_STIFFNESS = 420f

/**
 * Continuous cover hand-off between the mini player and Now Playing.
 *
 * Both endpoints report window bounds without recomposing; while a flight is active the
 * real covers are hidden and a single bitmap is drawn by [SharedCoverFlightLayer], lerping
 * from the source to wherever the target currently is (it may still be sliding in).
 */
@Stable
class SharedCoverFlightState {
    private data class VisualTarget(
        val token: Any,
        val coordinates: LayoutCoordinates,
        val frame: () -> CoverFlightFrame?
    )

    private var sourceBounds: Rect? by mutableStateOf(null)
    private var sourceCornerPx = 0f
    private var sourceCornerSmoothing = 1f
    private var sourceBitmap: ImageBitmap? by mutableStateOf(null)
    private var targetBounds: Rect? = null
    private var targetCornerPx = 0f
    private var targetAttached = false
    private var visualTarget by mutableStateOf<VisualTarget?>(null)
    private var job: Job? = null
    private var followsMiniPlayerGeometry = false
    private var latestSourceBounds: Rect? = null
    private var latestContainerBounds: Rect? = null
    private var sourceBoundsCorrection: Rect? = null
    private var containerBoundsCorrection: Rect? = null
    private var correctionStartProgress = 0f
    private var containerBounds: Rect? by mutableStateOf(null)

    internal val progress = Animatable(0f)

    /** Window bounds of the whole mini player capsule; the clean style morphs Now Playing from it. */
    val miniPlayerContainerBounds: Rect?
        get() = correctedMiniBounds(containerBounds, containerBoundsCorrection)

    val miniCoverBounds: Rect?
        get() = correctedMiniBounds(sourceBounds, sourceBoundsCorrection)

    internal val miniCoverCornerPx: Float
        get() = sourceCornerPx

    internal val miniCoverCornerSmoothing: Float
        get() = sourceCornerSmoothing

    fun reportMiniPlayerContainer(bounds: Rect) {
        if (bounds.isEmpty) return
        latestContainerBounds = bounds
        if ((!linked && !flying) || (linked && followsMiniPlayerGeometry) || containerBounds == null) {
            containerBounds = bounds
        }
    }
    internal var flightBitmap: ImageBitmap? by mutableStateOf(null)
        private set
    var flying: Boolean by mutableStateOf(false)
        private set

    internal fun reportSource(
        bounds: Rect,
        cornerPx: Float,
        bitmap: ImageBitmap?,
        continuousCorners: Boolean = true
    ) {
        sourceBitmap = bitmap
        if (bounds.isEmpty) return
        latestSourceBounds = bounds
        // 展开保留静止起点，关闭时跟随重新入场的 Mini，避免交还时跳到另一个位置
        if ((!linked && !flying) || (linked && followsMiniPlayerGeometry) || sourceBounds == null) {
            sourceBounds = bounds
            sourceCornerPx = cornerPx
            sourceCornerSmoothing = if (continuousCorners) 1f else 0f
        }
    }

    internal fun clearArtwork() {
        sourceBitmap = null
        linkedBitmap = null
        flightBitmap = null
    }

    internal fun reportTarget(bounds: Rect, cornerPx: Float) {
        if (bounds.isEmpty) return
        targetBounds = bounds
        targetCornerPx = cornerPx
        targetAttached = true
    }

    internal fun detachTarget() {
        targetAttached = false
        targetCoordinates = null
    }

    internal fun reportVisualTarget(
        token: Any,
        coordinates: LayoutCoordinates,
        frame: () -> CoverFlightFrame?
    ) {
        if (coordinates.isAttached) visualTarget = VisualTarget(token, coordinates, frame)
    }

    internal fun detachVisualTarget(token: Any) {
        if (visualTarget?.token === token) visualTarget = null
    }

    private fun hasVisualTarget(): Boolean = visualTarget?.let {
        it.coordinates.isAttached && it.frame()?.bounds?.isFiniteBounds() == true
    } == true

    internal fun targetFrameIn(coordinates: LayoutCoordinates): CoverFlightFrame? {
        visualTargetFrame(coordinates)?.let { return it }
        val target = targetCoordinates ?: return null
        if (!coordinates.isAttached || !target.isAttached ||
            coordinates.findRootCoordinates() !== target.findRootCoordinates()
        ) return null
        val bounds = coordinates.localBoundingBoxOf(target, clipBounds = false)
        return bounds.takeIf(Rect::isFiniteBounds)?.let { CoverFlightFrame(it, targetCornerPx) }
    }

    private fun visualTargetFrame(destination: LayoutCoordinates? = null): CoverFlightFrame? {
        val target = visualTarget ?: return null
        val owner = target.coordinates
        if (!owner.isAttached) return null
        if (destination != null && (!destination.isAttached ||
                destination.findRootCoordinates() !== owner.findRootCoordinates())
        ) return null
        val frame = target.frame() ?: return null
        if (!frame.bounds.isFiniteBounds()) return null
        fun map(point: Offset): Offset = if (destination == null) owner.localToWindow(point)
            else destination.localPositionOf(owner, point)
        val a = map(frame.bounds.topLeft)
        val b = map(frame.bounds.topRight)
        val c = map(frame.bounds.bottomLeft)
        val d = map(frame.bounds.bottomRight)
        val bounds = Rect(
            minOf(a.x, b.x, c.x, d.x), minOf(a.y, b.y, c.y, d.y),
            maxOf(a.x, b.x, c.x, d.x), maxOf(a.y, b.y, c.y, d.y)
        )
        if (!bounds.isFiniteBounds()) return null
        val scale = min(bounds.width / frame.bounds.width, bounds.height / frame.bounds.height)
        return frame.copy(bounds = bounds, cornerPx = frame.cornerPx * scale)
    }

    /** Live layout coordinates of the Now Playing artwork, for transforms that move it. */
    var targetCoordinates: LayoutCoordinates? by mutableStateOf(null)
        internal set

    val miniCoverBitmap: ImageBitmap?
        get() = sourceBitmap

    /**
     * Hands progress to an external driver (the clean container morph): covers stay hidden
     * and the driver draws the artwork itself until [endLinked].
     */
    /** Upward drag on the mini player, 0..1; non-null while the finger is down. */
    var dragOpenProgress: Float? by mutableStateOf(null)

    /** Resting fill of the mini player capsule, so the morph card can start from the same color. */
    var miniPlayerContainerColor: Color by mutableStateOf(Color.Unspecified)

    /** Clean container morph progress (0 closed, 1 open); the page underneath recedes with it. */
    var nowPlayingMorphProgress: Float by mutableFloatStateOf(0f)

    // 绘制门控直接观察动画状态，不能用异步同步的进度判断是否已经完整遮挡
    internal var nowPlayingMorphProgressState: State<Float>? by mutableStateOf(null)

    /** Set while the clean container morph draws the artwork itself. */
    var linked: Boolean by mutableStateOf(false)
        private set

    internal var linkedBitmap: ImageBitmap? by mutableStateOf(null)
        private set

    /** 替代封面的位图和两端坐标齐备后才隐藏真实封面 */
    val hidesCovers: Boolean
        get() = (flying && flightBitmap != null) || (linked && linkedBitmap != null && sourceBounds != null &&
            (hasVisualTarget() || targetCoordinates?.isAttached == true))

    fun beginLinked(
        preferredBitmap: ImageBitmap? = null,
        followMiniPlayerGeometry: Boolean = false
    ) {
        updateMiniPlayerGeometryMode(followMiniPlayerGeometry)
        if (linked) return
        // a Material flight must not keep painting its bitmap under the morph
        job?.cancel()
        flying = false
        // 固定本轮接管条件，避免图片回调在动画中途切换真实封面与替代封面
        linkedBitmap = preferredBitmap ?: sourceBitmap
        linked = true
    }

    fun endLinked() {
        linked = false
        linkedBitmap = null
        followsMiniPlayerGeometry = false
        clearMiniBoundsCorrection()
    }

    private fun updateMiniPlayerGeometryMode(follow: Boolean) {
        if (followsMiniPlayerGeometry == follow) return
        if (linked) {
            if (follow) followLatestMiniBounds() else freezeEffectiveMiniBounds()
        }
        followsMiniPlayerGeometry = follow
    }

    private fun followLatestMiniBounds() {
        clearMiniBoundsCorrection()
        val p = nowPlayingMorphProgressState?.value ?: nowPlayingMorphProgress
        if (p > 0f && p < 1f) {
            correctionStartProgress = p
            sourceBoundsCorrection = miniBoundsDifference(sourceBounds, latestSourceBounds)
            containerBoundsCorrection = miniBoundsDifference(containerBounds, latestContainerBounds)
        }
        sourceBounds = latestSourceBounds ?: sourceBounds
        containerBounds = latestContainerBounds ?: containerBounds
    }

    private fun freezeEffectiveMiniBounds() {
        sourceBounds = miniCoverBounds
        containerBounds = miniPlayerContainerBounds
        clearMiniBoundsCorrection()
    }

    private fun clearMiniBoundsCorrection() {
        sourceBoundsCorrection = null
        containerBoundsCorrection = null
        correctionStartProgress = 0f
    }

    private fun miniBoundsDifference(frozen: Rect?, live: Rect?): Rect? {
        if (frozen == null || live == null || frozen == live) return null
        return Rect(frozen.left - live.left, frozen.top - live.top,
            frozen.right - live.right, frozen.bottom - live.bottom)
    }

    private fun correctedMiniBounds(bounds: Rect?, correction: Rect?): Rect? {
        if (bounds == null || correction == null) return bounds
        // 反向先保留已画出的端点，再随剩余关闭进度交还实时 Mini 几何
        val p = nowPlayingMorphProgressState?.value ?: nowPlayingMorphProgress
        val remaining = (p / correctionStartProgress).coerceIn(0f, 1f)
        if (remaining == 0f) return bounds
        return Rect(bounds.left + correction.left * remaining, bounds.top + correction.top * remaining,
            bounds.right + correction.right * remaining, bounds.bottom + correction.bottom * remaining)
    }

    fun open(scope: CoroutineScope, preferredBitmap: ImageBitmap? = null) {
        val bitmap = preferredBitmap ?: sourceBitmap ?: return
        if (sourceBounds == null) return
        job?.cancel()
        flightBitmap = bitmap
        flying = true
        targetAttached = false
        job = scope.launch {
            try {
                progress.snapTo(0f)
                val ready = withTimeoutOrNull(TARGET_LAYOUT_WAIT_MS) {
                    while (!targetAttached && !hasVisualTarget()) withFrameNanos { }
                    true
                } == true
                if (!ready) return@launch
                progress.animateTo(1f, spring(OPEN_DAMPING, OPEN_STIFFNESS, 0.001f))
            } finally {
                flying = false
            }
        }
    }

    fun close(scope: CoroutineScope, preferredBitmap: ImageBitmap? = null) {
        if ((!targetAttached && !hasVisualTarget()) || sourceBounds == null) return
        val bitmap = preferredBitmap ?: sourceBitmap ?: return
        job?.cancel()
        flightBitmap = bitmap
        flying = true
        job = scope.launch {
            try {
                if (progress.value < 0.5f) progress.snapTo(1f)
                progress.animateTo(0f, spring(CLOSE_DAMPING, CLOSE_STIFFNESS, 0.001f))
            } finally {
                flying = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        flying = false
    }

    internal fun currentFrame(): CoverFlightFrame? {
        val source = sourceBounds ?: return null
        val targetFrame = visualTargetFrame()
            ?: targetBounds?.let { CoverFlightFrame(it, targetCornerPx) } ?: return null
        val fraction = progress.value
        return CoverFlightFrame(
            bounds = lerp(source, targetFrame.bounds, fraction),
            cornerPx = sourceCornerPx + (targetFrame.cornerPx - sourceCornerPx) * fraction,
            continuousCorners = targetFrame.continuousCorners,
            cornerSmoothing = sourceCornerSmoothing +
                (targetFrame.cornerSmoothing - sourceCornerSmoothing) * fraction
        )
    }
}

internal data class CoverFlightFrame(
    val bounds: Rect,
    val cornerPx: Float,
    val continuousCorners: Boolean = true,
    val cornerSmoothing: Float = if (continuousCorners) 1f else 0f
)

private fun Rect.isFiniteBounds(): Boolean = isFinite && !isEmpty

val LocalSharedCoverFlight = staticCompositionLocalOf<SharedCoverFlightState?> { null }

private const val DragOpenProgressThreshold = 0.3f
private const val DragOpenFlingVelocity = -900f
private const val DragCancelFlingVelocity = 600f

/** 松手速度使用 dp/s，避免相同手势随屏幕密度改变展开判定 */
fun shouldOpenNowPlayingFromDrag(progress: Float, velocityY: Float): Boolean = when {
    velocityY > DragCancelFlingVelocity -> false
    velocityY < DragOpenFlingVelocity && progress > 0.04f -> true
    else -> progress > DragOpenProgressThreshold
}

private fun lerp(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    offset = lerp(start.topLeft, stop.topLeft, fraction),
    size = Size(
        width = start.width + (stop.width - start.width) * fraction,
        height = start.height + (stop.height - start.height) * fraction
    )
)

/** Marks the mini player cover as the flight origin and hides it while a flight runs. */
fun Modifier.sharedCoverSource(
    cornerRadius: Dp,
    bitmap: ImageBitmap?,
    continuousCorners: Boolean = true
): Modifier = composed {
    val state = LocalSharedCoverFlight.current ?: return@composed this
    val cornerPx = with(LocalDensity.current) { cornerRadius.toPx() }
    this
        .onGloballyPositioned { coordinates ->
            state.reportSource(coordinates.boundsInWindow(), cornerPx, bitmap, continuousCorners)
        }
        .graphicsLayer { alpha = if (state.hidesCovers) 0f else 1f }
}

/** Marks the Now Playing cover as the flight destination and hides it while a flight runs. */
fun Modifier.sharedCoverTarget(cornerRadius: Dp): Modifier = composed {
    val state = LocalSharedCoverFlight.current ?: return@composed this
    val cornerPx = with(LocalDensity.current) { cornerRadius.toPx() }
    DisposableEffect(state) {
        onDispose { state.detachTarget() }
    }
    this
        .onGloballyPositioned { coordinates ->
            state.targetCoordinates = coordinates
            state.reportTarget(coordinates.boundsInWindow(), cornerPx)
        }
        .graphicsLayer { alpha = if (state.hidesCovers) 0f else 1f }
}

@Composable
fun SharedCoverFlightLayer(state: SharedCoverFlightState) {
    if (!state.flying || state.linked) return
    val bitmap = state.flightBitmap ?: return
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val path = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInWindow() }
    ) {
        val frame = state.currentFrame() ?: return@Canvas
        val bounds = frame.bounds.translate(-layerOrigin)
        if (bounds.width <= 1f || bounds.height <= 1f) return@Canvas
        path.reset()
        path.addSmoothedRoundRect(bounds.width, bounds.height, frame.cornerPx, frame.cornerSmoothing)
        translate(bounds.left, bounds.top) {
            clipPath(path) {
                val src = centerCropSource(bitmap.width, bitmap.height, bounds.width, bounds.height)
                drawImage(
                    image = bitmap,
                    srcOffset = src.first,
                    srcSize = src.second,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        bounds.width.roundToInt().coerceAtLeast(1),
                        bounds.height.roundToInt().coerceAtLeast(1)
                    )
                )
            }
        }
    }
}

private fun centerCropSource(
    bitmapWidth: Int,
    bitmapHeight: Int,
    dstWidth: Float,
    dstHeight: Float
): Pair<IntOffset, IntSize> {
    val scale = max(dstWidth / bitmapWidth, dstHeight / bitmapHeight)
    val srcWidth = (dstWidth / scale).roundToInt().coerceIn(1, bitmapWidth)
    val srcHeight = (dstHeight / scale).roundToInt().coerceIn(1, bitmapHeight)
    return IntOffset((bitmapWidth - srcWidth) / 2, (bitmapHeight - srcHeight) / 2) to
        IntSize(srcWidth, srcHeight)
}
