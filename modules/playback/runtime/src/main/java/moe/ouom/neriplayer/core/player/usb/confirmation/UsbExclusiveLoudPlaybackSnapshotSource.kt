package moe.ouom.neriplayer.core.player.usb.confirmation

import android.content.Context
import android.media.AudioManager
import moe.ouom.neriplayer.data.model.playback.AudioDevice
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveLoudnessEstimate
import moe.ouom.neriplayer.core.player.policy.usb.estimateUsbExclusiveLoudness
import moe.ouom.neriplayer.core.player.policy.usb.predictedUsbExclusivePlaybackGain
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics

internal data class UsbExclusiveLoudPlaybackSignals(
    val context: Context,
    val usbExclusiveEnabled: Boolean,
    val appInForeground: Boolean,
    val currentDevice: AudioDevice?,
    val reportedPlaying: Boolean,
    val playerInitialized: () -> Boolean,
    val playerIsPlaying: () -> Boolean,
    val playerVolume: () -> Float,
    val requestedVolume: () -> Float,
    val nativeState: () -> UsbExclusiveNativeState,
    val bitPerfect: Boolean,
    val riskThresholdDbfs: Int
)

internal data class UsbExclusiveLoudPlaybackSnapshot(
    val systemVolumePercent: Int?,
    val usbExclusiveEnabled: Boolean,
    val appInForeground: Boolean,
    val playbackAlreadyAudible: Boolean,
    val currentPlayerVolume: Float,
    val bitPerfect: Boolean,
    val riskThresholdDbfs: Int,
    val deviceName: String,
    val outputRouteKey: String,
    val outputSampleRate: Int,
    val metrics: UsbExclusiveRuntimeMetrics
) {
    fun estimate(systemVolumePercent: Int): UsbExclusiveLoudnessEstimate = estimateUsbExclusiveLoudness(
        systemVolumePercent = systemVolumePercent,
        playerVolume = predictedUsbExclusivePlaybackGain(
            currentPlayerVolume = currentPlayerVolume,
            playbackAlreadyAudible = playbackAlreadyAudible
        ),
        bitPerfect = bitPerfect,
        uacVersion = metrics.uacVersion,
        outputSampleRate = outputSampleRate,
        outputBitDepth = metrics.subslotBytes?.times(8),
        observedOutputPeak = observedOutputPeak(),
        riskThresholdDbfs = riskThresholdDbfs
    )

    private fun observedOutputPeak(): Float? = if (playbackAlreadyAudible) {
        metrics.lastOutputPeak?.takeIf { it.isFinite() && it > 0f }
    } else {
        null
    }
}

internal object UsbExclusiveLoudPlaybackSnapshotSource {
    fun capture(signals: UsbExclusiveLoudPlaybackSignals): UsbExclusiveLoudPlaybackSnapshot {
        val systemVolumePercent = systemMediaVolumePercent(signals.context)
        val audible = isPlaybackAudible(signals)
        val nativeState = signals.nativeState()
        val metrics = nativeState.runtimeReport.usbRuntimeMetrics()
        val playerVolume = currentPlayerVolume(signals)
        return UsbExclusiveLoudPlaybackSnapshot(
            systemVolumePercent = systemVolumePercent,
            usbExclusiveEnabled = signals.usbExclusiveEnabled,
            appInForeground = signals.appInForeground,
            playbackAlreadyAudible = audible,
            currentPlayerVolume = playerVolume,
            bitPerfect = signals.bitPerfect,
            riskThresholdDbfs = signals.riskThresholdDbfs,
            deviceName = signals.currentDevice?.name.orEmpty(),
            outputRouteKey = outputRouteKey(signals.currentDevice, metrics),
            outputSampleRate = metrics.sampleRate ?: nativeState.outputSampleRate,
            metrics = metrics
        )
    }

    fun systemMediaVolumePercent(context: Context): Int? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        return runCatching {
            val minVolume = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val range = maxVolume - minVolume
            if (range <= 0) 100 else ((currentVolume - minVolume) * 100 / range).coerceIn(0, 100)
        }.getOrNull()
    }

    private fun isPlaybackAudible(signals: UsbExclusiveLoudPlaybackSignals): Boolean =
        signals.reportedPlaying ||
            (signals.playerInitialized() && runCatching { signals.playerIsPlaying() }.getOrDefault(false))

    private fun currentPlayerVolume(signals: UsbExclusiveLoudPlaybackSignals): Float {
        if (!signals.playerInitialized()) return signals.requestedVolume()
        return runCatching { signals.playerVolume().coerceIn(0f, 1f) }
            .getOrElse { signals.requestedVolume() }
    }

    private fun outputRouteKey(
        device: AudioDevice?,
        metrics: UsbExclusiveRuntimeMetrics
    ): String {
        val route = if (device == null) "unknown" else "${device.type}:${device.name}"
        return "$route:${metrics.uacVersion ?: "uac_unknown"}:${metrics.candidateId ?: "candidate_unknown"}"
    }
}
