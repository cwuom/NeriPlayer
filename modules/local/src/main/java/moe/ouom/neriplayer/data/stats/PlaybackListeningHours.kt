package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHours
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class PlaybackListeningHour(val startAt: Long, val hourStartAt: Long, val localHour: Int, val durationMs: Long)

internal fun playbackListeningHours(endAt: Long, listenedMs: Long, zone: ZoneId): Sequence<PlaybackListeningHour> = sequence {
    if (listenedMs !in 1..endAt) return@sequence
    var cursor = endAt - listenedMs
    while (cursor < endAt) {
        val instant = Instant.ofEpochMilli(cursor)
        val local = instant.atZone(zone)
        val topOfHour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val nominalStart = topOfHour.toInstant(local.offset)
        val hourStart = if (zone.rules.getOffset(nominalStart) == local.offset) nominalStart
            else zone.rules.previousTransition(instant.plusNanos(1))?.instant ?: nominalStart
        val nextHour = topOfHour.plusHours(1).toInstant(local.offset).toEpochMilli()
        // 某些地区在半点切换夏令时，先结束当前偏移下的这段计时
        val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        val next = minOf(endAt, nextHour, transition)
        yield(PlaybackListeningHour(cursor, hourStart.toEpochMilli().coerceAtLeast(0), local.hour, next - cursor))
        cursor = next
    }
}

internal fun PlaybackStatsPendingDeltaEntity.hourIncrements(day: Long, durationMs: Long, zone: ZoneId?): List<SyncPlaybackHourTotal> {
    if (zone == null || durationMs <= 0) return emptyList()
    val start = maxOf(day, playedAt - listenedMs)
    return playbackListeningHours(start + durationMs, durationMs, zone).map { hour ->
        SyncPlaybackHourTotal(deviceId, epochStartedAt, maxOf(day, hour.hourStartAt), hour.localHour, hour.durationMs)
    }.toList()
}

internal fun List<SyncPlaybackHourTotal>.addHourIncrements(increments: List<SyncPlaybackHourTotal>): List<SyncPlaybackHourTotal> {
    if (increments.isEmpty()) return this
    val bySlot = associateByTo(mutableMapOf()) { it.slotKey }
    for (hour in increments) {
        val previous = bySlot[hour.slotKey]?.totalListenMs ?: 0L
        bySlot[hour.slotKey] = hour.copy(totalListenMs = SyncPlaybackCounterArithmetic.add(previous, hour.totalListenMs))
    }
    return SyncPlaybackHours.merge(bySlot.values.toList())
}

internal fun PlaybackStatsPendingDeltaEntity.recordedZone(): ZoneId? =
    playedZone?.takeIf { listenedMs in 0..playedAt }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

internal fun listeningDayStart(at: Long, zone: ZoneId?): Long =
    if (zone == null) playbackStatsDayStartAt(at)
    else Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
