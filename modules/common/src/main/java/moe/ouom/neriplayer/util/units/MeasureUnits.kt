package moe.ouom.neriplayer.util.units

const val SECOND_MS = 1_000L
const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS

val Int.second: Long
    get() = toLong() * SECOND_MS

val Int.minute: Long
    get() = toLong() * MINUTE_MS

val Int.hour: Long
    get() = toLong() * HOUR_MS

val Int.day: Long
    get() = toLong() * DAY_MS

const val MEBIBYTE_BYTES = 1_024L * 1_024L
const val GIBIBYTE_BYTES = 1_024L * MEBIBYTE_BYTES
