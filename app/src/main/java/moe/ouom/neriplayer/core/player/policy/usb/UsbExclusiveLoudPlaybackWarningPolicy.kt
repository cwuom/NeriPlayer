package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.usb.system.usbExclusiveSystemVolumeGain
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveVolumeRiskThresholdDbfs
import kotlin.math.log10

internal enum class UsbExclusiveOutputDeviceClass {
    Uac1,
    Uac2,
    Unknown
}

internal enum class UsbExclusiveLoudPlaybackRisk {
    None,
    Elevated,
    High,
    Critical
}

internal enum class UsbExclusiveLoudnessPeakSource {
    RecentSample,
    VolumeCeiling
}

internal data class UsbExclusiveLoudnessEstimate(
    val systemVolumePercent: Int,
    val playerVolume: Float,
    val deviceClass: UsbExclusiveOutputDeviceClass,
    val estimatedPeakDbfs: Double,
    val observedPeakDbfs: Double?,
    val peakSource: UsbExclusiveLoudnessPeakSource,
    val riskThresholdDbfs: Int,
    val risk: UsbExclusiveLoudPlaybackRisk
) {
    val requiresConfirmation: Boolean
        get() = risk == UsbExclusiveLoudPlaybackRisk.High ||
            risk == UsbExclusiveLoudPlaybackRisk.Critical
}

internal fun predictedUsbExclusivePlaybackGain(
    currentPlayerVolume: Float,
    playbackAlreadyAudible: Boolean
): Float {
    return if (playbackAlreadyAudible) {
        currentPlayerVolume.coerceIn(0f, 1f)
    } else {
        1f
    }
}

internal fun estimateUsbExclusiveLoudness(
    systemVolumePercent: Int,
    playerVolume: Float,
    uacVersion: String?,
    outputSampleRate: Int,
    outputBitDepth: Int?,
    observedOutputPeak: Float?,
    riskThresholdDbfs: Int = DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS
): UsbExclusiveLoudnessEstimate {
    val normalizedPercent = systemVolumePercent.coerceIn(0, 100)
    val normalizedPlayerVolume = playerVolume.coerceIn(0f, 1f)
    val deviceClass = usbExclusiveOutputDeviceClass(uacVersion)
    val systemGain = usbExclusiveSystemVolumeGain(normalizedPercent / 100f)
    val digitalGain = systemGain.toDouble() * normalizedPlayerVolume.toDouble()
    val ceilingPeakDbfs = amplitudeToDbfs(digitalGain)
    val observedPeakDbfs = observedOutputPeak
        ?.takeIf { it.isFinite() && it > 0f }
        ?.coerceAtMost(1f)
        ?.let { amplitudeToDbfs(it.toDouble()) }
    // A native peak can belong to the previous or paused stream and is already
    // post-gain, so it may refine the estimate upward but must never lower the
    // ceiling implied by the current system and player volume
    val observedPeakDominates = observedPeakDbfs != null &&
        observedPeakDbfs > ceilingPeakDbfs
    val peakSource = if (observedPeakDominates) {
        UsbExclusiveLoudnessPeakSource.RecentSample
    } else {
        UsbExclusiveLoudnessPeakSource.VolumeCeiling
    }
    val estimatedPeakDbfs = if (observedPeakDominates) {
        observedPeakDbfs ?: ceilingPeakDbfs
    } else {
        ceilingPeakDbfs
    }
    val normalizedRiskThresholdDbfs = normalizeUsbExclusiveVolumeRiskThresholdDbfs(
        riskThresholdDbfs
    )
    val deviceCautionThresholdDbfs = deviceCautionThresholdDbfs(
        deviceClass = deviceClass,
        outputSampleRate = outputSampleRate,
        outputBitDepth = outputBitDepth
    )
    val risk = when {
        estimatedPeakDbfs >= -1.5 -> UsbExclusiveLoudPlaybackRisk.Critical
        estimatedPeakDbfs >= normalizedRiskThresholdDbfs -> UsbExclusiveLoudPlaybackRisk.High
        estimatedPeakDbfs >= deviceCautionThresholdDbfs ->
            UsbExclusiveLoudPlaybackRisk.Elevated
        else -> UsbExclusiveLoudPlaybackRisk.None
    }
    return UsbExclusiveLoudnessEstimate(
        systemVolumePercent = normalizedPercent,
        playerVolume = normalizedPlayerVolume,
        deviceClass = deviceClass,
        estimatedPeakDbfs = estimatedPeakDbfs,
        observedPeakDbfs = observedPeakDbfs,
        peakSource = peakSource,
        riskThresholdDbfs = normalizedRiskThresholdDbfs,
        risk = risk
    )
}

internal fun shouldRequestUsbExclusiveLoudPlaybackWarning(
    usbExclusiveEnabled: Boolean,
    appInForeground: Boolean,
    commandSource: PlaybackCommandSource,
    playbackAlreadyAudible: Boolean,
    loudnessEstimate: UsbExclusiveLoudnessEstimate
): Boolean {
    if (!usbExclusiveEnabled || !appInForeground) return false
    if (commandSource != PlaybackCommandSource.LOCAL || playbackAlreadyAudible) return false
    return loudnessEstimate.requiresConfirmation
}

private fun usbExclusiveOutputDeviceClass(uacVersion: String?): UsbExclusiveOutputDeviceClass {
    return when {
        uacVersion?.trim()?.startsWith("2") == true -> UsbExclusiveOutputDeviceClass.Uac2
        uacVersion?.trim()?.startsWith("1") == true -> UsbExclusiveOutputDeviceClass.Uac1
        else -> UsbExclusiveOutputDeviceClass.Unknown
    }
}

private fun deviceCautionThresholdDbfs(
    deviceClass: UsbExclusiveOutputDeviceClass,
    outputSampleRate: Int,
    outputBitDepth: Int?
): Double {
    return when (deviceClass) {
        UsbExclusiveOutputDeviceClass.Uac2 -> {
            if (outputSampleRate >= 96_000 || (outputBitDepth ?: 0) >= 24) -10.0 else -8.0
        }
        UsbExclusiveOutputDeviceClass.Uac1 -> -7.0
        UsbExclusiveOutputDeviceClass.Unknown -> -8.0
    }
}

private fun amplitudeToDbfs(amplitude: Double): Double {
    return if (!amplitude.isFinite() || amplitude <= 0.0) {
        MINIMUM_DBFS
    } else {
        (20.0 * log10(amplitude)).coerceIn(MINIMUM_DBFS, 0.0)
    }
}

private const val MINIMUM_DBFS = -80.0
