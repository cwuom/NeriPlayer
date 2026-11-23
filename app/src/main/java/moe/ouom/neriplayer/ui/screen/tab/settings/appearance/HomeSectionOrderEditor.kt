package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.appearance.HomeSectionOrderEditor
 * Created: 2026/11/20
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.home.moveHomeSectionItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableColumnScope
import sh.calvin.reorderable.ReorderableListItemScope

/**
 * 设置页里的可拖拽排序列表。
 */
@Composable
internal fun <T> HomeReorderableColumn(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ReorderableColumnScope.(index: Int, item: T, isDragging: Boolean) -> Unit
) {
    ReorderableColumn(
        list = items,
        onSettle = { fromIndex, toIndex ->
            if (fromIndex == toIndex) return@ReorderableColumn
            if (fromIndex !in items.indices || toIndex !in items.indices) return@ReorderableColumn
            val reordered = items.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)
            onReorder(reordered.toList())
        },
        modifier = modifier,
        verticalArrangement = verticalArrangement
    ) { index, item, isDragging ->
        content(index, item, isDragging)
    }
}

/**
 * 拖拽手柄。
 *
 * [nested] 为 true 时表示这是大模块内部的子模块手柄：用主题色和更小的
 * 尺寸与顶层模块手柄区分，避免两级手柄在视觉上混为一体。
 * 子模块手柄还收紧了按钮内边距，否则 48dp 的默认触摸区会让图标与标题之间
 * 留出过大的空隙。
 */
@Composable
internal fun ReorderableListItemScope.HomeReorderHandleIcon(
    title: String,
    items: List<String>,
    index: Int,
    onReorder: (List<String>) -> Unit,
    nested: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember(title) { mutableStateOf(false) }
    val tint = if (nested) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconSize = if (nested) 16.dp else 20.dp
    val buttonSize = if (nested) 32.dp else 48.dp
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(buttonSize)
                .draggableHandle(
                    dragGestureDetector = DragGestureDetector.LongPress,
                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(CoreCommonR.string.home_reorder_section, title),
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun move(to: Int) {
                expanded = false
                onReorder(moveHomeSectionItem(items, index, to))
            }
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.home_move_up)) },
                onClick = { move(index - 1) },
                enabled = index > 0
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.home_move_down)) },
                onClick = { move(index + 1) },
                enabled = index < items.lastIndex
            )
        }
    }
}

/** 排序列表里的普通行容器。 */
@Composable
internal fun HomeReorderableRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        content()
    }
}
