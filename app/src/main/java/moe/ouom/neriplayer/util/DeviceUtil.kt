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
 * File: moe.ouom.neriplayer.util/DeviceUtil
 * Updated: 2026/3/23
 */

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * 仅对手机锁定竖屏，平板等大屏设备保持系统默认方向
 */
fun Activity.lockPortraitIfPhone() {
    val isPhone = resources.configuration.smallestScreenWidthDp < 600
    requestedOrientation = if (isPhone) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
