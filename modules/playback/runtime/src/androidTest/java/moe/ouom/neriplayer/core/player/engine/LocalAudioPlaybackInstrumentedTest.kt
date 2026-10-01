@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.core.player.lifecycle.buildAudioLoadControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioPlaybackInstrumentedTest {

    @Test(timeout = 90_000L)
    fun localAudioReachesReadyAndAdvances() {
        val arguments = InstrumentationRegistry.getArguments()
        val sourcePath = arguments.getString("localAudioSamplePath")
        assumeTrue("localAudioSamplePath is required", !sourcePath.isNullOrBlank())
        val source = File(requireNotNull(sourcePath))
        require(source.isAbsolute) { "localAudioSamplePath must be an absolute device path" }
        val expectedMimeType = arguments.getString("expectedAudioMimeType")
            ?.takeIf { it.isNotBlank() }
        val startPositionMs = arguments.getString("startPositionMs")?.toLong() ?: 0L
        val minimumPositionAdvanceMs = arguments.getString("minimumPositionAdvanceMs")
            ?.toLong() ?: 1_000L
        val timeoutMs = arguments.getString("playbackTimeoutMs")?.toLong() ?: 10_000L
        require(startPositionMs in 0L..3_600_000L)
        require(minimumPositionAdvanceMs in 1L..60_000L)
        require(timeoutMs in 1_000L..60_000L)
        val extension = source.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File.createTempFile("local-audio-playback-", ".${extension ?: "audio"}", context.cacheDir)
        val probe = LocalAudioPlaybackProbe(startPositionMs + minimumPositionAdvanceMs)
        val completed: Boolean
        val released: Boolean
        try {
            copySample(source, sample)
            probe.start(sample, startPositionMs)
            completed = probe.await(timeoutMs)
        } finally {
            released = probe.close()
            assertTrue("Cannot remove copied playback sample", sample.delete())
        }

        val result = probe.snapshot.get()
        val diagnostic = "completed=$completed released=$released $result"
        Log.i("NERI-LocalAudioPlaybackTest", diagnostic)
        assertTrue("Playback timed out: $diagnostic", completed)
        assertNull("Playback failed: $diagnostic", result.failure)
        assertNull("Audio sink reported an error: $diagnostic", result.lastSinkError)
        assertTrue("Player never reached READY: $diagnostic", result.readySeen)
        assertTrue("Audio decoder was bypassed: $diagnostic", !result.decoderName.isNullOrBlank())
        assertTrue("Audio input format was not reported: $diagnostic", !result.inputMimeType.isNullOrBlank())
        if (expectedMimeType != null) {
            assertEquals("Unexpected audio input format: $diagnostic", expectedMimeType, result.inputMimeType)
        }
        assertTrue(
            "AudioTrack did not receive PCM: $diagnostic",
            result.outputEncoding?.let(Util::isEncodingLinearPcm) == true
        )
        assertTrue("Audio sink did not consume output: $diagnostic", result.renderedOutputBuffers > 0)
        assertTrue("Audio output position did not advance: $diagnostic", result.audioPositionAdvancing)
        assertTrue(
            "Playback position did not reach its target: $diagnostic",
            result.positionMs >= startPositionMs + minimumPositionAdvanceMs
        )
        assertTrue("Player release timed out: $diagnostic", released)
        assertNull("Player release failed: $diagnostic", probe.releaseFailure.get())
    }

    private fun copySample(source: File, target: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = if (source.isFile && source.canRead()) {
            source.inputStream()
        } else {
            // shell 可读取 /data/local/tmp, 复制到应用缓存后无需外部存储权限
            val quotedPath = "'${source.absolutePath.replace("'", "'\\''")}'"
            val command = "if [ -f $quotedPath ]; then head -c 67108865 $quotedPath; fi"
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            )
        }
        input.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var copiedBytes = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    copiedBytes += count
                    require(copiedBytes <= 64L * 1024L * 1024L) { "Playback sample exceeds 64 MiB" }
                    output.write(buffer, 0, count)
                }
                require(copiedBytes > 0L) { "Playback sample is missing, unreadable, or empty" }
            }
        }
    }
}

