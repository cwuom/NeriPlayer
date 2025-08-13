package moe.ouom.neriplayer.util

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.Date

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
 * File: moe.ouom.neriplayer.util/Utils
 * Created: 2025/8/8
 */

fun convertTimestampToDate(timestamp: Long): String {
    val date = Date(timestamp)
    @SuppressLint("SimpleDateFormat") val sdf =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    return sdf.format(date)
}

fun formatTotalDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}