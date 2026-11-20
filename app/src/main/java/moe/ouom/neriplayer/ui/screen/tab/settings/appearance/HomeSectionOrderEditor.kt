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
 */
@Composable
internal fun ReorderableListItemScope.HomeReorderHandleIcon(
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {},
        modifier = modifier.draggableHandle(
            dragGestureDetector = DragGestureDetector.LongPress,
            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        )
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
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
