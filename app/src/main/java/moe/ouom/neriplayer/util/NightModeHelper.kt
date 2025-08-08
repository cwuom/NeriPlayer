package moe.ouom.neriplayer.util

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
 * File: moe.ouom.neriplayer.util/NightModeHelper
 * Created: 2025/8/8
 */

import androidx.appcompat.app.AppCompatDelegate

object NightModeHelper {

    /**
     * 优先级：forceDark > followSystem > 强制浅色
     * 调用后会触发 Activity 重建，资源/Compose/UI一致更新
     */
    fun applyNightMode(
        followSystemDark: Boolean,
        forceDark: Boolean
    ) {
        val mode = when {
            forceDark -> AppCompatDelegate.MODE_NIGHT_YES
            followSystemDark -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}