private class LocalAudioPlaybackProbe(private val targetPositionMs: Long) :
    Player.Listener, AnalyticsListener {

    val snapshot = AtomicReference(LocalAudioPlaybackSnapshot())
    val releaseFailure = AtomicReference<Throwable?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private var player: ExoPlayer? = null
    private var readySeen = false
    private var inputMimeType: String? = null
    private var decoderName: String? = null
    private var outputEncoding: Int? = null
    private var outputSampleRate = 0
    private var audioPositionAdvancing = false
    private var lastSinkError: String? = null
    private var failure: Throwable? = null
    private val states = mutableListOf<Int>()
    private val startTimeMs = SystemClock.elapsedRealtime()
    private val startCpuMs = Process.getElapsedCpuTime()
    private val poll = object : Runnable {
        override fun run() {
            if (closed.get() || finished.count == 0L) return
            try {
                updateSnapshot()
                val result = snapshot.get()
                if (result.failure != null || player?.playbackState == Player.STATE_ENDED ||
                    (result.readySeen && result.positionMs >= targetPositionMs &&
                        result.audioPositionAdvancing && result.renderedOutputBuffers > 0)
                ) {
                    finished.countDown()
                } else {
                    mainHandler.postDelayed(this, 100L)
                }
            } catch (error: Throwable) {
                failure = error
                snapshot.set(snapshot.get().copy(failure = error))
                finished.countDown()
            }
        }
    }

    fun start(sample: File, startPositionMs: Long) {
        mainHandler.post {
            if (closed.get()) return@post
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val renderers = ReactiveRenderersFactory(context)
                    .setEnableAudioFloatOutput(false)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                val mediaSources = DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(context),
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                )
                val exoPlayer = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(mediaSources)
                    .setLoadControl(buildAudioLoadControl())
                    .setReleaseTimeoutMs(1_000L)
                    .build()
                player = exoPlayer
                exoPlayer.addListener(this)
                exoPlayer.addAnalyticsListener(this)
                // 本地播放默认允许 offload, 必须覆盖该路径上的错误压缩直通
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                            )
                            .build()
                    )
                    .build()
                exoPlayer.volume = 0f
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(sample)), startPositionMs)
                exoPlayer.prepare()
                exoPlayer.play()
                mainHandler.post(poll)
            } catch (error: Throwable) {
                failure = error
                snapshot.set(snapshot.get().copy(failure = error))
                finished.countDown()
            }
        }
    }

    fun await(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun close(): Boolean {
        closed.set(true)
        mainHandler.removeCallbacks(poll)
        val released = CountDownLatch(1)
        mainHandler.post {
            try {
                player?.release()
                player = null
            } catch (error: Throwable) {
                releaseFailure.set(error)
            } finally {
                released.countDown()
            }
        }
        return released.await(3_000L, TimeUnit.MILLISECONDS)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        readySeen = readySeen || playbackState == Player.STATE_READY
        if (states.size < 20) states += playbackState
    }

    override fun onPlayerError(error: PlaybackException) {
        failure = error
        updateSnapshot()
        finished.countDown()
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        inputMimeType = format.sampleMimeType
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        this.decoderName = decoderName
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        outputEncoding = audioTrackConfig.encoding
        outputSampleRate = audioTrackConfig.sampleRate
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long
    ) {
        audioPositionAdvancing = true
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        lastSinkError = audioSinkError.toString()
    }

    private fun updateSnapshot() {
        val exoPlayer = player ?: return
        val counters = exoPlayer.audioDecoderCounters
        counters?.ensureUpdated()
        snapshot.set(
            LocalAudioPlaybackSnapshot(
                readySeen = readySeen,
                inputMimeType = inputMimeType,
                decoderName = decoderName,
                outputEncoding = outputEncoding,
                outputSampleRate = outputSampleRate,
                audioPositionAdvancing = audioPositionAdvancing,
                positionMs = exoPlayer.currentPosition,
                bufferedPositionMs = exoPlayer.bufferedPosition,
                queuedInputBuffers = counters?.queuedInputBufferCount ?: 0,
                renderedOutputBuffers = counters?.renderedOutputBufferCount ?: 0,
                states = states.toList(),
                elapsedMs = SystemClock.elapsedRealtime() - startTimeMs,
                processCpuMs = Process.getElapsedCpuTime() - startCpuMs,
                lastSinkError = lastSinkError,
                failure = failure ?: exoPlayer.playerError
            )
        )
    }
}

private data class LocalAudioPlaybackSnapshot(
    val readySeen: Boolean = false,
    val inputMimeType: String? = null,
    val decoderName: String? = null,
    val outputEncoding: Int? = null,
    val outputSampleRate: Int = 0,
    val audioPositionAdvancing: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val queuedInputBuffers: Int = 0,
    val renderedOutputBuffers: Int = 0,
    val states: List<Int> = emptyList(),
    val elapsedMs: Long = 0L,
    val processCpuMs: Long = 0L,
    val lastSinkError: String? = null,
    val failure: Throwable? = null
)
