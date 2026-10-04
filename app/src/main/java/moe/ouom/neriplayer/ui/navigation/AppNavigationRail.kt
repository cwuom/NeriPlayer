package moe.ouom.neriplayer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import moe.ouom.neriplayer.ui.banner.OfflineModeBottomBanner
import moe.ouom.neriplayer.ui.component.navigation.isNeriNavigationDestinationSelected
import moe.ouom.neriplayer.ui.component.navigation.resolveBottomBarFallbackScrimAlpha
import moe.ouom.neriplayer.ui.component.navigation.resolveBottomBarSelectionAlpha
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.resolveBottomBarLayoutInsets
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

internal val AppNavigationRailWidth = 96.dp
private val AppNavigationRailMinWindowWidth = 840.dp

internal fun shouldUseAppNavigationRail(
    smallestScreenWidthDp: Int,
    isLandscape: Boolean,
    availableWidth: Dp
): Boolean = smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP && isLandscape &&
    availableWidth >= AppNavigationRailMinWindowWidth

internal fun resolveAppNavigationLayoutInsets(
    useNavigationRail: Boolean,
    baseBlurRequested: Boolean,
    bottomBarInset: Dp,
    reservedMiniPlayerHeight: Dp
): BottomBarLayoutInsets = if (useNavigationRail) {
    // 侧栏宿主已经消费系统底部安全区，页面只需要给迷你播放器留位置
    BottomBarLayoutInsets(0.dp, reservedMiniPlayerHeight, 0.dp)
} else {
    resolveBottomBarLayoutInsets(baseBlurRequested, bottomBarInset, reservedMiniPlayerHeight)
}

@Composable
internal fun AppAdaptiveNavigationContent(
    useNavigationRail: Boolean,
    showNowPlaying: Boolean,
    offlineMode: Boolean,
    railContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val safeModifier = if (useNavigationRail) {
        modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        )
    } else modifier
    Row(safeModifier) {
        if (useNavigationRail) {
            // 侧栏由播放覆盖层自然盖住，避免覆盖层入场前突然消失或退出时突然出现
            Box(
                Modifier
                    .width(AppNavigationRailWidth)
                    .fillMaxHeight()
                    .focusProperties { canFocus = !showNowPlaying }
                    .pointerInput(showNowPlaying) {
                        if (showNowPlaying) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .then(if (showNowPlaying) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                railContent()
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().testTag("appNavigationContent")
        ) {
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            if (useNavigationRail) {
                AnimatedVisibility(visible = offlineMode && !showNowPlaying) { OfflineModeBottomBanner() }
            }
        }
    }
}

@Composable
internal fun AppNavigationRail(
    presentation: AppBottomBarPresentation,
    onMainTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectAlpha = resolveBottomBarSelectionAlpha(
        presentation.hasCustomBackground,
        presentation.alwaysUseNewTabStyle
    )
    val scrimAlpha = resolveBottomBarFallbackScrimAlpha(
        selectAlpha,
        LocalAdvancedGlassController.current.isBaseBlurRequested
    )
    val fallbackColor = MaterialTheme.colorScheme.background.copy(alpha = scrimAlpha)
    AdvancedGlassSurface(
        // 侧栏只覆盖背景，右栏的页面 backdrop 不在侧栏的采样范围内
        role = AdvancedGlassRole.NavigationRail,
        modifier = modifier.width(AppNavigationRailWidth).fillMaxHeight().testTag("appNavigationRail"),
        fallbackColor = fallbackColor,
        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        NavigationRail(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("appNavigationItemsViewport")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                ) {
                    presentation.items.forEach { (destination, icon) ->
                        val label = stringResource(destination.labelResId)
                        val selected = isNeriNavigationDestinationSelected(presentation.currentDestination, destination)
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                context.performHapticFeedback()
                                onMainTabSelected(destination.route)
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            modifier = Modifier.testTag("appNavigationItem_${destination.route}"),
                            label = {
                                if (selected) {
                                    Text(label, modifier = Modifier.testTag("appNavigationLabel_${destination.route}"),
                                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            alwaysShowLabel = false,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}
