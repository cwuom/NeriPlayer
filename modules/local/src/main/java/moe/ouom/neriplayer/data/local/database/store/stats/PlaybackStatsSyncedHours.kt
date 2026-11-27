package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic

private data class HourShard(val day: Long, val identity: String, val device: String, val epoch: Long)

private fun SupportSQLiteDatabase.hourShardDates(sql: PlaybackStatsSqlQuery): Map<HourShard, String?> = buildMap {
    query(sql.differingHourShardDates()).use { rows ->
        while (rows.moveToNext()) {
            put(HourShard(rows.getLong(0), rows.getString(1), rows.getString(2), rows.getLong(3)), rows.getString(4))
        }
    }
}

internal suspend fun PlaybackStatsRoomStore.addSyncedHourTotals(
    sql: PlaybackStatsSqlQuery,
    hours: LongArray
) {
    val range = sql.range
    val start = range.startInclusive ?: Long.MIN_VALUE
    val dates = sql.hourDates
    val db = database.openHelper.readableDatabase
    val shardDates = if (dates == null) emptyMap() else db.hourShardDates(sql)
    val context = currentCoroutineContext()
    db.query(sql.syncedHourBuckets()).use { rows ->
        while (rows.moveToNext()) {
            context.ensureActive()
            val covered = PlaybackHourTotalsCodec.decode(rows.getString(1)).associateBy { it.slotKey }
            val day = rows.getLong(2)
            val identity = rows.getString(3)
            val bucketDate = rows.getString(4)
            for (hour in PlaybackHourTotalsCodec.decode(rows.getString(0))) {
                val recordedDate = if (dates == null) null else
                    shardDates.getOrDefault(HourShard(day, identity, hour.deviceId, hour.epochStartedAt), bucketDate)
                val selected = if (recordedDate != null && dates != null)
                    recordedDate >= dates.first && recordedDate < dates.second
                else hour.hourStartAt >= start && hour.hourStartAt < range.endExclusive
                if (selected) {
                    val localMs = covered[hour.slotKey]?.totalListenMs ?: 0L
                    val remoteMs = (hour.totalListenMs - localMs).coerceAtLeast(0L)
                    hours[hour.localHour] = SyncPlaybackCounterArithmetic.add(hours[hour.localHour], remoteMs)
                }
            }
        }
    }
}
