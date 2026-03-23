package moe.ouom.neriplayer.ui.screen.tab.settings.state

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.state/SettingsFlowCompat
 * Updated: 2026/3/23
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.R

/** 兼容性：不用依赖 collectAsState / lifecycle-compose，手动收集 StateFlow */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> {
    val flow = this
    val state = remember(flow) { mutableStateOf(flow.value) }
    LaunchedEffect(flow) {
        flow.collect { state.value = it }
    }
    return state
}

/** 格式化同步时间 */
@Composable
internal fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3_600_000 -> stringResource(R.string.time_minutes_ago, diff / 60_000)
        diff < 86_400_000 -> stringResource(R.string.time_hours_ago, diff / 3_600_000)
        else -> stringResource(R.string.time_days_ago, diff / 86_400_000)
    }
}
