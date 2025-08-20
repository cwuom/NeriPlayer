package moe.ouom.neriplayer.util

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

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

/**
 * Converts a UNIX epoch millis timestamp to a formatted local time string.
 */
fun convertTimestampToDate(timestamp: Long): String {
    val date = Date(timestamp)
    @SuppressLint("SimpleDateFormat")
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    return sdf.format(date)
}

/**
 * Formats a long duration millis to a human readable string in Chinese.
 * Example: 90min -> "1小时30分钟"; 45min -> "45分钟".
 */
fun formatTotalDuration(ms: Long): String {
    if (ms <= 0) return "0分钟"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}

/**
 * Formats seconds to "mm:ss" or "h:mm:ss" when hours > 0.
 */
@SuppressLint("DefaultLocale")
fun formatDurationSec(seconds: Int): String {
    if (seconds <= 0) return "00:00"
    val total = seconds.toLong()
    val hours = TimeUnit.SECONDS.toHours(total)
    val minutes = TimeUnit.SECONDS.toMinutes(total) % 60
    val secs = (total % 60).toInt()
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs)
    else String.format("%02d:%02d", minutes, secs)
}