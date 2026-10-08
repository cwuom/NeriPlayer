package moe.ouom.neriplayer.core.player.audio.effects

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsResolution
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsRuntimeStats
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.NeriDspParams
import moe.ouom.neriplayer.data.model.playback.effects.neutralDspParams
import java.util.concurrent.atomic.AtomicReference

internal class AudioEffectsRuntimeSnapshot(
    val generation: Long,
    val active: Boolean,
    val params: FloatArray,
    val usbNativeAllowed: Boolean,
    val route: AudioOutputRoute,
    val inactiveReason: AudioEffectsInactiveReason?
) {
    val disabledParams: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        params.copyOf().also { it[NeriDspParams.MASTER_ENABLED] = 0f }
    }
}

internal data class AudioEffectsEngineStats(
    val processedFrames: Long,
    val processingNanos: Long,
    val limiterReductionDb: Float,
    val compressorReductionDb: Float,
    val sampleRate: Int
) {
    val cpuLoadPercent: Float
        get() {
            if (processedFrames <= 0L || sampleRate <= 0) return 0f
            val audioNanos = processedFrames * 1_000_000_000.0 / sampleRate
            return (processingNanos / audioNanos * 100.0).toFloat().coerceAtLeast(0f)
        }
}

internal interface AudioEffectsRuntimePublisher {
    fun publish(resolution: AudioEffectsResolution, usbNativeAllowed: Boolean, route: AudioOutputRoute)
}

/** 设置侧发布最新参数，播放线程只读快照；按代次判断是否需要重新下发到 native */
internal object AudioEffectsRuntimeState : AudioEffectsRuntimePublisher {
    private val snapshot = AtomicReference(
        AudioEffectsRuntimeSnapshot(
            generation = 0L,
            active = false,
            params = neutralDspParams(),
            usbNativeAllowed = false,
            route = AudioOutputRoute.SPEAKER,
            inactiveReason = AudioEffectsInactiveReason.DISABLED
        )
    )
    // 播放线程和设置侧同时改统计、各写各的字段，只能用 update 原子地读改写
    private val mutableStats = MutableStateFlow(AudioEffectsRuntimeStats())
    val stats: StateFlow<AudioEffectsRuntimeStats> = mutableStats.asStateFlow()

    fun current(): AudioEffectsRuntimeSnapshot = snapshot.get()

    override fun publish(resolution: AudioEffectsResolution, usbNativeAllowed: Boolean, route: AudioOutputRoute) {
        while (true) {
            val previous = snapshot.get()
            val next = AudioEffectsRuntimeSnapshot(
                generation = previous.generation + 1L,
                active = resolution.active,
                params = resolution.params,
                usbNativeAllowed = usbNativeAllowed,
                route = route,
                inactiveReason = resolution.inactiveReason
            )
            if (snapshot.compareAndSet(previous, next)) break
        }
        mutableStats.update { current ->
            current.copy(
                active = resolution.active,
                route = route,
                inactiveReason = resolution.inactiveReason,
                cpuLoadPercent = if (resolution.active) current.cpuLoadPercent else 0f
            )
        }
    }

    fun publishEngineStats(stats: AudioEffectsEngineStats) {
        mutableStats.update { current ->
            current.copy(
                cpuLoadPercent = stats.cpuLoadPercent,
                limiterReductionDb = stats.limiterReductionDb,
                compressorReductionDb = stats.compressorReductionDb,
                sampleRate = stats.sampleRate
            )
        }
    }

    /** 播放链自身导致的旁路（USB 独占、格式不支持、native 不可用）优先于设置侧原因 */
    fun publishPathState(sinkReason: AudioEffectsInactiveReason?, nativeAvailable: Boolean) {
        val resolved = snapshot.get()
        val reason = sinkReason ?: resolved.inactiveReason
        val active = resolved.active && sinkReason == null
        mutableStats.update { current ->
            if (current.inactiveReason == reason && current.active == active && current.nativeAvailable == nativeAvailable) {
                current
            } else {
                current.copy(
                    active = active,
                    inactiveReason = reason,
                    nativeAvailable = nativeAvailable,
                    cpuLoadPercent = if (active) current.cpuLoadPercent else 0f
                )
            }
        }
    }
}
