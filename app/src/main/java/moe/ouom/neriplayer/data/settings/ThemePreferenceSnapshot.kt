package moe.ouom.neriplayer.data.settings

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
 * File: moe.ouom.neriplayer.data.settings/ThemePreferenceSnapshot
 * Updated: 2026/3/23
 */

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

data class ThemePreferenceSnapshot(
    val dynamicColor: Boolean = true,
    val forceDark: Boolean = false,
    val followSystemDark: Boolean = true
) {
    fun resolveUseDark(systemDark: Boolean): Boolean {
        return when {
            forceDark -> true
            followSystemDark -> systemDark
            else -> false
        }
    }
}

fun readThemePreferenceSnapshotSync(context: Context): ThemePreferenceSnapshot {
    return runBlocking {
        val prefs = context.dataStore.data.first()
        ThemePreferenceSnapshot(
            dynamicColor = prefs[SettingsKeys.DYNAMIC_COLOR] ?: true,
            forceDark = prefs[SettingsKeys.FORCE_DARK] ?: false,
            followSystemDark = prefs[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true
        )
    }
}
