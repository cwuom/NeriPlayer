package moe.ouom.neriplayer.core.lyricon

/** Lyricon 独立位置推送周期, 与 UI 进度刷新解耦 */
internal const val LYRICON_FEED_INTERVAL_MS = 200L

/**
 * 推给 Lyricon/词幕的显示预推 (媒体时间轴)
 * 锚点用真实媒体进度, 仅显示层加 lead, 避免 dead-reckon 叠推
 */
internal const val LYRICON_DISPLAY_LEAD_MS = 750L

/**
 * 与高级歌词 resolveInterpolatedPlaybackPosition 同构的媒体锚点
 * 媒体 position + 墙钟流逝 * playbackSpeed
 */
internal data class LyriconPositionAnchor(
    val positionMs: Long,
    val elapsedRealtimeNanos: Long,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)

/**
 * 对齐高级歌词插值: anchor + elapsedRealtime * speed
 * 无有效锚点时返回 null
 */
internal fun resolveLyriconFeedPosition(
    anchor: LyriconPositionAnchor?,
    nowElapsedRealtimeNanos: Long,
): Long? {
    if (anchor == null) return null
    val elapsedNanos = (nowElapsedRealtimeNanos - anchor.elapsedRealtimeNanos).coerceAtLeast(0L)
    val speed = normalizeLyriconPlaybackSpeed(anchor.playbackSpeed)
    val predictedDeltaMs = ((elapsedNanos / 1_000_000.0) * speed).toLong()
    return clampLyriconPositionMs(anchor.positionMs + predictedDeltaMs, anchor.durationMs)
}

/**
 * 倍速变化: 先按旧速推到 now, 再以新速重锚
 */
internal fun rebaseLyriconPositionAnchor(
    anchor: LyriconPositionAnchor?,
    newSpeed: Float,
    nowElapsedRealtimeNanos: Long,
    durationMs: Long,
): LyriconPositionAnchor? {
    val speed = normalizeLyriconPlaybackSpeed(newSpeed)
    val currentPositionMs = resolveLyriconFeedPosition(anchor, nowElapsedRealtimeNanos) ?: return null
    return LyriconPositionAnchor(
        positionMs = currentPositionMs,
        elapsedRealtimeNanos = nowElapsedRealtimeNanos,
        durationMs = durationMs.coerceAtLeast(0L),
        playbackSpeed = speed,
    )
}

/**
 * 进度环写入锚点: 严格媒体进度 (与高级歌词同源)
 */
internal fun mediaLyriconPositionMs(
    positionMs: Long,
    durationMs: Long,
): Long {
    return clampLyriconPositionMs(positionMs, durationMs)
}

/**
 * 推给 Lyricon/SuperLyric 的显示位置: 先应用歌词偏移, 再加固定 lead
 * 偏移与应用内歌词一样先在零点截断, lead 不进锚点, 避免叠推
 */
internal fun displayLyriconPositionMs(
    mediaPositionMs: Long,
    durationMs: Long,
    leadMs: Long = LYRICON_DISPLAY_LEAD_MS,
    lyricOffsetMs: Long = 0L,
): Long {
    val lyricPositionMs = saturatingAddLyriconPositionMs(
        mediaPositionMs.coerceAtLeast(0L),
        lyricOffsetMs,
    ).coerceAtLeast(0L)
    return clampLyriconPositionMs(
        positionMs = saturatingAddLyriconPositionMs(
            lyricPositionMs,
            leadMs.coerceAtLeast(0L),
        ),
        durationMs = durationMs,
    )
}

private fun saturatingAddLyriconPositionMs(value: Long, delta: Long): Long {
    return when {
        delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }
}

/**
 * 与高级歌词一致: 有限 speed 取 max(0), 非法回退 1
 * 0 表示冻结前进
 */
internal fun normalizeLyriconPlaybackSpeed(speed: Float): Float {
    return if (speed.isFinite()) speed.coerceAtLeast(0f) else 1f
}

internal fun clampLyriconPositionMs(positionMs: Long, durationMs: Long): Long {
    val safePosition = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0L) {
        safePosition.coerceAtMost(durationMs)
    } else {
        safePosition
    }
}
