package moe.ouom.neriplayer.core.player.usb.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveBackgroundAudioAnchorSpec
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveBackgroundAudioAnchorTransferMode
import moe.ouom.neriplayer.core.player.policy.usb.usbExclusiveBackgroundAudioAnchorSpecs
import java.util.concurrent.atomic.AtomicBoolean

internal object UsbExclusiveBackgroundAudioAnchor {
    private const val TAG = "NERI-UsbAudioAnchor"
    private const val BYTES_PER_SAMPLE = 2
    private const val STREAM_BUFFER_MULTIPLIER = 2

    private data class ActiveAnchor(
        val track: AudioTrack,
        val spec: UsbExclusiveBackgroundAudioAnchorSpec,
        val silence: ByteArray,
        val volumeGuardToken: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken?,
        var streamWriterRunning: AtomicBoolean? = null,
        var streamWriter: Thread? = null
    )

    private val lock = Any()
    private var activeAnchor: ActiveAnchor? = null

    fun start(context: Context, reason: String): Boolean {
        synchronized(lock) {
            val existing = activeAnchor
            if (existing != null && existing.track.state == AudioTrack.STATE_INITIALIZED) {
                return resume(existing, reason)
            }
            releaseLocked("replace_unusable:$reason")

            val volumeGuardToken = UsbExclusiveBackgroundAudioAnchorVolumeGuard.acquire(context)
            val created = createAnchor(context, reason, volumeGuardToken)
            if (created == null) {
                UsbExclusiveBackgroundAudioAnchorVolumeGuard.release(volumeGuardToken)
                return false
            }
            activeAnchor = created
            return resume(created, reason)
        }
    }

    fun stop(reason: String) {
        synchronized(lock) {
            releaseLocked(reason)
        }
    }

    fun isActive(): Boolean {
        return synchronized(lock) {
            activeAnchor?.track?.let {
                it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING
            } == true
        }
    }

    private fun createAnchor(
        context: Context,
        reason: String,
        volumeGuardToken: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken?
    ): ActiveAnchor? {
        for (spec in usbExclusiveBackgroundAudioAnchorSpecs()) {
            val anchor = createAnchor(context, reason, spec, volumeGuardToken)
            if (anchor != null) return anchor
        }
        NPLogger.w(TAG, "no compatible silent media anchor reason=$reason")
        return null
    }

    private fun createAnchor(
        context: Context,
        reason: String,
        spec: UsbExclusiveBackgroundAudioAnchorSpec,
        volumeGuardToken: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken?
    ): ActiveAnchor? {
        val channelMask = when (spec.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null
        }
        val bufferBytes = resolveBufferBytes(spec, channelMask)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(spec.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(platformTransferMode(spec.transferMode))
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.onFailure { error ->
            NPLogger.w(TAG, "create failed reason=$reason spec=${spec.name}", error)
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            NPLogger.w(TAG, "create returned uninitialized track reason=$reason spec=${spec.name}")
            releaseTrack(track)
            return null
        }
        preferBuiltInOutput(context, track)
        val silence = ByteArray(bufferBytes)
        val initialized = initializeTrack(track, spec, silence)
        if (!initialized) {
            NPLogger.w(TAG, "initialize rejected reason=$reason spec=${spec.name}")
            releaseTrack(track)
            return null
        }
        return ActiveAnchor(
            track = track,
            spec = spec,
            silence = silence,
            volumeGuardToken = volumeGuardToken
        )
    }

    private fun resolveBufferBytes(
        spec: UsbExclusiveBackgroundAudioAnchorSpec,
        channelMask: Int
    ): Int {
        val requestedBytes = spec.bufferFrames * spec.channelCount * BYTES_PER_SAMPLE
        if (spec.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop) {
            return requestedBytes
        }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            spec.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(0)
        return maxOf(requestedBytes, minBufferBytes * STREAM_BUFFER_MULTIPLIER)
    }

    private fun platformTransferMode(
        transferMode: UsbExclusiveBackgroundAudioAnchorTransferMode
    ): Int {
        return when (transferMode) {
            UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop -> AudioTrack.MODE_STATIC
            UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming -> AudioTrack.MODE_STREAM
        }
    }

    private fun initializeTrack(
        track: AudioTrack,
        spec: UsbExclusiveBackgroundAudioAnchorSpec,
        silence: ByteArray
    ): Boolean {
        return runCatching {
            when (spec.transferMode) {
                UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop -> {
                    val written = track.write(silence, 0, silence.size)
                    if (written != silence.size) return@runCatching false
                    val loopFrames = track.bufferSizeInFrames.coerceAtLeast(0)
                    loopFrames > 0 && track.setLoopPoints(0, loopFrames, -1) == AudioTrack.SUCCESS
                }

                UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming -> true
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "initialize failed spec=${spec.name}", error)
        }.getOrDefault(false)
    }

    private fun resume(anchor: ActiveAnchor, reason: String): Boolean {
        val track = anchor.track
        val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        val playing = runCatching {
            if (!wasPlaying) {
                track.play()
            }
            track.playState == AudioTrack.PLAYSTATE_PLAYING
        }.onFailure { error ->
            NPLogger.w(TAG, "play failed reason=$reason", error)
        }.getOrDefault(false)
        if (playing && !wasPlaying) {
            UsbExclusiveBackgroundAudioAnchorVolumeGuard
                .beginRouteObservation(anchor.volumeGuardToken)
        }
        if (playing) {
            ensureStreamingWriter(anchor)
        }
        if (playing && !wasPlaying) {
            NPLogger.i(TAG, "started silent media anchor reason=$reason spec=${anchor.spec.name}")
        }
        if (!playing) {
            releaseLocked("play_failed:$reason")
        }
        return playing
    }

    private fun preferBuiltInOutput(context: Context, track: AudioTrack) {
        runCatching {
            val audioManager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val output = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return
            if (!track.setPreferredDevice(output)) {
                NPLogger.d(TAG, "built-in output preference rejected")
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "built-in output preference failed", error)
        }
    }

    private fun ensureStreamingWriter(anchor: ActiveAnchor) {
        if (anchor.spec.transferMode != UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming) {
            return
        }
        if (anchor.streamWriter?.isAlive == true) return
        val running = AtomicBoolean(true)
        val track = anchor.track
        val silence = anchor.silence
        val specName = anchor.spec.name
        anchor.streamWriterRunning = running
        anchor.streamWriter = Thread(
            {
                while (running.get()) {
                    val written = try {
                        track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    } catch (error: Exception) {
                        if (running.get()) {
                            NPLogger.w(TAG, "stream write failed spec=$specName", error)
                        }
                        break
                    }
                    if (written < 0) {
                        if (running.get()) {
                            NPLogger.w(TAG, "stream write rejected spec=$specName result=$written")
                        }
                        break
                    }
                    if (written == 0) {
                        Thread.yield()
                    }
                }
                running.set(false)
            },
            "NeriUsbAudioAnchor"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseLocked(reason: String) {
        val anchor = activeAnchor ?: return
        activeAnchor = null
        anchor.streamWriterRunning?.set(false)
        anchor.streamWriter?.interrupt()
        anchor.streamWriter = null
        anchor.streamWriterRunning = null
        releaseTrack(anchor.track)
        UsbExclusiveBackgroundAudioAnchorVolumeGuard.release(anchor.volumeGuardToken)
        NPLogger.i(TAG, "released silent media anchor reason=$reason spec=${anchor.spec.name}")
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            track.release()
        }.onFailure { error ->
            NPLogger.w(TAG, "release failed", error)
        }
    }
}
