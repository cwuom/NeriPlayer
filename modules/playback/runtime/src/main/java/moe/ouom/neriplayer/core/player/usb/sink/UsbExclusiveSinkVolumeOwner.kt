package moe.ouom.neriplayer.core.player.usb.sink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.abs
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.usb.system.usbExclusiveEffectiveNativeVolume

internal interface UsbExclusiveSinkVolumePort {
    fun bitPerfect(): Boolean
    fun setNativeBitPerfect(handle: Long, enabled: Boolean)
    fun setNativeVolume(handle: Long, volume: Float)
    fun setFallbackVolume(volume: Float)
    fun publishVolume(volume: Float)
    fun hasHardwareVolume(handle: Long): Boolean
    fun setHardwareVolume(handle: Long, fraction: Float)
    fun publishHardwareVolume(available: Boolean)
}

internal const val USB_EXCLUSIVE_ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
internal const val USB_EXCLUSIVE_ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
internal const val USB_EXCLUSIVE_ACTION_STREAM_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION"
internal const val USB_EXCLUSIVE_EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

/**
 * 原生 USB 输出跟随系统媒体音量：音量键、锁屏和系统面板都走 STREAM_MUSIC，
 * AudioService 在调节的同时发出广播，Settings 持久化要晚约 500 ms，只作兜底
 */
internal class UsbExclusiveSinkVolumeOwner(
    context: Context,
    observeSystemVolume: Boolean,
    private val port: UsbExclusiveSinkVolumePort,
) {
    private companion object {
        const val VOLUME_EPSILON = 0.0001f
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
    private val bitPerfectLock = Any()
    private var nativeBitPerfect: Boolean? = null
    @Volatile var playerVolume = 1f
        private set
    private var lastReportedNativeVolume = Float.NaN
    private var lastSystemVolumeReadFailureLogAtMs = 0L
    @Volatile private var systemVolumeObserverRegistered = false
    private val systemVolumeObserver = object : ContentObserver(systemVolumeHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            applySystemVolumeChange()
        }
    }
    private val systemVolumeReceiver = UsbExclusiveMusicVolumeReceiver(::applySystemVolumeChange)

    init {
        cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
    }

    fun setNativeHandle(handle: Long) {
        if (handle == nativeHandle) return
        // 空闲时不监听系统音量，打开原生输出前同步读取一次，首包就用当前音量
        if (handle != 0L) cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
        nativeHandle = handle
        synchronized(bitPerfectLock) { nativeBitPerfect = null }
        if (observesSystemVolume) {
            if (handle != 0L) registerSystemVolumeObserver() else unregisterSystemVolumeObserver()
        }
        updateNativeBitPerfect()
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

    /** 写入路径每次取音量时同步比特完美开关，设置切换后下一包 PCM 即生效 */
    fun nativeWriteVolume(): Float {
        syncNativeBitPerfect()
        return effectiveNativeVolume()
    }

    fun applyEffectiveNativeVolume(): Float {
        syncNativeBitPerfect()
        val effectiveVolume = effectiveNativeVolume()
        publishNativeVolume(effectiveVolume)
        val handle = nativeHandle
        if (handle != 0L) port.setNativeVolume(handle, effectiveVolume)
        return effectiveVolume
    }

    /** 会话中途切换比特完美时，DAC 硬件音量也要在 0 dB 与跟随音量键之间切换 */
    private fun syncNativeBitPerfect() {
        if (updateNativeBitPerfect()) applyHardwareVolume()
    }

    private fun updateNativeBitPerfect(): Boolean = synchronized(bitPerfectLock) {
        val handle = nativeHandle
        if (handle == 0L) return false
        val enabled = port.bitPerfect()
        if (nativeBitPerfect == enabled) return false
        nativeBitPerfect = enabled
        port.setNativeBitPerfect(handle, enabled)
        true
    }

    fun publishNativeVolume(effectiveVolume: Float) {
        if (lastReportedNativeVolume.isNaN() || abs(lastReportedNativeVolume - effectiveVolume) > VOLUME_EPSILON) {
            lastReportedNativeVolume = effectiveVolume
            port.publishVolume(effectiveVolume)
        }
    }

    fun release() {
        unregisterSystemVolumeObserver()
        systemVolumeThread?.quitSafely()
    }

    private fun readMusicVolumeFractionFromSystem(): Float {
        val manager = audioManager ?: return cachedMusicVolumeFraction
        return try {
            readMusicVolumeFraction(manager)
        } catch (error: Throwable) {
            volumeReadFallback(error)
        }
    }

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
        return cachedMusicVolumeFraction
    }

    private fun registerSystemVolumeObserver() {
        if (systemVolumeObserverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                systemVolumeReceiver,
                usbExclusiveMusicVolumeIntentFilter(),
                null,
                systemVolumeHandler,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, systemVolumeObserver)
            systemVolumeObserverRegistered = true
        }.onFailure { NPLogger.w("NERI-UsbExclusive", "system volume observer registration failed", it) }
        // 注册期间的音量变化不会补发广播，注册后再读一次
        systemVolumeHandler.post { applySystemVolumeChange() }
    }

    private fun unregisterSystemVolumeObserver() {
        if (!systemVolumeObserverRegistered) return
        runCatching { appContext.unregisterReceiver(systemVolumeReceiver) }
            .onFailure { NPLogger.w("NERI-UsbExclusive", "system volume receiver unregistration failed", it) }
        runCatching { appContext.contentResolver.unregisterContentObserver(systemVolumeObserver) }
            .onFailure { NPLogger.w("NERI-UsbExclusive", "system volume observer unregistration failed", it) }
        systemVolumeObserverRegistered = false
    }

    private fun applySystemVolumeChange() {
        if (nativeHandle == 0L) return
        updateSystemVolumeFraction(readMusicVolumeFractionFromSystem())
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
}

internal class UsbExclusiveMusicVolumeReceiver(
    private val onMusicVolumeChanged: () -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (isUsbExclusiveMusicVolumeBroadcast(action, intent.getIntExtra(USB_EXCLUSIVE_EXTRA_VOLUME_STREAM_TYPE, -1))) {
            onMusicVolumeChanged()
        }
    }
}

internal fun usbExclusiveMusicVolumeIntentFilter(): IntentFilter = IntentFilter().apply {
    addAction(USB_EXCLUSIVE_ACTION_VOLUME_CHANGED)
    addAction(USB_EXCLUSIVE_ACTION_STREAM_MUTE_CHANGED)
    addAction(USB_EXCLUSIVE_ACTION_STREAM_DEVICES_CHANGED)
}

/** 只响应媒体流；缺少流类型的广播按可能相关处理，读一次 AudioManager 的成本很低 */
internal fun isUsbExclusiveMusicVolumeBroadcast(action: String?, streamType: Int?): Boolean {
    if (action != USB_EXCLUSIVE_ACTION_VOLUME_CHANGED &&
        action != USB_EXCLUSIVE_ACTION_STREAM_MUTE_CHANGED &&
        action != USB_EXCLUSIVE_ACTION_STREAM_DEVICES_CHANGED
    ) return false
    return streamType == null || streamType < 0 || streamType == AudioManager.STREAM_MUSIC
}
