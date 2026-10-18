package moe.ouom.neriplayer.ui.playback.visual

import androidx.compose.ui.graphics.isSpecified
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.theme.addContinuousRoundRect
import kotlin.math.max
import kotlin.math.roundToInt

private const val MorphOpenDamping = 0.9f
private const val MorphOpenStiffness = 260f
private const val MorphCloseDurationMillis = 250
private const val MorphSettleFrames = 2
/** On close the card dissolves into the returning mini player instead of covering it. */
private const val CloseCardFadeSpan = 0.2f
/** On open the card fades in over the capsule so the mini player content cross-fades away. */
private const val OpenCardFadeSpan = 0.14f
/** The card keeps the capsule's own color at first and only takes the page tint as it grows. */
private const val CardColorSpan = 0.5f
private const val MorphScrimAlpha = 0.16f
/** Page content fades in over this slice of the morph; the card and artwork lead. */
private const val ContentFadeStart = 0.06f
private const val ContentFadeEnd = 0.42f
private val MorphCardColor = Color(0xFF19191C)
/** How far a back gesture previews the close morph before the user commits. */
private const val MorphBackPreviewDepth = 0.4f

@Stable
internal class NowPlayingMorphState(initiallyVisible: Boolean) {
    val progress = Animatable(if (initiallyVisible) 1f else 0f)
    var mounted by mutableStateOf(initiallyVisible)
        internal set
    var closing by mutableStateOf(false)
        internal set
}

/**
 * Mounts the page on open and keeps it until the close morph lands. The open spring waits two
 * frames so the page's first (heavy) composition is drawn before the clock starts. While the
 * morph runs the shared artwork is handed to [NowPlayingContainerMorph] via [flight].
 */
@Composable
internal fun rememberNowPlayingMorphState(
    visible: Boolean,
    flight: SharedCoverFlightState?,
    backProgress: () -> Float = { 0f }
): NowPlayingMorphState {
    val state = remember { NowPlayingMorphState(initiallyVisible = visible) }
    val dragging = flight?.dragOpenProgress != null
    LaunchedEffect(visible, dragging) {
        val linked = flight
        if (dragging && !visible) {
            // the finger owns the morph; release hands over to the open or close branch
            state.closing = false
            state.mounted = true
            linked.beginLinked()
            snapshotFlow { linked.dragOpenProgress }.collect { dragProgress ->
                if (dragProgress != null) state.progress.snapTo(dragProgress.coerceIn(0f, 1f))
            }
        } else if (visible) {
            state.closing = false
            state.mounted = true
            if (state.progress.value < 1f) {
                linked?.beginLinked()
                if (state.progress.value <= 0f) repeat(MorphSettleFrames) { withFrameNanos { } }
                state.progress.animateTo(1f, spring(MorphOpenDamping, MorphOpenStiffness, 0.001f))
            }
            linked?.endLinked()
            // a back gesture scrubs the close morph; commit continues it, cancel springs back
            snapshotFlow { backProgress().coerceIn(0f, 1f) }.collect { back ->
                if (back > 0f) linked?.beginLinked() else linked?.endLinked()
                state.progress.snapTo(1f - back * MorphBackPreviewDepth)
            }
        } else if (state.mounted) {
            state.closing = true
            linked?.beginLinked()
            state.progress.animateTo(0f, tween(MorphCloseDurationMillis, easing = FastOutSlowInEasing))
            state.mounted = false
            linked?.endLinked()
        }
    }
    DisposableEffect(flight) {
        onDispose { flight?.endLinked() }
    }
    return state
}

/**
 * Container transform from the mini player capsule to the full page, after the reference
 * motion: an opaque card grows out of the capsule, the page rides inside it scaled to the
 * card width and top-aligned, and the artwork flies on its own from the mini cover to the
 * page cover's live position so it stays crisp and continuous the whole way.
 */
