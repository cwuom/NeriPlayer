package moe.ouom.neriplayer.data.local.database.store.stats

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic

internal suspend fun PlaybackStatsRoomStore.addSyncedHourTotals(
    sql: PlaybackStatsSqlQuery,
    range: PlaybackStatsTimeRange,
    hours: LongArray
) {
    val start = range.startInclusive ?: Long.MIN_VALUE
    val context = currentCoroutineContext()
    database.openHelper.readableDatabase.query(sql.syncedHourBuckets()).use { rows ->
        while (rows.moveToNext()) {
            context.ensureActive()
            val covered = PlaybackHourTotalsCodec.decode(rows.getString(1)).associateBy { it.slotKey }
            for (hour in PlaybackHourTotalsCodec.decode(rows.getString(0))) {
                if (hour.hourStartAt < start || hour.hourStartAt >= range.endExclusive) continue
                // 本库的精确区间已按查询范围计入，这里仅补上云端多出的时长
                val remaining = (hour.totalListenMs - (covered[hour.slotKey]?.totalListenMs ?: 0L)).coerceAtLeast(0L)
                hours[hour.localHour] = SyncPlaybackCounterArithmetic.add(hours[hour.localHour], remaining)
            }
        }
    }
}
