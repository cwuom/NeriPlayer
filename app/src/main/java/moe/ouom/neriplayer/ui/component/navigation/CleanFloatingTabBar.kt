package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import kotlin.math.roundToInt

private val FloatingBarHeight = 64.dp
private val FloatingBarHorizontalMargin = 16.dp
// floats clear of the system gesture strip so a press-and-slide never reads as a home swipe
private val FloatingBarBottomMargin = 16.dp

/**
 * Floating capsule tab bar for the clean style, after the iOS 26 tab bar: inset glass capsule
 * with a hairline rim and a soft shadow, a quiet pill behind the selected tab, and on drag a
 * larger glass lens that lifts out of the bar, follows the finger and magnifies the tab below.
 */
@Composable
internal fun CleanFloatingTabBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val overBackground = LocalHasCustomBackground.current
    val selectedIndex = items.indexOfFirst { (dest, _) ->
        isNeriNavigationDestinationSelected(currentDestination, dest)
    }
    val tabDrag = remember { CleanTabDragState() }
    val host = remember { LensHost() }
    val latestItems by rememberUpdatedState(items)
    val latestOnItemSelected by rememberUpdatedState(onItemSelected)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val density = LocalDensity.current
    val barHeightPx = with(density) { FloatingBarHeight.toPx() }
    val barLayer = rememberGraphicsLayer()
    val lensPath = remember { Path() }
    SideEffect { tabDrag.trimItems(items.size) }
    LaunchedEffect(selectedIndex, tabDrag.layoutVersion) {
        tabDrag.settleOn(selectedIndex)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = FloatingBarHorizontalMargin,
                end = FloatingBarHorizontalMargin,
                bottom = FloatingBarBottomMargin
            )
            .onGloballyPositioned { host.coordinates = it }
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.BottomNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .height(FloatingBarHeight)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.3f else 0.12f)
                )
                .border(
                    width = 0.5.dp,
                    color = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f),
                    shape = CircleShape
                ),
            shape = CircleShape,
            fallbackColor = colors.surfaceContainerLow.copy(alpha = if (overBackground) 0.62f else 0.9f),
            tintColor = colors.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp)
                    .onGloballyPositioned { tabDrag.bar = it }
                    .cleanTabDragGesture(tabDrag) { index ->
                        if (index == latestSelectedIndex) return@cleanTabDragGesture
                        latestItems.getOrNull(index)?.let { (dest, _) -> latestOnItemSelected(dest) }
                    }
                    .drawWithContent {
                        drawBarContentAroundIndicator(
                            layer = barLayer,
                            indicator = tabDrag.indicatorRect(barHeightPx, density),
                            path = lensPath
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, (dest, icon) ->
                    val selected = index == selectedIndex
                    val label = stringResource(dest.labelResId)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { tabDrag.reportItem(index, it) }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                context.performHapticFeedback()
                                onItemSelected(dest)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        TabGlyph(dest, icon, selected, label)
                    }
                }
            }
        }

        CleanTabIndicator(
            tabDrag = tabDrag,
            hostCoordinates = { host.coordinates },
            barLayer = barLayer,
            barHeightPx = barHeightPx,
            dark = dark
        )
    }
}

@Composable
private fun TabGlyph(
    dest: Destinations,
    icon: ImageVector,
    selected: Boolean,
    label: String
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.72f)
    CompositionLocalProvider(LocalContentColor provides tint) {
        CleanTabIcon(dest, icon, selected, label)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        ),
        color = tint,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

private class LensHost {
    var coordinates: LayoutCoordinates? = null
}