@Composable
internal fun NowPlayingContainerMorph(
    morph: NowPlayingMorphState,
    flight: SharedCoverFlightState?,
    capsuleInWindow: () -> Rect?,
    artwork: () -> ImageBitmap?,
    cardColor: Color = MorphCardColor,
    content: @Composable () -> Unit
) {
    val progress = morph.progress
    val startColor = { flight?.miniPlayerContainerColor ?: Color.Unspecified }
    LaunchedEffect(flight) {
        snapshotFlow { progress.value }.collect { flight?.nowPlayingMorphProgress = it }
    }
    DisposableEffect(flight) {
        onDispose { flight?.nowPlayingMorphProgress = 0f }
    }
    val density = LocalDensity.current
    val capsuleCornerPx = with(density) { 22.dp.toPx() }
    val cardCornerPx = with(density) { 40.dp.toPx() }
    val miniCoverCornerPx = with(density) { 10.dp.toPx() }
    val pageCoverCornerPx = with(density) { 24.dp.toPx() }
    val geometry = remember { MorphGeometry() }
    val coverPath = remember { Path() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { geometry.origin = it.positionInWindow() }
            .drawBehind {
                val p = progress.value.coerceIn(0f, 1f)
                if (p >= 0.999f) return@drawBehind
                val scrim = MorphScrimAlpha * p
                if (scrim > 0f) drawRect(Color.Black.copy(alpha = scrim))
                val card = geometry.card(p, size, capsuleInWindow())
                val corner = morphCorner(p, capsuleCornerPx, cardCornerPx)
                val fadeSpan = if (morph.closing) CloseCardFadeSpan else OpenCardFadeSpan
                val cardAlpha = smoothStep(p / fadeSpan)
                val color = startColor().let { start ->
                    if (start.isSpecified) {
                        androidx.compose.ui.graphics.lerp(start.copy(alpha = 1f), cardColor, smoothStep(p / CardColorSpan))
                    } else {
                        cardColor
                    }
                }
                drawRoundRect(
                    color = color.copy(alpha = cardAlpha),
                    topLeft = card.topLeft,
                    size = card.size,
                    cornerRadius = CornerRadius(corner)
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    if (p >= 0.999f) {
                        geometry.scale = 1f
                        geometry.translation = Offset.Zero
                        return@graphicsLayer
                    }
                    val card = geometry.card(p, size, capsuleInWindow())
                    val scale = (card.width / size.width).coerceAtLeast(0.01f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = card.left
                    translationY = card.top
                    alpha = ((p - ContentFadeStart) / (ContentFadeEnd - ContentFadeStart)).coerceIn(0f, 1f)
                    shape = MorphCardShape(
                        bottom = card.height / scale,
                        cornerPx = morphCorner(p, capsuleCornerPx, cardCornerPx) / scale
                    )
                    clip = true
                    geometry.scale = scale
                    geometry.translation = Offset(card.left, card.top)
                }
                .onPlaced { geometry.content = it }
        ) {
            content()
        }
        Canvas(Modifier.fillMaxSize()) {
            if (flight?.linked != true || !flight.hidesCovers) return@Canvas
            val p = progress.value.coerceIn(0f, 1f)
            val bitmap = artwork() ?: return@Canvas
            val from = flight.miniCoverBounds?.translate(-geometry.origin) ?: return@Canvas
            val to = geometry.pageCover(flight.targetCoordinates) ?: return@Canvas
            val rect = lerp(from, to, p)
            if (rect.width <= 1f || rect.height <= 1f) return@Canvas
            val corner = lerp(miniCoverCornerPx, pageCoverCornerPx * geometry.scale, p)
            coverPath.reset()
            coverPath.addContinuousRoundRect(rect.width, rect.height, corner, corner, corner, corner)
            translate(rect.left, rect.top) {
                clipPath(coverPath) {
                    val (srcOffset, srcSize) = centerCrop(bitmap, rect.size)
                    drawImage(
                        image = bitmap,
                        srcOffset = srcOffset,
                        srcSize = srcSize,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
                    )
                }
            }
        }
    }
}

private const val CornerTaperStart = 0.8f

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Grows from the capsule radius, then tapers to square so the hand-off to the page is seamless. */
private fun morphCorner(p: Float, capsulePx: Float, cardPx: Float): Float =
    if (p < CornerTaperStart) {
        lerp(capsulePx, cardPx, p / CornerTaperStart)
    } else {
        lerp(cardPx, 0f, (p - CornerTaperStart) / (1f - CornerTaperStart))
    }

/** Card tint from the cover seed, kept dark so white chrome and text read on it immediately. */
internal fun nowPlayingMorphCardColor(seedHex: String?): Color {
    val seed = seedHex?.removePrefix("#")?.toLongOrNull(16) ?: return MorphCardColor
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL((0xFF000000 or seed).toInt(), hsl)
    hsl[1] = hsl[1].coerceAtMost(0.42f)
    hsl[2] = 0.16f
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/** Shared per-frame geometry; plain fields because only draw and layer blocks read them. */
private class MorphGeometry {
    var origin: Offset = Offset.Zero
    var content: LayoutCoordinates? = null
    var scale: Float = 1f
    var translation: Offset = Offset.Zero

    fun card(p: Float, size: Size, capsuleInWindow: Rect?): Rect {
        val capsule = capsuleInWindow?.translate(-origin)
            // a recreated player has no capsule in this viewport, so fade without inventing one
            ?: Rect(Offset.Zero, size)
        return Rect(
            left = lerp(capsule.left, 0f, p),
            top = lerp(capsule.top, 0f, p),
            right = lerp(capsule.right, size.width, p),
            bottom = lerp(capsule.bottom, size.height, p)
        )
    }

    /** Page artwork bounds in this layer's space, following the content scale and translation. */
    fun pageCover(cover: LayoutCoordinates?): Rect? {
        val host = content ?: return null
        if (cover == null || !cover.isAttached || !host.isAttached) return null
        val local = host.localBoundingBoxOf(cover, clipBounds = false)
        return Rect(
            left = translation.x + local.left * scale,
            top = translation.y + local.top * scale,
            right = translation.x + local.right * scale,
            bottom = translation.y + local.bottom * scale
        )
    }
}

private class MorphCardShape(
    private val bottom: Float,
    private val cornerPx: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rect = Rect(0f, 0f, size.width, bottom.coerceIn(1f, size.height))
        val corner = cornerPx.coerceAtMost(minOf(rect.width, rect.height) / 2f)
        return Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
    }
}

private fun centerCrop(bitmap: ImageBitmap, dst: Size): Pair<IntOffset, IntSize> {
    val scale = max(dst.width / bitmap.width, dst.height / bitmap.height)
    val srcWidth = (dst.width / scale).roundToInt().coerceIn(1, bitmap.width)
    val srcHeight = (dst.height / scale).roundToInt().coerceIn(1, bitmap.height)
    return IntOffset((bitmap.width - srcWidth) / 2, (bitmap.height - srcHeight) / 2) to IntSize(srcWidth, srcHeight)
}

private fun lerp(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction)
)

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
