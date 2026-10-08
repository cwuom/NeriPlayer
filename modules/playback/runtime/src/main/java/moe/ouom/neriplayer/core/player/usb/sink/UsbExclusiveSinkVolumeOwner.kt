package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.abs
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveBackgroundAudioAnchorVolumeGuard
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridgeSubscription
import moe.ouom.neriplayer.core.player.usb.system.usbExclusiveEffectiveNativeVolume

internal interface UsbExclusiveSinkVolumePort {
    fun bitPerfect(): Boolean
    fun setNativeVolume(handle: Long, volume: Float)
    fun setFallbackVolume(volume: Float)
    fun publishVolume(volume: Float)
    fun hasHardwareVolume(handle: Long): Boolean
    fun setHardwareVolume(handle: Long, fraction: Float)
    fun publishHardwareVolume(available: Boolean)
}

internal class UsbExclusiveSinkVolumeOwner(
    context: Context,
    observeSystemVolume: Boolean,
    private val port: UsbExclusiveSinkVolumePort,
) {
    private companion object {
        const val VOLUME_EPSILON = 0.0001f
        const val POLL_INTERVAL_ACTIVE_MS = 100L
        const val READ_FAILURE_LOG_INTERVAL_MS = 5_000L

        fun audioManager(context: Context): AudioManager? =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        fun volumeThread(observeSystemVolume: Boolean): HandlerThread? =
            if (observeSystemVolume) HandlerThread("NeriUsbVolume").apply { start() } else null

        fun volumeHandler(thread: HandlerThread?): Handler =
            Handler(thread?.looper ?: Looper.getMainLooper())
    }

    private val appContext = context.applicationContext
    private val observesSystemVolume = observeSystemVolume
    private val audioManager = audioManager(appContext)
    private val systemVolumeThread = volumeThread(observeSystemVolume)
    private val systemVolumeHandler = volumeHandler(systemVolumeThread)
    @Volatile private var cachedMusicVolumeFraction = 1f
    @Volatile private var nativeHandle = 0L
    @Volatile private var hardwareVolumeAvailable = false
    @Volatile var playerVolume = 1f
        private set
    private var lastReportedNativeVolume = Float.NaN
    private var lastSystemVolumeReadFailureLogAtMs = 0L
    @Volatile private var systemVolumeObserverRegistered = false
    private var systemVolumeBridgeSubscription: UsbExclusiveSystemVolumeBridgeSubscription? = null
    private val systemVolumeObserver = object : ContentObserver(systemVolumeHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            applySystemVolumeChange(acceptUserVolumeChange = true)
        }
    }
    private val systemVolumePoll = object : Runnable {
        override fun run() {
            if (!shouldPollUsbExclusiveSystemVolume(
                    observing = systemVolumeObserverRegistered,
                    sessionVolumePushed = UsbExclusiveSystemVolumeBridge.currentSessionVolumeFractionOrNull() != null,
                )) return
            applySystemVolumeChange()
            systemVolumeHandler.postDelayed(this, POLL_INTERVAL_ACTIVE_MS)
        }
    }

    init {
        cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
        systemVolumeBridgeSubscription = UsbExclusiveSystemVolumeBridge.subscribe { volumeFraction ->
            systemVolumeHandler.post {
                if (volumeFraction == null) {
                    applySystemVolumeChange()
                    resumeSystemVolumePoll()
                } else {
                    applySessionVolumeChange(volumeFraction)
                }
            }
        }
    }

    fun setNativeHandle(handle: Long) {
        if (handle == nativeHandle) return
        // 空闲时不监听系统音量，打开原生输出前同步读取一次，首包就用当前音量
        if (handle != 0L) cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
        nativeHandle = handle
        if (observesSystemVolume) {
            if (handle != 0L) registerSystemVolumeObserver() else unregisterSystemVolumeObserver()
        }
        hardwareVolumeAvailable = handle != 0L && port.hasHardwareVolume(handle)
        // 打开后先同步写入硬件音量再出声，避免比特完美首包按 0 dB 播放
        applyHardwareVolume()
        port.publishHardwareVolume(hardwareVolumeAvailable)
    }

    fun setPlayerVolume(volume: Float) {
        playerVolume = volume.coerceIn(0f, 1f)
        if (nativeHandle == 0L) {
            lastReportedNativeVolume = Float.NaN
            port.publishVolume(playerVolume)
            port.setFallbackVolume(playerVolume)
        } else {
            applyEffectiveNativeVolume()
        }
    }

    fun applyFallbackVolume() {
        port.setFallbackVolume(playerVolume)
    }

    fun effectiveNativeVolume(): Float = usbExclusiveEffectiveNativeVolume(
        playerVolume = playerVolume,
        systemVolumeFraction = cachedMusicVolumeFraction,
        bitPerfect = port.bitPerfect(),
    )

    fun applyEffectiveNativeVolume(): Float {
        val effectiveVolume = effectiveNativeVolume()
        publishNativeVolume(effectiveVolume)
        val handle = nativeHandle
        if (handle != 0L) port.setNativeVolume(handle, effectiveVolume)
        return effectiveVolume
    }

    fun publishNativeVolume(effectiveVolume: Float) {
        if (lastReportedNativeVolume.isNaN() || abs(lastReportedNativeVolume - effectiveVolume) > VOLUME_EPSILON) {
            lastReportedNativeVolume = effectiveVolume
            port.publishVolume(effectiveVolume)
        }
    }

    fun release() {
        unregisterSystemVolumeObserver()
        UsbExclusiveSystemVolumeBridge.unsubscribe(systemVolumeBridgeSubscription)
        systemVolumeBridgeSubscription = null
        systemVolumeThread?.quitSafely()
    }

    private fun readMusicVolumeFractionFromSystem(acceptUserVolumeChange: Boolean = false): Float {
        UsbExclusiveSystemVolumeBridge.currentSessionVolumeFractionOrNull()?.let { return it }
        val manager = audioManager ?: return UsbExclusiveBackgroundAudioAnchorVolumeGuard
            .currentVolumeFractionOrNull() ?: 1f
        val observedVolumeFraction = try {
            readMusicVolumeFraction(manager)
        } catch (error: Throwable) {
            return volumeReadFallback(error)
        }
        return anchoredVolume(observedVolumeFraction, acceptUserVolumeChange)
    }

    private fun anchoredVolume(observedVolumeFraction: Float, acceptUserVolumeChange: Boolean): Float =
        if (acceptUserVolumeChange) anchoredUserVolume(observedVolumeFraction)
        else anchoredRouteVolume(observedVolumeFraction)

    private fun anchoredUserVolume(observedVolumeFraction: Float): Float =
        UsbExclusiveBackgroundAudioAnchorVolumeGuard.applyUserVolumeChange(observedVolumeFraction)
            ?: observedVolumeFraction

    private fun anchoredRouteVolume(observedVolumeFraction: Float): Float =
        UsbExclusiveBackgroundAudioAnchorVolumeGuard.observeRouteVolume(observedVolumeFraction)
            ?: observedVolumeFraction

    private fun readMusicVolumeFraction(manager: AudioManager): Float {
        val minVolume = manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val range = maxVolume - minVolume
        return if (range <= 0) 1f else ((currentVolume - minVolume).toFloat() / range).coerceIn(0f, 1f)
    }

    private fun volumeReadFallback(error: Throwable): Float {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastSystemVolumeReadFailureLogAtMs >= READ_FAILURE_LOG_INTERVAL_MS) {
            lastSystemVolumeReadFailureLogAtMs = nowMs
            NPLogger.w("NERI-UsbExclusive", "failed to read system media volume", error)
        }
        return UsbExclusiveBackgroundAudioAnchorVolumeGuard.currentVolumeFractionOrNull()
            ?: cachedMusicVolumeFraction
    }

    private fun registerSystemVolumeObserver() {
        if (systemVolumeObserverRegistered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, systemVolumeObserver)
            systemVolumeObserverRegistered = true
            systemVolumeHandler.removeCallbacks(systemVolumePoll)
            systemVolumeHandler.post(systemVolumePoll)
        }.onFailure { NPLogger.w("NERI-UsbExclusive", "system volume observer registration failed", it) }
    }

    private fun unregisterSystemVolumeObserver() {
        systemVolumeHandler.removeCallbacks(systemVolumePoll)
        if (!systemVolumeObserverRegistered) return
        runCatching { appContext.contentResolver.unregisterContentObserver(systemVolumeObserver) }
            .onFailure { NPLogger.w("NERI-UsbExclusive", "system volume observer unregistration failed", it) }
        systemVolumeObserverRegistered = false
    }

    private fun applySystemVolumeChange(acceptUserVolumeChange: Boolean = false) {
        updateSystemVolumeFraction(readMusicVolumeFractionFromSystem(acceptUserVolumeChange))
    }

    private fun applySessionVolumeChange(volumeFraction: Float) {
        updateSystemVolumeFraction(volumeFraction.coerceIn(0f, 1f))
    }

    internal fun updateSystemVolumeFraction(nextVolumeFraction: Float) {
        if (abs(nextVolumeFraction - cachedMusicVolumeFraction) <= VOLUME_EPSILON) return
        cachedMusicVolumeFraction = nextVolumeFraction
        if (nativeHandle != 0L) applyEffectiveNativeVolume()
        applyHardwareVolume()
    }

    /** 比特完美时音量键改 DAC 硬件音量；非比特完美保持 0 dB，由数字音量负责 */
    private fun applyHardwareVolume() {
        val handle = nativeHandle
        if (handle == 0L || !hardwareVolumeAvailable) return
        port.setHardwareVolume(handle, if (port.bitPerfect()) cachedMusicVolumeFraction else 1f)
    }

    private fun resumeSystemVolumePoll() {
        if (!systemVolumeObserverRegistered) return
        systemVolumeHandler.removeCallbacks(systemVolumePoll)
        systemVolumeHandler.post(systemVolumePoll)
    }
}

/**
 * 系统音量只在原生 USB 输出时才映射到原生增益；MediaSession 远程音量生效时音量变化会主动推送，
 * 两种情况都不需要每 100 ms 唤醒读一次 AudioManager
 */
internal fun shouldPollUsbExclusiveSystemVolume(observing: Boolean, sessionVolumePushed: Boolean): Boolean =
    observing && !sessionVolumePushed
