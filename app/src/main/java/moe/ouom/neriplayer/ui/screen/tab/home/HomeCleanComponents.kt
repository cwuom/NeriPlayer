package moe.ouom.neriplayer.ui.screen.tab.home

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size as CoilSize
import coil.transform.Transformation
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.common.NeriSearchFieldGlass
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import kotlin.math.max
import kotlin.math.roundToInt

/** Opens Explore's search from the home header; null where no navigation host is available. */
val LocalHomeOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

private val FeaturedLabelHeight = 54.dp
private const val FeaturedLabelScrimAlpha = 0.32f
internal const val HomeFeaturedBackdropMaxSizePx = 64
internal const val HomeFeaturedBackdropBlurRadiusPx = 5
private const val HomeFeaturedBackdropBlurPasses = 3

/** Clean-style search entry pinned under the home title. */
@Composable
internal fun HomeSearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeriSearchFieldGlass(modifier = modifier.fillMaxWidth().height(46.dp), shape = CircleShape) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stringResource(CoreCommonR.string.search_keyword),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    }
}

/**
 * Builds a low-resolution, statically pre-blurred Coil request for the featured card label band.
 * Keeps the default disk cache key so it reuses the main cover's downloaded bytes while storing
 * its tiny blurred bitmap under a separate memory cache key via [HomeFeaturedBackdropBlurTransformation].
 */
internal fun homeFeaturedLabelBackdropRequest(
    context: Context,
    data: Any?,
    offlineMode: Boolean
): ImageRequest {
    val remoteSource = isRemoteImageSource(data)
    return ImageRequest.Builder(context)
        .data(data)
        .size(HomeFeaturedBackdropMaxSizePx)
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(if (offlineMode && remoteSource) CachePolicy.DISABLED else CachePolicy.ENABLED)
        .transformations(HomeFeaturedBackdropBlurTransformation)
        .crossfade(false)
        .build()
}

internal object HomeFeaturedBackdropBlurTransformation : Transformation {
    override val cacheKey: String =
        "home_featured_backdrop_blur:s=$HomeFeaturedBackdropMaxSizePx:" +
            "r=$HomeFeaturedBackdropBlurRadiusPx:p=$HomeFeaturedBackdropBlurPasses"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        val scaled = scaleForFeaturedBackdropBlur(input, HomeFeaturedBackdropMaxSizePx)
        val readable = if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return scaled
        }
        val width = readable.width
        val height = readable.height
        val pixels = IntArray(width * height)
        readable.getPixels(pixels, 0, width, 0, 0, width, height)
        if (readable !== scaled) readable.recycle()
        if (scaled !== input) scaled.recycle()

        applySeparableBoxBlur(
            pixels = pixels,
            width = width,
            height = height,
            radius = HomeFeaturedBackdropBlurRadiusPx,
            passes = HomeFeaturedBackdropBlurPasses
        )
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}

private fun scaleForFeaturedBackdropBlur(source: Bitmap, maxDimensionPx: Int): Bitmap {
    val longestEdge = max(source.width, source.height)
    if (longestEdge <= maxDimensionPx) return source
    val scale = maxDimensionPx.toFloat() / longestEdge.toFloat()
    val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
    return source.scale(targetWidth, targetHeight, filter = true)
}

internal fun applySeparableBoxBlur(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int
) {
    if (width <= 0 || height <= 0 || radius <= 0 || passes <= 0 || pixels.size < width * height) {
        return
    }
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        boxBlurHorizontal(pixels, scratch, width, height, radius)
        boxBlurVertical(scratch, pixels, width, height, radius)
    }
}

