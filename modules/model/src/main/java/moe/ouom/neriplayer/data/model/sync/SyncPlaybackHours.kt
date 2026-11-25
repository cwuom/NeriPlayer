@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.model.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class SyncPlaybackHourTotal(
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val epochStartedAt: Long = 0L,
    @ProtoNumber(3) val hourStartAt: Long = 0L,
    @ProtoNumber(4) val localHour: Int = 0,
    @ProtoNumber(5) val totalListenMs: Long = 0L
) {
    data class Slot(val deviceId: String, val epochStartedAt: Long, val hourStartAt: Long, val localHour: Int)

    val slotKey: Slot get() = Slot(deviceId, epochStartedAt, hourStartAt, localHour)

    internal fun isValidAfter(clearedAt: Long): Boolean =
        !deviceId.isNullOrBlank() && epochStartedAt >= clearedAt && hourStartAt >= 0L && localHour in 0..23 && totalListenMs > 0L
}

object SyncPlaybackHours {
    private val order = compareBy(SyncPlaybackHourTotal::hourStartAt, SyncPlaybackHourTotal::localHour,
        SyncPlaybackHourTotal::deviceId, SyncPlaybackHourTotal::epochStartedAt)

    fun merge(
        first: List<SyncPlaybackHourTotal?>?,
        second: List<SyncPlaybackHourTotal?>? = emptyList(),
        clearedAt: Long = 0L
    ): List<SyncPlaybackHourTotal> {
        if (first.isNullOrEmpty() && second.isNullOrEmpty()) return emptyList()
        val totals = mutableMapOf<SyncPlaybackHourTotal.Slot, SyncPlaybackHourTotal>()
        val epoch = clearedAt.coerceAtLeast(0L)
        for (row in first.orEmpty().asSequence() + second.orEmpty().asSequence()) {
            if (row == null || !row.isValidAfter(epoch)) continue
            val key = row.slotKey
            if (row.totalListenMs > (totals[key]?.totalListenMs ?: 0L)) totals[key] = row
        }
        return totals.values.sortedWith(order)
    }
}
