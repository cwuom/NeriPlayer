package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class PlaybackListeningHour(val startAt: Long, val localHour: Int, val durationMs: Long)

internal fun playbackListeningHours(endAt: Long, listenedMs: Long, zone: ZoneId): Sequence<PlaybackListeningHour> = sequence {
    if (listenedMs !in 1..endAt) return@sequence
    var cursor = endAt - listenedMs
    while (cursor < endAt) {
        val instant = Instant.ofEpochMilli(cursor)
        val local = instant.atZone(zone)
        val topOfHour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val nextHour = topOfHour.plusHours(1).toInstant(local.offset).toEpochMilli()
        // 某些地区在半点切换夏令时，先结束当前偏移下的这段计时
        val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        val next = minOf(endAt, nextHour, transition)
        val start = local.truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
        yield(PlaybackListeningHour(start, local.hour, next - cursor))
        cursor = next
    }
}

internal fun PlaybackStatsPendingDeltaEntity.recordedZone(): ZoneId? =
    playedZone?.takeIf { listenedMs in 0..playedAt }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

internal fun listeningDayStart(at: Long, zone: ZoneId?): Long =
    if (zone == null) playbackStatsDayStartAt(at)
    else Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
