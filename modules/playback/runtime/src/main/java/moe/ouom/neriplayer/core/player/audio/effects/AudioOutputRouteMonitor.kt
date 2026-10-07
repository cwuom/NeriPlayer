package moe.ouom.neriplayer.core.player.audio.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute

private val UsbOutputTypes = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY
)
private val WiredOutputTypes = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE
)
private val BluetoothOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AUDIO_DEVICE_TYPE_BLE_HEADSET,
    AUDIO_DEVICE_TYPE_BLE_SPEAKER,
    AUDIO_DEVICE_TYPE_BLE_BROADCAST
)
private val SpeakerOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AUDIO_DEVICE_TYPE_BUILTIN_SPEAKER_SAFE
)
private val OtherOutputTypes = setOf(
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_FM
)

private const val AUDIO_DEVICE_TYPE_BLE_HEADSET = 26
private const val AUDIO_DEVICE_TYPE_BLE_SPEAKER = 27
private const val AUDIO_DEVICE_TYPE_BLE_BROADCAST = 30
private const val AUDIO_DEVICE_TYPE_BUILTIN_SPEAKER_SAFE = 24

internal fun AudioOutputRoute.Companion.fromDeviceType(type: Int): AudioOutputRoute? = when (type) {
    in UsbOutputTypes -> AudioOutputRoute.USB
    in WiredOutputTypes -> AudioOutputRoute.WIRED
    in BluetoothOutputTypes -> AudioOutputRoute.BLUETOOTH
    in SpeakerOutputTypes -> AudioOutputRoute.SPEAKER
    in OtherOutputTypes -> AudioOutputRoute.OTHER
    else -> null
}

/**
 * 没有系统路由结果时按媒体策略的常见优先级推断：
 * 蓝牙 > 有线 > USB > 其他外接 > 内置扬声器
 */
internal fun resolveAudioOutputRoute(routedTypes: List<Int>, connectedTypes: Collection<Int>): AudioOutputRoute {
    routedTypes.firstNotNullOfOrNull { AudioOutputRoute.fromDeviceType(it) }?.let { return it }
    val connected = connectedTypes.mapNotNull { AudioOutputRoute.fromDeviceType(it) }.toSet()
    return listOf(
        AudioOutputRoute.BLUETOOTH,
        AudioOutputRoute.WIRED,
        AudioOutputRoute.USB,
        AudioOutputRoute.OTHER
    ).firstOrNull { it in connected } ?: AudioOutputRoute.SPEAKER
}

internal class AudioOutputRouteMonitor(
    context: Context,
    private val onRouteChanged: (AudioOutputRoute) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        val manager = audioManager ?: return
        if (!registered) {
            runCatching { manager.registerAudioDeviceCallback(callback, mainHandler) }
                .onSuccess { registered = true }
                .onFailure { NPLogger.w("NERI-AudioEffects", "register route callback failed: ${it.message}") }
        }
        refresh()
    }

    fun stop() {
        val manager = audioManager ?: return
        if (registered) runCatching { manager.unregisterAudioDeviceCallback(callback) }
        registered = false
    }

    fun refresh() {
        val manager = audioManager ?: return
        val route = runCatching {
            resolveAudioOutputRoute(routedMediaDeviceTypes(manager), connectedOutputTypes(manager))
        }.getOrDefault(AudioOutputRoute.SPEAKER)
        onRouteChanged(route)
    }

    private fun connectedOutputTypes(manager: AudioManager): List<Int> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

    private fun routedMediaDeviceTypes(manager: AudioManager): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return manager.getAudioDevicesForAttributes(attributes).map { it.type }
    }
}
