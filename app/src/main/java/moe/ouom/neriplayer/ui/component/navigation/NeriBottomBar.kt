package moe.ouom.neriplayer.ui.component.navigation

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.component/NeriBottomBar
 * Created: 2025/8/8
 */

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

internal const val DEFAULT_BOTTOM_BAR_SELECTION_ALPHA = 0.72f
internal const val BOTTOM_BAR_FALLBACK_SCRIM_ALPHA = 0.28f

internal fun resolveBottomBarSelectionAlpha(
    hasCustomBackground: Boolean,
    alwaysUseNewTabStyle: Boolean
): Float = if (hasCustomBackground || alwaysUseNewTabStyle) {
    0f
} else {
    DEFAULT_BOTTOM_BAR_SELECTION_ALPHA
}

internal fun isNeriNavigationDestinationSelected(
    currentDestination: NavDestination?,
    destination: Destinations
): Boolean = currentDestination?.hierarchy?.any { it.route == destination.route } == true

@Composable
fun NeriBottomBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier,
    selectAlpha: Float = DEFAULT_BOTTOM_BAR_SELECTION_ALPHA
) {
    if (NeriDesign.isClean) {
        CleanFloatingTabBar(items, currentDestination, onItemSelected, modifier)
        return
    }
    val context = LocalContext.current
    val alwaysShowLabel = selectAlpha != 0f
    val baseBlurRequested = LocalAdvancedGlassController.current.isBaseBlurRequested
    val fallbackScrimAlpha = resolveBottomBarFallbackScrimAlpha(
        selectAlpha = selectAlpha,
        baseBlurRequested = baseBlurRequested
    )
    val fallbackColor = if (fallbackScrimAlpha > 0f) {
        MaterialTheme.colorScheme.background.copy(alpha = fallbackScrimAlpha)
    } else {
        Color.Transparent
    }

    AdvancedGlassSurface(
        role = AdvancedGlassRole.BottomNavigation,
        modifier = modifier,
        fallbackColor = fallbackColor
    ) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            items.forEach { (dest, icon) ->
                val selected = isNeriNavigationDestinationSelected(currentDestination, dest)
                val label = stringResource(dest.labelResId)
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        context.performHapticFeedback()
                        onItemSelected(dest)
                    },
                    icon = {
                        // 选中态换填充字形, 与 M3 指示器一起表达层级
                        MaterialTabIcon(dest, icon, selected, label)
                    },
                    label = {
                        Text(
                            text = label,
                            style = LocalTextStyle.current,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    alwaysShowLabel = alwaysShowLabel,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                            alpha = selectAlpha
                        ),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                )
            }
        }
    }
}

@Composable
private fun MaterialTabIcon(
    destination: Destinations,
    outlinedIcon: ImageVector,
    selected: Boolean,
    contentDescription: String
) {
    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 180),
        label = "material-tab-icon"
    ) { isSelected ->
        Icon(
            imageVector = if (isSelected) cleanSelectedTabIcon(destination) ?: outlinedIcon else outlinedIcon,
            contentDescription = contentDescription
        )
    }
}

/** Filled rounded glyph for the active tab, outlined for the rest, with a small spring pop. */
@Composable
internal fun CleanTabIcon(
    destination: Destinations,
    outlinedIcon: ImageVector,
    selected: Boolean,
    contentDescription: String
) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.84f)
            pop.animateTo(1f, NeriMotion.bouncy())
        }
    }
    Icon(
        imageVector = if (selected) cleanSelectedTabIcon(destination) ?: outlinedIcon else outlinedIcon,
        contentDescription = contentDescription,
        modifier = Modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
        }
    )
}

internal fun cleanSelectedTabIcon(destination: Destinations): ImageVector? = when (destination.route) {
    Destinations.Home.route -> Icons.Rounded.Home
    Destinations.Explore.route -> Icons.Rounded.Search
    Destinations.Library.route -> Icons.Rounded.LibraryMusic
    Destinations.Settings.route -> Icons.Rounded.Settings
    Destinations.Debug.route -> Icons.Rounded.BugReport
    else -> null
}

internal fun shouldUseOpaqueBottomBarFallback(
    selectAlpha: Float,
    baseBlurRequested: Boolean
): Boolean = selectAlpha != 0f || baseBlurRequested

internal fun resolveBottomBarFallbackScrimAlpha(
    selectAlpha: Float,
    baseBlurRequested: Boolean
): Float = if (shouldUseOpaqueBottomBarFallback(selectAlpha, baseBlurRequested)) {
    BOTTOM_BAR_FALLBACK_SCRIM_ALPHA
} else {
    0f
}
