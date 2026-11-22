@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.lan.timing.LanHardwareRenderersFactory
import moe.ouom.neriplayer.core.player.lan.timing.LanRoomOutputTiming
import moe.ouom.neriplayer.data.model.lan.LanAction
import moe.ouom.neriplayer.lanltw.audio.LanAudioState
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

internal class LanMedia3AudioEngine(
    context: Context,
    handleAudioFocus: Boolean,
    private val onState: (LanAudioState, Int, Boolean) -> Unit,
    private val onFormat: (Format) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: () -> Unit,
    private val onInterrupted: () -> Unit = {},
    private val onSeekUnavailable: () -> Unit = {},
    private val onRefilled: ((String) -> Unit)? = null,
    private val resumeUnseekableLocally: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val calls = LanProgressiveCalls(http)
    private val timing = LanRoomOutputTiming()
    private val player = ExoPlayer.Builder(context, LanHardwareRenderersFactory(context, timing.hardware)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
            REFILL_THRESHOLD_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_RESUME_MS
        ).build())
        .setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus)
        .build()
    private var generation = 0L
    private var preparing = false
    private var sleepMuted = false
    private val stalls = LanAudioStallDiagnostics()
    private var activeSource: String? = null
    private var reportedAudioFormat: Format? = null
    val mediaId: String? get() = player.currentMediaItem?.mediaId
    val audioFormat: Format? get() = player.audioFormat
    val positionSeconds: Double get() = (timing.upcomingPositionMs(now()) ?: player.currentPosition)
        .coerceIn(0, actualDurationMs() ?: Long.MAX_VALUE) / 1000.0
    val playing: Boolean get() = player.isPlaying && timing.upcomingPositionMs(now()) == null
    val seekable: Boolean get() = player.isCurrentMediaItemSeekable
    val seekableDurationMs: Long? get() = actualDurationMs()
        ?.takeIf { seekable && !player.isCurrentMediaItemDynamic }

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (activeSource != null && stalls.reportRebuffer(
                        playing = player.isPlaying,
                        playWhenReady = player.playWhenReady,
                        buffering = player.playbackState == Player.STATE_BUFFERING
                    )
                ) NPLogger.w(LOG_TAG, "media rebuffer ${diagnostics()}")
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    if (player.playbackState == Player.STATE_BUFFERING) {
                        timing.discardSamples()
                        setCorrectionSpeed(1f)
                    } else if (player.playbackState == Player.STATE_ENDED) clearTiming()
                }
                if (!player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    setCorrectionSpeed(1f)
                }
                handleRefill()
                if (player.isPlaying) timing.resumeOutput(generation, now())
                publish()
                if (activeSource != null && events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED &&
                    player.playWhenReady) {
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!preparing && activeSource != null) onError()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (activeSource != null && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    onInterrupted()
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (activeSource != null && player.playWhenReady &&
                    playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                    player.pause()
                    onInterrupted()
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                if (activeSource == null || !stalls.reportUnderrun()) return
                val sinkBufferMs = bufferSizeMs.takeUnless { it == C.TIME_UNSET }
                NPLogger.w(LOG_TAG, "audio sink underrun sinkBytes=$bufferSize sinkBufferMs=$sinkBufferMs " +
                    "elapsedSinceFeedMs=$elapsedSinceLastFeedMs ${diagnostics()}")
            }
        })
        scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    val rate = timing.speed(generation, now(), player.playbackParameters.speed)
                    setCorrectionSpeed(rate)
                    publish()
                }
                delay(if (player.playWhenReady) 200 else 500)
            }
        }
    }

    fun updateRoomClock(offsetMs: Double, elapsedMs: Long) = timing.updateClock(offsetMs, elapsedMs)

    private fun setCorrectionSpeed(speed: Float) {
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }

    private fun clearTiming() {
        timing.cancel()
        setCorrectionSpeed(1f)
    }

    fun ready(id: String): Boolean = mediaId == id && player.playbackState == Player.STATE_READY && player.playerError == null

    private fun handleRefill() {
        val refilled = onRefilled ?: return
        val id = activeSource ?: return
        if (stalls.awaitingRefill && player.playbackState == Player.STATE_READY && player.playWhenReady) {
            stalls.resetPlaybackTransition()
            if (!resumeUnseekableLocally || seekable) {
                player.pause()
                refilled(id)
            }
        }
        // 让 Media3 按断流阈值继续蓄水，并在新计划到达前静音旧进度
        applyOutputVolume()
    }

    fun muteForSleep(muted: Boolean) {
        sleepMuted = muted
        applyOutputVolume()
        publish()
    }

    private fun applyOutputVolume() {
        val volume = if (sleepMuted || (onRefilled != null && stalls.awaitingRefill)) 0f else 1f
        if (player.volume != volume) player.volume = volume
    }

    suspend fun prepare(id: String, url: HttpUrl, bearer: String, mime: String, positionMs: Long): Boolean {
        val ticket = ++generation
        clearTiming()
        stalls.resetPlaybackTransition()
        preparing = true
        try {
            player.pause()
            activeSource = id
            if (mediaId == id && player.playerError == null && player.playbackState != Player.STATE_IDLE) {
                seekToPosition(positionMs)
            } else {
                reportedAudioFormat = null
                val item = MediaItem.Builder().setMediaId(id).setUri(url.toString()).setMimeType(mime).build()
                calls.replaceLoading {
                    val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                    val source = ProgressiveMediaSource.Factory(calls.sourceFactory(bearer), extractors).createMediaSource(item)
                    player.setMediaSource(source, positionMs)
                    player.prepare()
                }
            }
            return withTimeoutOrNull(PREPARE_TIMEOUT_MS) { awaitReady(id, ticket, positionMs) } == true
        } finally {
            if (generation == ticket) preparing = false
        }
    }

    suspend fun schedule(action: LanAction, deadlineMs: Double, offsetMs: Double, nudgeMs: Int): Boolean {
        val id = mediaId ?: return false
        val ticket = ++generation
        clearTiming()
        stalls.resetPlaybackTransition()
        if (action.type == "PAUSE") {
            val targetMs = (deadlineMs - offsetMs - nudgeMs).roundToLong()
            delay((targetMs - now()).coerceAtLeast(0L))
            if (ticket != generation || mediaId != id) return false
            player.pause()
            seekToPosition((action.trackTimeSeconds * 1000).roundToLong())
            return true
        }
        if (action.type != "PLAY") return false
        player.pause()
        repeat(2) {
            val plan = planLanMedia3Start(now(), deadlineMs, offsetMs, nudgeMs, action.trackTimeSeconds, actualDurationMs())
                ?: return finishMissedTail()
            if (!seekToPosition(plan.positionMs)) {
                onSeekUnavailable()
                return false
            }
            val waitMs = (plan.deadlineMs - now()).coerceAtLeast(1L)
            if (withTimeoutOrNull(waitMs) { awaitReady(id, ticket) } == true) {
                if (ticket != generation || !ready(id)) return false
                timing.arm(ticket, plan, offsetMs, now())
                player.play()
                // 输出线程已接收计划，房间在约定时刻才收到播放完成回报
                try {
                    delay((plan.deadlineMs - now()).coerceAtLeast(0L))
                } catch (cancelled: CancellationException) {
                    if (generation == ticket) cancelSchedule()
                    throw cancelled
                }
                if (generation != ticket || mediaId != id) return false
                if (ready(id) && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) return true
                cancelSchedule()
                return false
            }
            if (ticket != generation || player.playerError != null) return false
        }
        return false
    }

    private fun finishMissedTail(): Boolean {
        player.pause()
        actualDurationMs()?.let(::seekToPosition)
        onEnded()
        return true
    }

    private suspend fun awaitReady(id: String, ticket: Long, requestedMs: Long? = null): Boolean {
        var adjustedEndSeek = false
        while (generation == ticket && player.playerError == null && mediaId == id) {
            if (!adjustedEndSeek && requestedMs != null && requestedMs > clampLanMediaPosition(requestedMs, actualDurationMs())) {
                adjustedEndSeek = true
                seekToPosition(requestedMs)
            }
            if (ready(id)) return true
            delay(READY_POLL_MS)
        }
        return false
    }

    fun seek(positionMs: Long): Boolean {
        clearTiming()
        generation++
        if (player.playWhenReady) timing.hardware.arm(generation, now())
        return seekToPosition(positionMs)
    }

    private fun seekToPosition(positionMs: Long): Boolean {
        val target = clampLanMediaPosition(positionMs, actualDurationMs())
        if (target > 0 && player.playbackState == Player.STATE_READY && !seekable) return false
        val current = player.currentPosition
        if (abs(current - target) <= SEEK_TOLERANCE_MS && player.playbackState != Player.STATE_ENDED) return true
        stalls.resetPlaybackTransition()
        if (target > player.bufferedPosition || target < current - SEEK_TOLERANCE_MS) {
            calls.replaceLoading { player.seekTo(target) }
        } else player.seekTo(target)
        return true
    }

    fun changeNudge(deltaMs: Int) {
        if (!playing || !seekable || deltaMs == 0) return
        if (seekToPosition((positionSeconds * 1000).roundToLong() + deltaMs)) {
            timing.movedByNudge(deltaMs)
            setCorrectionSpeed(1f)
            timing.resumeOutput(++generation, now())
        }
    }

    fun cancelSchedule() {
        generation++
        clearTiming()
        preparing = false
        stalls.resetPlaybackTransition()
        player.pause()
    }

    fun invalidate() {
        cancelSchedule()
        activeSource = null
        reportedAudioFormat = null
        calls.replaceLoading {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun play() {
        clearTiming()
        timing.hardware.arm(++generation, now())
        player.play()
    }

    fun release() {
        scope.cancel()
        generation++
        clearTiming()
        activeSource = null
        calls.replaceLoading { player.stop() }
        player.release()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    fun diagnostics(): String = "[media3 state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
        "playing=${player.isPlaying} positionMs=${player.currentPosition} bufferedMs=${player.totalBufferedDuration} " +
        "outputTiming=${timing.hardware.status} phaseErrorMs=${timing.errorMs} speed=${player.playbackParameters.speed} " +
        "rebuffers=${stalls.rebuffers} underruns=${stalls.underruns}]"

    private fun publish() {
        if (activeSource == null) return
        player.audioFormat?.takeIf { it != reportedAudioFormat }?.let {
            reportedAudioFormat = it
            onFormat(it)
        }
        val playbackRequested = player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED
        onState(LanAudioState(playing = playing && !sleepMuted, positionSeconds = positionSeconds,
            durationSeconds = (actualDurationMs() ?: 0) / 1000.0), player.playbackState, playbackRequested && !sleepMuted)
    }

    private fun actualDurationMs(): Long? = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    private fun now(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val LOG_TAG = "NERI-LanAudio"
        // 首播保持低延迟，播放中提前补充数据，断流后积累足够余量再恢复
        private const val REFILL_THRESHOLD_MS = 15_000
        private const val START_BUFFER_MS = 600
        private const val REBUFFER_RESUME_MS = 5_000
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val READY_POLL_MS = 20L
        private const val SEEK_TOLERANCE_MS = 20L
    }
}