private fun boxBlurHorizontal(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int
) {
    val window = radius * 2 + 1
    val lastX = width - 1
    for (y in 0 until height) {
        val rowOffset = y * width
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val pixel = src[rowOffset + i.coerceIn(0, lastX)]
            sumA += pixel ushr 24
            sumR += (pixel ushr 16) and 0xFF
            sumG += (pixel ushr 8) and 0xFF
            sumB += pixel and 0xFF
        }
        for (x in 0 until width) {
            dst[rowOffset + x] = packBlurredArgb(sumA / window, sumR / window, sumG / window, sumB / window)
            val addPixel = src[rowOffset + (x + radius + 1).coerceAtMost(lastX)]
            val remPixel = src[rowOffset + (x - radius).coerceAtLeast(0)]
            sumA += (addPixel ushr 24) - (remPixel ushr 24)
            sumR += ((addPixel ushr 16) and 0xFF) - ((remPixel ushr 16) and 0xFF)
            sumG += ((addPixel ushr 8) and 0xFF) - ((remPixel ushr 8) and 0xFF)
            sumB += (addPixel and 0xFF) - (remPixel and 0xFF)
        }
    }
}

private fun boxBlurVertical(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int
) {
    val window = radius * 2 + 1
    val lastY = height - 1
    for (x in 0 until width) {
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val pixel = src[i.coerceIn(0, lastY) * width + x]
            sumA += pixel ushr 24
            sumR += (pixel ushr 16) and 0xFF
            sumG += (pixel ushr 8) and 0xFF
            sumB += pixel and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] = packBlurredArgb(sumA / window, sumR / window, sumG / window, sumB / window)
            val addPixel = src[(y + radius + 1).coerceAtMost(lastY) * width + x]
            val remPixel = src[(y - radius).coerceAtLeast(0) * width + x]
            sumA += (addPixel ushr 24) - (remPixel ushr 24)
            sumR += ((addPixel ushr 16) and 0xFF) - ((remPixel ushr 16) and 0xFF)
            sumG += ((addPixel ushr 8) and 0xFF) - ((remPixel ushr 8) and 0xFF)
            sumB += (addPixel and 0xFF) - (remPixel and 0xFF)
        }
    }
}

private fun packBlurredArgb(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b

/**
 * Square artwork over a frosted label band backed by a statically pre-blurred low-resolution
 * cached bitmap, avoiding per-card real-time `RenderEffect` blur layers while scrolling.
 */
@Composable
internal fun HomeFeaturedArtwork(
    model: Any?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    backdropModel: Any? = null,
    clipToCardShape: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (clipToCardShape) Modifier.clip(NeriDesign.tokens.cardShape) else Modifier)
            .background(Color(0xFF2A2A2E))
    ) {
        AsyncImage(
            model = model,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FeaturedLabelHeight)
                .clipToBounds()
        ) {
            if (backdropModel != null) {
                AsyncImage(
                    model = backdropModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.matchParentSize()
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = FeaturedLabelScrimAlpha))
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private val ScrollEdgeFadeHeight = 24.dp
private const val ScrollEdgeFadeMillis = 180

/**
 * iOS-style scroll edge for the clean home: once content slides under the pinned title and
 * search, its top edge fades into the page instead of being cut by the grid bounds. A single
 * gradient rect drawn over the grid, skipped over custom background images where a solid fade
 * would not match what is behind it
 */
@Composable
internal fun Modifier.homeScrollEdgeFade(gridState: LazyGridState): Modifier {
    if (!NeriDesign.isClean || LocalHasCustomBackground.current) return this
    val scrolled by remember(gridState) { derivedStateOf { gridState.canScrollBackward } }
    val alpha = animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(ScrollEdgeFadeMillis),
        label = "home-scroll-edge"
    )
    val color = MaterialTheme.colorScheme.background
    return this.drawWithCache {
        val height = ScrollEdgeFadeHeight.toPx().coerceAtMost(size.height)
        val fade = Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), endY = height)
        onDrawWithContent {
            drawContent()
            val visible = alpha.value
            if (visible > 0.01f) drawRect(fade, size = Size(size.width, height), alpha = visible)
        }
    }
}
