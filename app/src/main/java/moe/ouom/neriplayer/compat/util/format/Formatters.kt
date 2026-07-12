package moe.ouom.neriplayer.util

import android.content.Context
import moe.ouom.neriplayer.util.format.formatDate as formatDateImpl
import moe.ouom.neriplayer.util.format.formatDuration as formatDurationImpl
import moe.ouom.neriplayer.util.format.formatFileSize as formatFileSizeImpl
import moe.ouom.neriplayer.util.format.formatPlayCount as formatPlayCountImpl

fun formatPlayCount(context: Context, count: Long): String {
    return formatPlayCountImpl(context, count)
}

fun formatDuration(millis: Long): String {
    return formatDurationImpl(millis)
}

fun formatFileSize(bytes: Long): String {
    return formatFileSizeImpl(bytes)
}

fun formatDate(timestamp: Long): String {
    return formatDateImpl(timestamp)
}
