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
import androidx.compose.runtime.State
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.units.day
import moe.ouom.neriplayer.util.units.hour
import moe.ouom.neriplayer.util.units.minute

/** 兼容性：统一走生命周期感知收集，避免设置页后台继续订阅 */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> {
    return collectAsStateWithLifecycle()
}

/** 格式化同步时间 */
@Composable
internal fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    val minutesAgo = (diff / 1.minute).toInt()
    val hoursAgo = (diff / 1.hour).toInt()
    val daysAgo = (diff / 1.day).toInt()

    return when {
        diff < 1.minute -> stringResource(R.string.time_just_now)
        diff < 1.hour -> pluralStringResource(R.plurals.time_minutes_ago, minutesAgo, minutesAgo)
        diff < 1.day -> pluralStringResource(R.plurals.time_hours_ago, hoursAgo, hoursAgo)
        else -> pluralStringResource(R.plurals.time_days_ago, daysAgo, daysAgo)
    }
}
