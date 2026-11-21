package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.common.NeriSearchFieldGlass
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** Opens Explore's search from the home header; null where no navigation host is available. */
val LocalHomeOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

private val FeaturedLabelHeight = 54.dp
private val FeaturedLabelBlur = 24.dp
private const val FeaturedLabelScrimAlpha = 0.32f

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
 * Square artwork over a frosted label band made from the same image, as on featured shelves.
 * The band blur is a static per-card render effect, so scrolling only translates it.
 */
@Composable
internal fun HomeFeaturedArtwork(
    model: Any?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NeriDesign.tokens.cardShape)
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
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomCenter,
                modifier = Modifier
                    .matchParentSize()
                    .blur(FeaturedLabelBlur, BlurredEdgeTreatment.Rectangle)
            )
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
