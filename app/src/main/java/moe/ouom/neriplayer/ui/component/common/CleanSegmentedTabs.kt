package moe.ouom.neriplayer.ui.component.common

import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloatAsState
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer

private const val SEGMENTED_MAX_FIXED_ITEMS = 4
private val SegmentedHeight = 36.dp
private val SegmentedInset = 3.dp
private val SegmentedLabelVerticalPadding = 4.dp

/**
 * Clean-style tab selector. Up to four labels render as an equal-width segmented control with
 * a sliding thumb; longer lists fall back to a scrollable capsule row.
 */
@Composable
fun CleanSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    maxFixedItems: Int = SEGMENTED_MAX_FIXED_ITEMS,
    badged: (Int) -> Boolean = { false }
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val labelHeight = remember(labels, labelStyle, density, textMeasurer) {
        labels.maxOf { textMeasurer.measure(it, style = labelStyle, maxLines = 1).size.height }
    }
    val height = maxOf(
        SegmentedHeight,
        with(density) { labelHeight.toDp() } + (SegmentedInset + SegmentedLabelVerticalPadding) * 2
    )
    if (labels.size <= maxFixedItems) {
        FixedSegmentedTabs(labels, selectedIndex, onSelected, modifier.padding(contentPadding), height, badged)
    } else {
        ScrollableCapsuleTabs(labels, selectedIndex, onSelected, modifier, contentPadding, height, badged)
    }
}

@Composable
private fun FixedSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    height: Dp,
    badged: (Int) -> Boolean
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    CleanTabGlass(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(SegmentedInset)
    ) {
        val segmentWidth: Dp = maxWidth / labels.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex.coerceIn(0, labels.lastIndex),
            animationSpec = NeriMotion.snappy(Dp.VisibilityThreshold),
            label = "clean_segmented_thumb"
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(1.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(cleanSegmentThumbColor())
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    animationSpec = NeriMotion.smooth(),
                    label = "clean_segmented_text"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!selected) {
                                context.performHapticFeedback()
                                onSelected(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    TabLabel(
                        label = label,
                        color = textColor,
                        badge = badged(index),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
    }
}

/**
 * Track for clean tabs. Over a custom background it becomes a real glass region so the
 * advanced blur reaches it; on a plain canvas a faint ink fill is enough and costs nothing.
 */
@Composable
private fun CleanTabGlass(
    modifier: Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val overBackground = LocalHasCustomBackground.current
    val fill = when {
        selected -> colors.onSurface
        overBackground -> colors.surfaceContainerLow.copy(alpha = 0.5f)
        else -> colors.onSurface.copy(alpha = 0.06f)
    }
    if (overBackground && !selected) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = modifier,
            shape = CircleShape,
            fallbackColor = fill,
            tintColor = colors.surfaceContainerLow
        ) { content() }
    } else {
        Box(modifier = modifier.clip(CircleShape).background(fill)) { content() }
    }
}

private val EdgeFadeWidth = 28.dp

/**
 * Masks scrolling content toward the edges. Strengths are read at draw time, so the mask can
 * ease in and out as the row reaches either end without recomposing the row.
 */
private fun Modifier.softHorizontalEdgeFade(startFade: () -> Float, endFade: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = EdgeFadeWidth.toPx().coerceAtMost(size.width / 2f)
            val start = startFade().coerceIn(0f, 1f)
            val end = endFade().coerceIn(0f, 1f)
            if (start > 0.001f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black.copy(alpha = 1f - start), Color.Black),
                        startX = 0f,
                        endX = width
                    ),
                    size = Size(width, size.height),
                    blendMode = BlendMode.DstIn
                )
            }
            if (end > 0.001f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - end)),
                        startX = size.width - width,
                        endX = size.width
                    ),
                    topLeft = Offset(size.width - width, 0f),
                    size = Size(width, size.height),
                    blendMode = BlendMode.DstIn
                )
            }
        }

/** Raised thumb: white on light, a lifted grey on dark so it never reads as a hole. */
@Composable
private fun cleanSegmentThumbColor(): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return if (dark) {
        lerp(colors.surfaceContainerHighest, colors.onSurface, 0.24f)
    } else {
        colors.surfaceContainerLow
    }
}

@Composable
private fun ScrollableCapsuleTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    height: Dp,
    badged: (Int) -> Boolean
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex !in labels.indices) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val viewportStart = layout.viewportStartOffset
        val viewportEnd = layout.viewportEndOffset
        val fullyVisible = item != null &&
            item.offset >= viewportStart && item.offset + item.size <= viewportEnd
        if (fullyVisible) return@LaunchedEffect
        // reveal a clipped tab by centering it; never yank the row so the tab lands first
        val itemSize = item?.size ?: 0
        val centeredOffset = -((viewportEnd - viewportStart - itemSize) / 2)
        listState.animateScrollToItem(selectedIndex, centeredOffset)
    }
    val startFade by animateFloatAsState(
        targetValue = if (listState.canScrollBackward) 1f else 0f,
        animationSpec = NeriMotion.smooth(),
        label = "clean_capsule_start_fade"
    )
    val endFade by animateFloatAsState(
        targetValue = if (listState.canScrollForward) 1f else 0f,
        animationSpec = NeriMotion.smooth(),
        label = "clean_capsule_end_fade"
    )
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .softHorizontalEdgeFade(startFade = { startFade }, endFade = { endFade }),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(labels) { index, label ->
            val selected = index == selectedIndex
            val content by animateColorAsState(
                targetValue = if (selected) colors.surface else colors.onSurfaceVariant,
                animationSpec = NeriMotion.smooth(),
                label = "clean_capsule_content"
            )
            CleanTabGlass(
                modifier = Modifier
                    .height(height)
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    },
                selected = selected
            ) {
                Box(
                    modifier = Modifier
                        .height(height)
                        .clickable {
                            if (!selected) {
                                context.performHapticFeedback()
                                onSelected(index)
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TabLabel(label = label, color = content, badge = badged(index), bounded = false)
                }
            }
        }
    }
}

/**
 * Label with an optional "modified" dot that fades in instead of popping the width.
 * [bounded] must be false inside lazy rows: a weighted child measures to zero width when the
 * parent has no maximum width, which would hide the label entirely.
 */
@Composable
private fun TabLabel(
    label: String,
    color: Color,
    badge: Boolean,
    modifier: Modifier = Modifier,
    bounded: Boolean = true
) {
    val badgeAlpha by animateFloatAsState(
        targetValue = if (badge) 1f else 0f,
        animationSpec = NeriMotion.smooth(),
        label = "clean_tab_badge"
    )
    val badgeColor = MaterialTheme.colorScheme.primary
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (bounded) Modifier.weight(1f, fill = false) else Modifier
        )
        if (badge || badgeAlpha > 0f) {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(5.dp)
                    .graphicsLayer { alpha = badgeAlpha }
                    .background(badgeColor, CircleShape)
            )
        }
    }
}
