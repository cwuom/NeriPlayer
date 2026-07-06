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
import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val THEME_SNAPSHOT_PREFS = "theme_snapshot_cache"
private const val THEME_DYNAMIC_COLOR_KEY = "dynamic_color"
private const val THEME_FORCE_DARK_KEY = "force_dark"
private const val THEME_FOLLOW_SYSTEM_DARK_KEY = "follow_system_dark"

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
    readCachedThemePreferenceSnapshot(context)?.let { return it }

    return runCatching {
        runBlocking {
            val prefs = context.dataStore.data.first()
            ThemePreferenceSnapshot(
                dynamicColor = prefs[SettingsKeys.DYNAMIC_COLOR] ?: true,
                forceDark = prefs[SettingsKeys.FORCE_DARK] ?: false,
                followSystemDark = prefs[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true
            )
        }
    }.getOrElse {
        ThemePreferenceSnapshot()
    }.also { snapshot ->
        persistThemePreferenceSnapshot(context, snapshot)
    }
}

internal fun persistThemePreferenceSnapshot(
    context: Context,
    snapshot: ThemePreferenceSnapshot
) {
    context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putBoolean(THEME_DYNAMIC_COLOR_KEY, snapshot.dynamicColor)
        putBoolean(THEME_FORCE_DARK_KEY, snapshot.forceDark)
        putBoolean(THEME_FOLLOW_SYSTEM_DARK_KEY, snapshot.followSystemDark)
    }
}

internal fun persistThemeDynamicColor(context: Context, value: Boolean) {
    context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putBoolean(THEME_DYNAMIC_COLOR_KEY, value)
    }
}

internal fun persistThemeForceDark(context: Context, value: Boolean) {
    context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putBoolean(THEME_FORCE_DARK_KEY, value)
    }
}

internal fun persistThemeFollowSystemDark(context: Context, value: Boolean) {
    context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putBoolean(THEME_FOLLOW_SYSTEM_DARK_KEY, value)
    }
}

internal fun persistThemeModeSnapshot(
    context: Context,
    followSystemDark: Boolean,
    forceDark: Boolean
) {
    val prefs = context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    prefs.edit {
        putBoolean(THEME_DYNAMIC_COLOR_KEY, prefs.getBoolean(THEME_DYNAMIC_COLOR_KEY, true))
        putBoolean(THEME_FORCE_DARK_KEY, forceDark)
        putBoolean(THEME_FOLLOW_SYSTEM_DARK_KEY, followSystemDark)
    }
}

private fun readCachedThemePreferenceSnapshot(context: Context): ThemePreferenceSnapshot? {
    val prefs = context.getSharedPreferences(THEME_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (
        !prefs.contains(THEME_DYNAMIC_COLOR_KEY) ||
        !prefs.contains(THEME_FORCE_DARK_KEY) ||
        !prefs.contains(THEME_FOLLOW_SYSTEM_DARK_KEY)
    ) {
        return null
    }
    return ThemePreferenceSnapshot(
        dynamicColor = prefs.getBoolean(THEME_DYNAMIC_COLOR_KEY, true),
        forceDark = prefs.getBoolean(THEME_FORCE_DARK_KEY, false),
        followSystemDark = prefs.getBoolean(THEME_FOLLOW_SYSTEM_DARK_KEY, true)
    )
}
