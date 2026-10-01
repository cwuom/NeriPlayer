package moe.ouom.neriplayer.data.model.stats

import java.util.Calendar

fun PlaybackStatsPeriod.resolvePlaybackStatsTimeRange(
    nowMillis: Long = System.currentTimeMillis()
): PlaybackStatsTimeRange {
    if (this == PlaybackStatsPeriod.ALL) {
        return PlaybackStatsTimeRange(
            startInclusive = null,
            endExclusive = Long.MAX_VALUE
        )
    }

    val end = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        moveToDayStart()
        add(Calendar.DAY_OF_YEAR, 1)
    }
    val days = when (this) {
        PlaybackStatsPeriod.DAY -> 1
        PlaybackStatsPeriod.WEEK -> 7
        PlaybackStatsPeriod.MONTH -> 30
        PlaybackStatsPeriod.YEAR -> 365
        PlaybackStatsPeriod.ALL -> 0
    }
    val start = (end.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -days)
    }
    return PlaybackStatsTimeRange(
        startInclusive = start.timeInMillis,
        endExclusive = end.timeInMillis
    )
}

fun playbackStatsDayStartAt(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        moveToDayStart()
    }.timeInMillis
}

private fun Calendar.moveToDayStart() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
