package moe.ouom.neriplayer.core.player.service.lyrics

import kotlin.math.abs

/**
 * 系统不会为 ProgressStyle 插值，进度只能靠重发推进；每秒一格与计时器同步走字，
 * 加上媒体通知也远低于每包每秒 5 次的入队上限
 */
internal const val LIVE_UPDATE_PROGRESS_TICK_MS = 1_000L
/** 播放器在后台最慢 1.5 s 推送一次位置；更久没有新位置多半是缓冲卡住，停止外推以免进度回跳 */
internal const val LIVE_UPDATE_MAX_EXTRAPOLATION_MS = 2_500L
/** 计时器与真实进度偏差超过该值才视为跳转，避免每个进度节拍都改写 when 导致重发 */
internal const val LIVE_UPDATE_CHRONOMETER_DRIFT_TOLERANCE_MS = 1_500L
/** 进程被杀后通知仍会残留，用系统超时兜底；正常播放时每次重发都会刷新超时 */
internal const val LIVE_UPDATE_PLAYING_TIMEOUT_MARGIN_MS = 5 * 60_000L
/** 暂停态由应用自己收起；系统超时只作兜底且要晚于应用，以免被当作用户划掉 */
internal const val LIVE_UPDATE_PAUSED_TIMEOUT_GRACE_MS = 30_000L

/** 播放器推送给 Live Update 的一帧状态 */
internal data class LiveUpdateFrame(
    val songKey: String,
    val songTitle: String,
    val artist: String,
    val lyric: String?,
    val translation: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float = 1f,
    /** 引擎是否真的在走；playing 含播放意图，缓冲时为 true 但位置不动 */
    val advancing: Boolean = playing,
) {
    /** 倍速改变播放位置随时间前进的速率；异常值按 1× 处理 */
    val effectiveSpeed: Float
        get() = playbackSpeed.takeIf { it > 0f && it.isFinite() } ?: 1f

    /** 只有引擎真正前进时才外推进度和走计时器；缓冲时按钮仍是暂停，但进度原地不动 */
    val clockRunning: Boolean
        get() = playing && advancing
}

internal data class LiveUpdateProgress(val max: Int, val value: Int)

/** 与发布时刻无关的展示内容；两帧内容相等时不必重发通知 */
internal data class LiveUpdateContent(
    val songKey: String,
    val title: String,
    val text: String?,
    val subText: String?,
    val chipText: String?,
    val playing: Boolean,
    val progress: LiveUpdateProgress?,
)

internal fun resolveLiveUpdateContent(
    frame: LiveUpdateFrame,
    fallbackTitle: String,
    chipMeasurer: LiveUpdateTextMeasurer,
    chipMaxWidthEm: Float = LIVE_UPDATE_CHIP_MAX_WIDTH_EM,
): LiveUpdateContent {
    val songTitle = normalizeLiveUpdateText(frame.songTitle) ?: fallbackTitle
    val artist = normalizeLiveUpdateText(frame.artist)
    val songLine = if (artist != null) "$songTitle - $artist" else songTitle
    // 歌词只在播放时占据标题；暂停后回到歌曲信息，避免锁屏长期停留在某一句
    val lyric = normalizeLiveUpdateText(frame.lyric)?.takeIf { frame.playing }
    val translation = lyric?.let { normalizeLiveUpdateText(frame.translation) }
    return LiveUpdateContent(
        songKey = frame.songKey,
        title = lyric ?: songTitle,
        text = when {
            translation != null -> translation
            lyric != null -> songLine
            else -> artist
        },
        subText = songLine.takeIf { translation != null },
        chipText = fitLiveUpdateChipText(lyric ?: songTitle, chipMaxWidthEm, chipMeasurer),
        playing = frame.playing,
        progress = resolveLiveUpdateProgress(frame.positionMs, frame.durationMs),
    )
}

/** 以秒为单位：同一秒内的多次推送不重发，跨秒时进度条与计时器一起前进 */
internal fun resolveLiveUpdateProgress(positionMs: Long, durationMs: Long): LiveUpdateProgress? {
    if (durationMs <= 0L) return null
    val maxSeconds = (durationMs / 1_000L).toInt().coerceAtLeast(1)
    val positionSeconds = (positionMs.coerceIn(0L, durationMs) / 1_000L).toInt()
    return LiveUpdateProgress(max = maxSeconds, value = positionSeconds.coerceAtMost(maxSeconds))
}

/** 两次播放器推送之间按经过的时间与倍速外推播放位置，进度条才能逐秒前进 */
internal fun extrapolateLiveUpdatePosition(frame: LiveUpdateFrame, elapsedSinceFrameMs: Long): Long {
    if (!frame.clockRunning) return frame.positionMs
    val elapsedMs = elapsedSinceFrameMs.coerceIn(0L, LIVE_UPDATE_MAX_EXTRAPOLATION_MS)
    val advanced = frame.positionMs + (elapsedMs * frame.effectiveSpeed).toLong()
    return if (frame.durationMs > 0L) advanced.coerceAtMost(frame.durationMs) else advanced
}

