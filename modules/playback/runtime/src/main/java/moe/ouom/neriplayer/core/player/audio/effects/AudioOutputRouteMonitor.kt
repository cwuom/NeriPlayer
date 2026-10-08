package moe.ouom.neriplayer.core.player.audio.effects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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

private const val ACTION_STREAM_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
private const val EXTRA_VOLUME_STREAM_DEVICES = "android.media.EXTRA_VOLUME_STREAM_DEVICES"

/** AudioSystem.DEVICE_OUT_* 位到 AudioDeviceInfo 类型，按媒体路由优先级排列 */
private val StreamDeviceBits = listOf(
    0x80 to AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    0x100 to AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    0x200 to AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    0x8000000 to AudioDeviceInfo.TYPE_HEARING_AID,
    0x20000000 to AUDIO_DEVICE_TYPE_BLE_HEADSET,
    0x4 to AudioDeviceInfo.TYPE_WIRED_HEADSET,
    0x8 to AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    0x20000 to AudioDeviceInfo.TYPE_LINE_ANALOG,
    0x80000 to AudioDeviceInfo.TYPE_LINE_DIGITAL,
    0x200000 to AudioDeviceInfo.TYPE_AUX_LINE,
    0x4000000 to AudioDeviceInfo.TYPE_USB_HEADSET,
    0x4000 to AudioDeviceInfo.TYPE_USB_DEVICE,
    0x2000 to AudioDeviceInfo.TYPE_USB_ACCESSORY,
    0x400 to AudioDeviceInfo.TYPE_HDMI,
    0x40000 to AudioDeviceInfo.TYPE_HDMI_ARC,
    0x800 to AudioDeviceInfo.TYPE_DOCK,
    0x1000 to AudioDeviceInfo.TYPE_DOCK,
    0x100000 to AudioDeviceInfo.TYPE_FM,
    0x2 to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    0x400000 to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
)

/** 媒体流设备变化广播里的位掩码转成 AudioDeviceInfo 类型 */
internal fun audioOutputTypesForStreamDevices(devices: Int): List<Int> =
    StreamDeviceBits.filter { (bit, _) -> devices and bit != 0 }.map { it.second }.distinct()

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
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private var streamDevicesReceiverRegistered = false
    @Volatile private var musicStreamDeviceTypes: List<Int> = emptyList()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    /** 设备不增减时的路由切换（如插入有线设备后蓝牙让出媒体）只体现在媒体流设备变化广播里 */
    private val streamDevicesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            musicStreamDeviceTypes = audioOutputTypesForStreamDevices(intent.getIntExtra(EXTRA_VOLUME_STREAM_DEVICES, 0))
            refresh()
        }
    }

    fun start() {
        val manager = audioManager ?: return
        if (!registered) {
            runCatching { manager.registerAudioDeviceCallback(callback, mainHandler) }
                .onSuccess { registered = true }
                .onFailure { NPLogger.w("NERI-AudioEffects", "register route callback failed: ${it.message}") }
        }
        registerStreamDevicesReceiver()
        refresh()
    }

    fun stop() {
        val manager = audioManager ?: return
        if (registered) runCatching { manager.unregisterAudioDeviceCallback(callback) }
        registered = false
        if (streamDevicesReceiverRegistered) runCatching { appContext.unregisterReceiver(streamDevicesReceiver) }
        streamDevicesReceiverRegistered = false
    }

    fun refresh() {
        val manager = audioManager ?: return
        val route = runCatching {
            val connectedTypes = connectedOutputTypes(manager)
            resolveAudioOutputRoute(routedMediaDeviceTypes(manager, connectedTypes), connectedTypes)
        }.getOrDefault(AudioOutputRoute.SPEAKER)
        onRouteChanged(route)
    }

    private fun registerStreamDevicesReceiver() {
        if (streamDevicesReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                streamDevicesReceiver,
                IntentFilter(ACTION_STREAM_DEVICES_CHANGED),
                null,
                mainHandler,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess { streamDevicesReceiverRegistered = true }
            .onFailure { NPLogger.w("NERI-AudioEffects", "register stream devices receiver failed: ${it.message}") }
    }

    private fun connectedOutputTypes(manager: AudioManager): List<Int> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

    private fun routedMediaDeviceTypes(manager: AudioManager, connectedTypes: List<Int>): List<Int> {
        // 广播里的设备可能已经拔出，只认仍然连接的设备，否则退回按连接设备推断
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return musicStreamDeviceTypes.filter { it in connectedTypes }
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return manager.getAudioDevicesForAttributes(attributes).map { it.type }
    }
}
