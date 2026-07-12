package moe.ouom.neriplayer.util

import android.content.Context
import moe.ouom.neriplayer.util.format.convertTimestampToDate as convertTimestampToDateImpl
import moe.ouom.neriplayer.util.format.formatDurationSec as formatDurationSecImpl
import moe.ouom.neriplayer.util.format.formatTotalDuration as formatTotalDurationImpl

fun convertTimestampToDate(timestamp: Long): String {
    return convertTimestampToDateImpl(timestamp)
}

fun formatTotalDuration(context: Context, ms: Long): String {
    return formatTotalDurationImpl(context, ms)
}

fun formatDurationSec(seconds: Int): String {
    return formatDurationSecImpl(seconds)
}