/**
 * 播放起点在开机时钟与墙钟上的对应时刻；墙钟只用于通知 when，节拍按不受改时间影响的开机时钟走。
 * 位置 = (现在 - elapsedMs) × speed。
 */
internal data class LiveUpdateClockAnchor(val elapsedMs: Long, val wallMs: Long, val speed: Float = 1f) {
    /** 系统计时器按墙钟 1× 走字，倍速播放时会与真实进度越走越远，只能不显示 */
    val drivesChronometer: Boolean get() = speed == 1f
}

/**
 * 锚点只在跳转、暂停、倍速变化或漂移过大时更换，未换时原样复用，避免两次读时钟的毫秒差导致重发。
 * 切歌时不要传入上一首的锚点，否则新歌开头可能被当作抖动沿用旧锚点。
 */
internal fun resolveLiveUpdateClockAnchor(
    previous: LiveUpdateClockAnchor?,
    nowElapsedMs: Long,
    nowWallMs: Long,
    positionMs: Long,
    playing: Boolean,
    speed: Float = 1f,
): LiveUpdateClockAnchor? {
    val reusable = previous?.takeIf { it.speed == speed }
    val baseElapsedMs = resolveLiveUpdateChronometerBase(reusable?.elapsedMs, nowElapsedMs, positionMs, playing, speed)
        ?: return null
    if (reusable != null && baseElapsedMs == reusable.elapsedMs) return reusable
    return LiveUpdateClockAnchor(
        elapsedMs = baseElapsedMs,
        wallMs = nowWallMs - (nowElapsedMs - baseElapsedMs),
        speed = speed,
    )
}

/**
 * 播放中按锚点换算进度：进度条与计时器同一节奏跨秒，不受播放器推送时机忽早忽晚的影响。
 * 结果不超过外推上限，播放器久未推送（卡顿或主线程延迟）时停在原地而不是凭锚点继续前进。
 */
internal fun resolveLiveUpdateClockPosition(
    anchor: LiveUpdateClockAnchor?,
    nowElapsedMs: Long,
    frame: LiveUpdateFrame,
    extrapolatedMs: Long,
): Long {
    anchor ?: return extrapolatedMs
    val limitMs = extrapolateLiveUpdatePosition(frame, LIVE_UPDATE_MAX_EXTRAPOLATION_MS).coerceAtLeast(0L)
    return ((nowElapsedMs - anchor.elapsedMs) * anchor.speed).toLong().coerceIn(0L, limitMs)
}

/** 距离下一个整秒的时长；刷新按锚点对齐整秒，1× 时每次重发恰好前进一格 */
internal fun msUntilNextLiveUpdateProgressTick(clockMs: Long): Long =
    LIVE_UPDATE_PROGRESS_TICK_MS - clockMs.coerceAtLeast(0L) % LIVE_UPDATE_PROGRESS_TICK_MS

/**
 * 计时器自行走字，只有暂停、恢复或跳转时才需要新锚点；与时钟种类无关，只要前后用同一个时钟。
 * 锚点满足 位置 = (现在 - 锚点) × speed，漂移按播放位置计算。
 * @return 新的锚点；暂停时返回 null 表示不显示计时器
 */
internal fun resolveLiveUpdateChronometerBase(
    previousBaseMs: Long?,
    nowMs: Long,
    positionMs: Long,
    playing: Boolean,
    speed: Float = 1f,
): Long? {
    if (!playing) return null
    val expectedBase = nowMs - (positionMs.coerceAtLeast(0L) / speed).toLong()
    if (previousBaseMs != null &&
        abs(previousBaseMs - expectedBase) * speed <= LIVE_UPDATE_CHRONOMETER_DRIFT_TOLERANCE_MS
    ) {
        return previousBaseMs
    }
    return expectedBase
}

/** @return 通知的系统超时；0 表示不设置。剩余时长按倍速换算成墙钟时间，慢速播放时不会提前超时 */
internal fun resolveLiveUpdateTimeoutMs(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    pausedLingerMs: Long,
    speed: Float = 1f,
): Long {
    if (!playing) return pausedLingerMs.coerceAtLeast(0L) + LIVE_UPDATE_PAUSED_TIMEOUT_GRACE_MS
    if (durationMs <= 0L) return 0L
    val remainingMs = (durationMs - positionMs.coerceIn(0L, durationMs)).coerceAtLeast(0L)
    return (remainingMs / speed).toLong() + LIVE_UPDATE_PLAYING_TIMEOUT_MARGIN_MS
}

/** Build.VERSION_CODES_FULL.BAKLAVA_1；自定义常量避免在低版本上引用 API 36 的类 */
private const val SDK_INT_FULL_BAKLAVA_1 = 3_600_001

/**
 * Android 16.0 的旧提升规则要求声明 colorized，16.1 起改为要求 requestPromotedOngoing
 * 且声明 colorized 反而失去资格；非前台服务通知声明 colorized 不会真的被着色。
 */
internal fun requiresLegacyColorizedPromotion(sdkInt: Int, sdkIntFull: Int): Boolean =
    sdkInt == 36 && sdkIntFull < SDK_INT_FULL_BAKLAVA_1
