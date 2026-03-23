package moe.ouom.neriplayer.ui.screen.tab.settings.component

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsCommon
 * Updated: 2026/3/23
 */

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.HapticIconButton

internal fun maskCookieValue(value: String): String {
    return when {
        value.length <= 4 -> "***"
        else -> "${value.take(2)}***${value.takeLast(2)}"
    }
}

private val SettingsItemShape = RoundedCornerShape(18.dp)

internal fun Modifier.settingsItemClickable(onClick: () -> Unit): Modifier {
    return clip(SettingsItemShape).clickable(onClick = onClick)
}

/** 可复用的折叠区头部 */
@Composable
internal fun ExpandableHeader(
    icon: ImageVector,
    title: String,
    subtitleCollapsed: String,
    subtitleExpanded: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    arrowRotation: Float = 0f
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(if (expanded) subtitleExpanded else subtitleCollapsed) },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    stringResource(R.string.action_collapse)
                } else {
                    stringResource(R.string.action_expand)
                },
                modifier = Modifier.rotate(arrowRotation.takeIf { it != 0f } ?: if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        modifier = Modifier.settingsItemClickable(onClick = onToggle),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 主题色预览行（当关闭系统动态取色时显示） */
@Composable
internal fun ThemeSeedListItem(seedColorHex: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ColorLens,
                contentDescription = stringResource(R.string.settings_theme_color),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_color)) },
        supportingContent = { Text(stringResource(R.string.settings_theme_color_desc)) },
        trailingContent = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(("#$seedColorHex").toColorInt()))
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** UI 缩放设置入口 */
@Composable
internal fun UiScaleListItem(currentScale: Float, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ZoomInMap,
                contentDescription = stringResource(R.string.settings_ui_scale),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_ui_scale_dpi)) },
        supportingContent = {
            Text(stringResource(R.string.settings_ui_scale_current, "%.2f".format(currentScale)))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun ThemeModeActionButton(
    isDarkTheme: Boolean,
    onToggleRequest: (Offset, Float) -> Unit
) {
    var centerInWindow by remember { mutableStateOf<Offset?>(null) }
    var revealStartRadiusPx by remember { mutableFloatStateOf(18f) }
    val contentDescription = if (isDarkTheme) {
        stringResource(R.string.settings_theme_toggle_light)
    } else {
        stringResource(R.string.settings_theme_toggle_dark)
    }
    val iconProgress by animateFloatAsState(
        targetValue = if (isDarkTheme) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "theme_toggle_icon_progress"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isDarkTheme) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "theme_toggle_container_color"
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(containerColor)
    ) {
        HapticIconButton(
            onClick = { centerInWindow?.let { onToggleRequest(it, revealStartRadiusPx) } },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    revealStartRadiusPx = maxOf(coordinates.size.width, coordinates.size.height) / 2f
                    centerInWindow = coordinates.positionInWindow() + Offset(
                        x = coordinates.size.width / 2f,
                        y = coordinates.size.height / 2f
                    )
                }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = 1f - iconProgress
                            val scale = 0.56f + (1f - iconProgress) * 0.44f
                            scaleX = scale
                            scaleY = scale
                            rotationZ = -56f * iconProgress
                        }
                )
                Icon(
                    imageVector = Icons.Outlined.LightMode,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = iconProgress
                            val scale = 0.56f + iconProgress * 0.44f
                            scaleX = scale
                            scaleY = scale
                            rotationZ = 56f * (1f - iconProgress)
                        }
                )
            }
        }
    }
}

/** 内嵌提示条 */
@Composable
internal fun InlineMessage(text: String, onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            HapticIconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close)
                )
            }
        }
    }
}
