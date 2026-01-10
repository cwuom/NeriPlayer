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
 * File: moe.ouom.neriplayer.util/Formatters
 * Created: 2025/8/10
 */

import android.annotation.SuppressLint
import android.content.Context
import moe.ouom.neriplayer.R
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@SuppressLint("DefaultLocale")
fun formatPlayCount(context: Context, count: Long): String {
    val locale = context.resources.configuration.locales[0]
    val isChinese = locale.language.startsWith("zh")
    
    return if (isChinese) {
        // 中文：使用亿、万
        when {
            count >= 100_000_000L -> context.getString(R.string.number_hundred_million, count / 100_000_000.0)
            count >= 10_000L      -> context.getString(R.string.number_ten_thousand, count / 10_000.0)
            else                  -> count.toString()
        }
    } else {
        // 英文：使用 B (billion)、M (million)、K (thousand)
        when {
            count >= 1_000_000_000L -> context.getString(R.string.number_billion, count / 1_000_000_000.0)
            count >= 1_000_000L     -> context.getString(R.string.number_million, count / 1_000_000.0)
            count >= 1_000L         -> context.getString(R.string.number_thousand, count / 1_000.0)
            else                    -> count.toString()
        }
    }
}

@SuppressLint("DefaultLocale")
fun formatDuration(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) -
            TimeUnit.MINUTES.toSeconds(minutes)
    return String.format("%02d:%02d", minutes, seconds)
}

@SuppressLint("DefaultLocale")
fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(date)
}