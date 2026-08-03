package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val SongTitleBounceInitialDelayMillis = 850L
private const val SongTitleBounceEndpointDelayMillis = 850L
private const val SongTitleBounceMinTravelDurationMillis = 900
private const val SongTitleBounceMaxTravelDurationMillis = 5_000
private const val SongTitleBounceVelocityDpPerSecond = 36f
private val SongTitleEdgeFadeWidth = 12.dp

@Composable
internal fun NowPlayingSongTitle(
    text: String,
    marqueeEnabled: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    if (!marqueeEnabled) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    BouncingSongTitle(
        text = text,
        style = style,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun BouncingSongTitle(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier
) {
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val bounceDistancePx = songTitleBounceDistancePx(
        marqueeEnabled = true,
        contentWidthPx = contentWidthPx,
        viewportWidthPx = viewportWidthPx
    )
    val shouldBounce = bounceDistancePx > 0
    val scrollOffset = remember(text) { Animatable(0f) }
    val scrollVelocityPxPerSecond = with(LocalDensity.current) {
        SongTitleBounceVelocityDpPerSecond.dp.toPx()
    }

    LaunchedEffect(text, bounceDistancePx, scrollVelocityPxPerSecond) {
        scrollOffset.snapTo(0f)
        if (!shouldBounce) return@LaunchedEffect

        val travelDurationMillis = songTitleBounceTravelDurationMillis(
            distancePx = bounceDistancePx,
            pixelsPerSecond = scrollVelocityPxPerSecond
        )
        while (isActive) {
            delay(SongTitleBounceInitialDelayMillis)
            scrollOffset.animateTo(
                targetValue = bounceDistancePx.toFloat(),
                animationSpec = tween(
                    durationMillis = travelDurationMillis,
                    easing = FastOutSlowInEasing
                )
            )
            delay(SongTitleBounceEndpointDelayMillis)
            scrollOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = travelDurationMillis,
                    easing = FastOutSlowInEasing
                )
            )
            delay(SongTitleBounceEndpointDelayMillis)
        }
    }

    Layout(
        modifier = modifier
            .onSizeChanged { viewportWidthPx = it.width }
            .clipToBounds()
            .then(
                if (shouldBounce) {
                    Modifier.horizontalTitleEdgeFade(SongTitleEdgeFadeWidth)
                } else {
                    Modifier
                }
            ),
        content = {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { contentWidthPx = it.size.width },
                modifier = Modifier.graphicsLayer {
                    translationX = -scrollOffset.value
                }
            )
        }
    ) { measurables, constraints ->
        val title = measurables.single().measure(
            constraints.copy(
                minWidth = 0,
                maxWidth = Constraints.Infinity
            )
        )
        val width = title.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = title.height.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            title.placeRelative(0, 0)
        }
    }
}

private fun Modifier.horizontalTitleEdgeFade(fadeWidth: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (fadeWidth.toPx() / size.width).coerceIn(0f, 0.22f)
        if (edge <= 0f) return@drawWithContent

        val brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Transparent
            ),
            startX = 0f,
            endX = size.width
        )
        drawRect(
            brush = brush,
            size = size,
            blendMode = BlendMode.DstIn
        )
    }

internal fun songTitleBounceDistancePx(
    marqueeEnabled: Boolean,
    contentWidthPx: Int,
    viewportWidthPx: Int
): Int {
    if (!marqueeEnabled || contentWidthPx <= 0 || viewportWidthPx <= 0) return 0
    return (contentWidthPx - viewportWidthPx).coerceAtLeast(0)
}

internal fun songTitleBounceTravelDurationMillis(
    distancePx: Int,
    pixelsPerSecond: Float
): Int {
    if (distancePx <= 0 || pixelsPerSecond <= 0f) {
        return SongTitleBounceMinTravelDurationMillis
    }
    return (distancePx / pixelsPerSecond * 1_000f)
        .roundToInt()
        .coerceIn(
            SongTitleBounceMinTravelDurationMillis,
            SongTitleBounceMaxTravelDurationMillis
        )
}
