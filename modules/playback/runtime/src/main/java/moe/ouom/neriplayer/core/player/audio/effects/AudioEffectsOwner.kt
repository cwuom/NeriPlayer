package moe.ouom.neriplayer.core.player.audio.effects

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsRuntimeContext
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.normalized
import moe.ouom.neriplayer.data.model.playback.effects.resolveDsp
import kotlin.time.Duration.Companion.milliseconds

private const val PERSIST_DEBOUNCE_MS = 250L

internal interface AudioEffectsPort {
    fun usbBitPerfect(): Boolean
    fun onDspActiveChanged(active: Boolean, reason: String)
    suspend fun persist(settings: AudioEffectsSettings)
}

/** 音效设置的唯一持有者：合并界面修改、外部导入与输出设备变化，并发布给播放线程 */
internal class AudioEffectsOwner(
    private var ioScope: CoroutineScope,
    private val port: AudioEffectsPort,
    private val publisher: AudioEffectsRuntimePublisher = AudioEffectsRuntimeState
) {
    private val mutableSettings = MutableStateFlow(AudioEffectsSettings())
    val settings: StateFlow<AudioEffectsSettings> = mutableSettings.asStateFlow()
    private val mutableRoute = MutableStateFlow(AudioOutputRoute.SPEAKER)
    val route: StateFlow<AudioOutputRoute> = mutableRoute.asStateFlow()
    private var persistJob: Job? = null
    private var dspActive: Boolean? = null

    val isDspActive: Boolean
        get() = dspActive == true

    fun rebindScope(ioScope: CoroutineScope) {
        persistJob?.cancel()
        persistJob = null
        this.ioScope = ioScope
    }

    fun restore(settings: AudioEffectsSettings) {
        mutableSettings.value = settings.normalized()
        publish("restore")
    }

    fun update(persist: Boolean, transform: (AudioEffectsSettings) -> AudioEffectsSettings) {
        val next = transform(mutableSettings.value).normalized()
        if (next == mutableSettings.value) return
        mutableSettings.value = next
        publish("settings")
        if (persist) schedulePersist(next)
    }

    /** DataStore 回流或配置导入；本地写入尚未落盘时忽略旧值，避免拖动中被回滚 */
    fun applyStored(settings: AudioEffectsSettings) {
        if (persistJob?.isActive == true) return
        val normalized = settings.normalized()
        if (normalized == mutableSettings.value) return
        mutableSettings.value = normalized
        publish("stored")
    }

    fun onRouteChanged(route: AudioOutputRoute) {
        if (route == mutableRoute.value) return
        mutableRoute.value = route
        publish("route")
    }

    fun onUsbPreferencesChanged() = publish("usb")

    private fun publish(reason: String) {
        val current = mutableSettings.value
        val route = mutableRoute.value
        val resolution = current.resolveDsp(AudioEffectsRuntimeContext(route = route))
        publisher.publish(
            resolution = resolution,
            usbNativeAllowed = current.applyInUsbExclusive && !port.usbBitPerfect(),
            route = route
        )
        if (dspActive != resolution.active) {
            dspActive = resolution.active
            port.onDspActiveChanged(resolution.active, reason)
        }
    }

    private fun schedulePersist(settings: AudioEffectsSettings) {
        persistJob?.cancel()
        persistJob = ioScope.launch {
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
            port.persist(settings)
        }
    }
}
