@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan.timing

import android.media.AudioManager
import android.media.AudioTimestamp
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import java.nio.ByteBuffer

internal class LanHardwareAudioOutput(
    delegate: AudioTrackAudioOutput,
    private val config: AudioOutputProvider.OutputConfig,
    private val routes: AudioManager,
    private val clock: LanHardwareAudioClock
) : ForwardingAudioOutput(delegate) {
    private val track = delegate.audioTrack
    private val rate = config.sampleRate
    private val frameBytes = if (!config.isOffload && !config.isTunneling && Util.isEncodingLinearPcm(config.encoding)) {
        Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask))
    } else 0
    private val handler = Handler(checkNotNull(Looper.myLooper()))
    private val startWhenDue = Runnable { startWhenDue() }
    private val timestamp = AudioTimestamp()
    private var queuedBytes = 0L
    private var outputUs = 0L
    private var pending: LanHardwarePlan? = null
    private var started: LanHardwarePlan? = null
    private var adopting: LanHardwarePlan? = null
    private var route: LanPcmRoute? = null
    private var lastStamp: LanPcmStamp? = null
    private var lastPollNanos = 0L
    private var playNanos = 0L
    private var startFrame = 0L
    private var learning = false
    private var running = false
    private var stopAfterStart = false

    override fun play() {
        val ticket = clock.plan ?: return
        if (running && started === ticket || pending === ticket) return
        clearPending()
        pending = ticket
        startWhenDue()
    }

    private fun startWhenDue() {
        val ticket = pending ?: return
        if (ticket !== clock.plan) {
            clearPending()
            return
        }
        val remaining = ticket.deadlineElapsedMs - SystemClock.elapsedRealtime()
        // 等待期间允许 Media3 继续向暂停的 AudioTrack 预填数据
        if (remaining > 250) {
            handler.postDelayed(startWhenDue, remaining - 250)
            return
        }
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        val device = predictedRoute()
        val key = device?.let { routeKey(it, head) }
        val enoughPcm = hasPreroll(head)
        val wait = clock.history.waitMs(remaining, enoughPcm, key)
        if (wait > 0) {
            handler.postDelayed(startWhenDue, wait.coerceAtMost(25L))
            return
        }
        pending = null
        val mode = if (remaining > 0) "calibrated" else "cold"
        val didStart = clock.start(ticket, mode) {
            playNanos = System.nanoTime()
            super.play()
        }
        if (!didStart) return
        running = true
        started = ticket
        startFrame = head
        route = key
        lastStamp = null
        learning = enoughPcm
        if (stopAfterStart) {
            stopAfterStart = false
            stop()
        }
    }

    private fun predictedRoute(): Int? {
        if (frameBytes == 0) return null
        actualRoute()?.let { return it }
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            routes.getAudioDevicesForAttributes(track.audioAttributes).singleOrNull()?.id
        } catch (_: SecurityException) {
            null
        }
    }

    private fun routeKey(device: Int, head: Long) =
        LanPcmRoute(device, rate, bufferSizeInFrames, config.encoding, config.channelMask, head != 0L)

    private fun actualRoute(): Int? =
        if (Build.VERSION.SDK_INT >= 36) track.routedDevices.singleOrNull()?.id else track.routedDevice?.id

    private fun hasPreroll(head: Long): Boolean {
        if (frameBytes <= 0) return false
        val threshold = if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames.toLong() else bufferSizeInFrames
        return queuedBytes / frameBytes - head >= threshold.coerceAtLeast(1)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val before = buffer.remaining()
        val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        queuedBytes += before - buffer.remaining()
        return handled
    }

    override fun getPositionUs(): Long = super.getPositionUs().also { outputUs = it }

    fun observeMediaPosition(mediaUs: Long) {
        if (!running || frameBytes <= 0) return
        val ticket = clock.plan ?: return
        val pollNanos = System.nanoTime()
        if (pollNanos - lastPollNanos < 25_000_000L) return
        lastPollNanos = pollNanos
        if (started !== ticket) {
            if (ticket.deadlineElapsedMs > SystemClock.elapsedRealtime()) return
            adoptAfterPendingSeek(ticket)
            return
        }
        val device = actualRoute() ?: return
        if (route?.id != device) {
            if (route != null) learning = false
            route = routeKey(device, startFrame)
            lastStamp = null
            clock.cleared(ticket)
        }
        if (!track.getTimestamp(timestamp)) return
        val stamp = LanPcmStamp(timestamp.framePosition, timestamp.nanoTime)
        val previous = lastStamp
        if (stamp == previous) return
        lastStamp = stamp
        val submittedFrames = queuedBytes / frameBytes
        if (stamp.frame !in startFrame..submittedFrames || previous == null || !stamp.follows(previous, rate)) return
        // 先在输出时间轴对齐媒体位置和硬件帧，再转换到房间使用的时基
        val boundedUs = outputUs.coerceAtMost(submittedFrames * 1_000_000 / rate)
        val before = SystemClock.elapsedRealtimeNanos()
        val monotonic = System.nanoTime()
        val after = SystemClock.elapsedRealtimeNanos()
        if (after - before > 2_000_000L) return
        val elapsed = stamp.elapsedAtOutputUs(boundedUs, rate, monotonic, before + (after - before) / 2) ?: return
        if (learning) {
            stamp.startupLatencyMs(startFrame, rate, playNanos)?.let { clock.history.record(checkNotNull(route), it) }
            learning = false
        }
        clock.observed(ticket, LanHardwareObservation(elapsed, mediaUs / 1000.0, device))
    }

    private fun adoptAfterPendingSeek(ticket: LanHardwarePlan) {
        if (adopting === ticket) return
        adopting = ticket
        // 小幅调整可能继续使用原输出，等待已入队的 seek 先清空旧帧
        handler.post {
            if (adopting === ticket && running && clock.plan === ticket) {
                started = ticket
                learning = false
                lastStamp = null
            }
            if (adopting === ticket) adopting = null
        }
    }

    private fun clearPending() {
        handler.removeCallbacks(startWhenDue)
        pending = null
    }

    private fun clearRunning() {
        clearPending()
        clock.cleared(started)
        running = false
        started = null
        adopting = null
        lastStamp = null
    }

    override fun pause() {
        clearRunning()
        super.pause()
    }

    override fun flush() {
        clearRunning()
        queuedBytes = 0
        outputUs = 0
        stopAfterStart = false
        super.flush()
    }

    override fun stop() {
        if (pending != null || (!running && queuedBytes > 0)) {
            // 短音频可能在首声前就已写完，等输出开始后再排空
            stopAfterStart = true
            return
        }
        clearRunning()
        super.stop()
    }

    override fun release() {
        clearRunning()
        super.release()
    }
}
