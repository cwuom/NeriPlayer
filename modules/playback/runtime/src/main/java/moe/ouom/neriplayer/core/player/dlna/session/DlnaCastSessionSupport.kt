package moe.ouom.neriplayer.core.player.dlna.session

import android.os.SystemClock
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpControlClient
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

internal const val DLNA_POLL_INTERVAL_MS = 1_000L
internal const val DLNA_IDLE_POLL_INTERVAL_MS = 5_000L
internal const val DLNA_EXTERNAL_RESUME_GRACE_MS = 3_000L
internal const val DLNA_END_OF_TRACK_TOLERANCE_MS = 5_000L
internal const val DLNA_STARTUP_PAUSE_GRACE_MS = 12_000L

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

internal fun isStartupPauseGraceActive(
    hasStartedPlayingInGeneration: Boolean,
    playCommandSentInGeneration: Boolean,
    elapsedSincePlayCommandMs: Long
): Boolean = !hasStartedPlayingInGeneration && (
    !playCommandSentInGeneration ||
        elapsedSincePlayCommandMs <= 0L ||
        elapsedSincePlayCommandMs < DLNA_STARTUP_PAUSE_GRACE_MS
)

/**
 * 曲中确认的暂停。部分渲染器（如山灵 EM7）在自然结束时也上报 PAUSED_PLAYBACK（且可能将 RelTime 归零），
 * 切歌启动窗口内也可能短暂残留上一首的 PAUSED_PLAYBACK，这些都不能当成用户暂停去释放保活锁。
 */
internal fun isMidTrackPause(
    transport: DlnaTransportState?,
    positionMs: Long?,
    durationMs: Long?,
    startupGraceActive: Boolean = false,
    lastPlayingPositionMs: Long = 0L,
    elapsedSinceLastPlayingPollMs: Long = 0L,
    playCommandSentInGeneration: Boolean = false,
    playCommandStartPositionMs: Long = 0L,
    elapsedSincePlayCommandMs: Long = 0L
): Boolean {
    if (transport != DlnaTransportState.PAUSED_PLAYBACK || startupGraceActive) return false
    if (durationMs == null || durationMs <= 0L || positionMs == null) return false
    if (DlnaCastStateMachine.isPausedAtTrackEnd(
            positionMs = positionMs,
            durationMs = durationMs,
            lastPlayingPositionMs = lastPlayingPositionMs,
            elapsedSinceLastPlayingPollMs = elapsedSinceLastPlayingPollMs,
            playCommandSentInGeneration = playCommandSentInGeneration,
            playCommandStartPositionMs = playCommandStartPositionMs,
            elapsedSincePlayCommandMs = elapsedSincePlayCommandMs
        )
    ) {
        return false
    }
    return positionMs < durationMs - DLNA_END_OF_TRACK_TOLERANCE_MS
}

internal fun elapsedSinceLastPlayingSampleMs(
    lastPlayingPositionMs: Long,
    lastSampleMonoMs: Long,
    nowMonoMs: Long = monotonicBootElapsedMs()
): Long = if (lastPlayingPositionMs > 0L && lastSampleMonoMs > 0L) {
    (nowMonoMs - lastSampleMonoMs).coerceAtLeast(0L)
} else {
    0L
}

internal fun collectMissingDeviceOptions(
    devices: List<DlnaDevice>,
    existingKeys: Set<String>,
    prefsStore: DlnaPreferencesStore
): Map<String, DlnaDeviceOptions> {
    val missing = devices.filter { it.udn !in existingKeys }
    if (missing.isEmpty()) return emptyMap()
    val additions = LinkedHashMap<String, DlnaDeviceOptions>(missing.size)
    for (device in missing) {
        val defaults = prefsStore.defaultOptionsForDevice(device)
        prefsStore.saveDeviceOptions(device.udn, defaults)
        additions[device.udn] = defaults
    }
    return additions
}

/**
 * 山灵 EM7 等固件在播放中途收到 SetNextAVTransportURI 会偶发中断当前流并回退到 00:01 暂停，
 * 或在曲尾切入 NextURI 后停在 00:00 的 PAUSED_PLAYBACK 不出声；对其仅做本地缓存预热，曲尾由控制器统一切歌下发 Play。
 */
internal fun shouldSendGaplessNextUriToRenderer(device: DlnaDevice): Boolean =
    !device.isLikelyShanlingEm7

/**
 * Play 刚返回时部分渲染器仍处于 TRANSITIONING 打开流阶段，过早发 Seek 会报 701 或打断解码回退到开头；
 * 先等待退出 TRANSITIONING（最多约 2.25s）再发送初始/恢复 Seek。
 */
internal suspend fun awaitReadyForPostPlaySeek(
    device: DlnaDevice,
    controlClient: UpnpControlClient,
    isStale: () -> Boolean,
    desiredPlaying: () -> Boolean
) {
    delay(250L)
    repeat(8) {
        if (isStale() || !desiredPlaying()) return
        val state = controlClient.getTransportState(device).getOrNull()
        if (state != DlnaTransportState.TRANSITIONING) return
        delay(250L)
    }
}

internal class DelegatingLanSocketFactory(
    private val delegateProvider: () -> SocketFactory?
) : SocketFactory() {
    private fun delegate(): SocketFactory = delegateProvider() ?: getDefault()

    override fun createSocket(): Socket = delegate().createSocket()
    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}

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
