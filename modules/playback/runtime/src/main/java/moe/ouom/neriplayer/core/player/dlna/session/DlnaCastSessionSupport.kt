package moe.ouom.neriplayer.core.player.dlna.session

import android.os.SystemClock
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState

internal const val DLNA_POLL_INTERVAL_MS = 1_000L
internal const val DLNA_IDLE_POLL_INTERVAL_MS = 5_000L
internal const val DLNA_EXTERNAL_RESUME_GRACE_MS = 3_000L
internal const val DLNA_END_OF_TRACK_TOLERANCE_MS = 5_000L

internal fun monotonicBootElapsedMs(): Long {
    val bootMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
    return if (bootMs > 0L) bootMs else (System.nanoTime() / 1_000_000L).coerceAtLeast(1L)
}

internal fun mergeDiscoveredDevices(
    manualDevices: List<DlnaDevice>,
    scanned: List<DlnaDevice>
): List<DlnaDevice> {
    val map = LinkedHashMap<String, DlnaDevice>()
    for (item in manualDevices) {
        if (item.isManual) {
            map[item.udn] = item.copy(online = false)
        }
    }
    for (item in scanned) {
        map[item.udn] = item
    }
    return map.values.toList()
}

/**
 * 切换输出时判断渲染器上是否真的在播。
 *
 * 用户可能直接在渲染器上暂停，此时 App 侧的播放意图不会变；稳定的传输状态比意图更可信，
 * 只有 TRANSITIONING / UNKNOWN 这类还没定下来的状态才退回到意图。
 */
internal fun resolveRemotePlayingForHandoff(
    transportState: DlnaTransportState,
    desiredRemotePlaying: Boolean
): Boolean = when (transportState) {
    DlnaTransportState.PLAYING -> true
    DlnaTransportState.TRANSITIONING, DlnaTransportState.UNKNOWN -> desiredRemotePlaying
    else -> false
}

/** 用户已暂停或停止时降低轮询频率；命令和 GENA 事件会通过 nudge 立即唤醒轮询 */
internal fun dlnaPollIntervalMs(userPausedOrStopped: Boolean, desiredRemotePlaying: Boolean): Long =
    if (userPausedOrStopped && !desiredRemotePlaying) DLNA_IDLE_POLL_INTERVAL_MS else DLNA_POLL_INTERVAL_MS

internal fun isRequestedLosslessForSong(song: SongItem): Boolean = when {
    PlayerManager.isYouTubeMusicTrack(song) -> false
    PlayerManager.isBiliTrack(song) -> PlayerManager.effectiveBiliQuality() in setOf("lossless", "hires")
    else -> PlayerManager.effectiveNeteaseQuality() in setOf("lossless", "hires", "jyeffect", "sky", "jymaster")
}

/**
 * 曲中确认的暂停。部分渲染器（如山灵 EM7）在自然结束时也上报 PAUSED_PLAYBACK，
 * 那是切下一首的时机，不能当成用户暂停去释放保活；曲长未知时也按保守处理。
 */
internal fun isMidTrackPause(transport: DlnaTransportState?, positionMs: Long?, durationMs: Long?): Boolean =
    transport == DlnaTransportState.PAUSED_PLAYBACK &&
        durationMs != null && durationMs > 0L &&
        positionMs != null && positionMs < durationMs - DLNA_END_OF_TRACK_TOLERANCE_MS

/** 锁跟随渲染器实际状态：渲染器上直接恢复播放要拿回锁（否则 Wi-Fi 省电导致拉流卡顿），暂停后再释放 */
internal fun DlnaNetworkAndLockController.followTransport(
    transport: DlnaTransportState?,
    desiredRemotePlaying: Boolean,
    confirmedMidTrackPause: Boolean = false
) {
    when (transport) {
        DlnaTransportState.PLAYING -> acquireCastLocks()
        DlnaTransportState.PAUSED_PLAYBACK ->
            if (!desiredRemotePlaying || confirmedMidTrackPause) releaseCastLocks()
        DlnaTransportState.STOPPED -> if (!desiredRemotePlaying) releaseCastLocks()
        else -> Unit
    }
}

/**
 * 用户在渲染器上直接恢复了 App 侧暂停的曲目时，应解除暂停锁存，否则不会预取下一首、到曲尾也不推进队列。
 * 刚按下暂停的几秒内，在途的轮询仍可能读到 PLAYING，这段时间不算外部恢复。
 */
internal fun shouldAdoptExternalResume(
    transport: DlnaTransportState?,
    currentTrackLoaded: Boolean,
    userPausedOrStopped: Boolean,
    desiredRemotePlaying: Boolean,
    nowMs: Long,
    lastUserPauseAtMs: Long
): Boolean = transport == DlnaTransportState.PLAYING &&
    currentTrackLoaded &&
    userPausedOrStopped &&
    !desiredRemotePlaying &&
    nowMs - lastUserPauseAtMs >= DLNA_EXTERNAL_RESUME_GRACE_MS
