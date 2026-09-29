package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.core.player.usb.transport.canReuseNativePlayerSession
import moe.ouom.neriplayer.core.player.usb.transport.hasHealthyTransport

import moe.ouom.neriplayer.core.player.usb.sink.UsbExclusiveOutputFormatResolver
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.booleanField
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics

internal object UsbExclusiveSessionReusePolicy {
    fun canReuseOutput(currentOutputFormat: String, preferredOutputFormat: String): Boolean {
        if (currentOutputFormat.isBlank() || currentOutputFormat == "none") return false
        if (preferredOutputFormat.isBlank() || preferredOutputFormat == "none") return false
        return UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(
            currentDescription = currentOutputFormat,
            preferredDescription = preferredOutputFormat
        )
    }

    fun canReuseResolvedOutput(
        currentOutputFormat: String,
        currentRequestedOutputFormat: String,
        preferredOutputFormat: String,
        candidateDescriptions: Set<String>
    ): Boolean {
        if (currentOutputFormat !in candidateDescriptions) return false
        if (currentRequestedOutputFormat == preferredOutputFormat) return true
        return canReuseOutput(currentOutputFormat, preferredOutputFormat)
    }

    fun canReconfigureInPlace(state: UsbExclusiveNativeState): Boolean {
        if (!canReuseSession(state)) return false
        return state.runtimeReport.usbRuntimeMetrics().running != true
    }

    fun canReuseSession(state: UsbExclusiveNativeState): Boolean {
        if (!hasOpenedPlayerSession(state)) return false
        if (state.outputFormat.isBlank() || state.outputFormat == "none") return false
        if (!state.runtimeReport.usbRuntimeMetrics().canReuseNativePlayerSession) return false
        val lastError = state.lastError.orEmpty()
        return lastError.isBlank() || lastError == "none"
    }

    fun hasOpenedPlayerSession(state: UsbExclusiveNativeState): Boolean {
        return state.handle != 0L && state.source == "player_pcm" && state.opened
    }

    fun hasPlayerHandle(state: UsbExclusiveNativeState): Boolean {
        return state.handle != 0L && state.source == "player_pcm"
    }

    fun canRearmPlayerSession(state: UsbExclusiveNativeState, handle: Long): Boolean {
        if (!state.matchesPlayerSession(handle)) return false
        return !state.transitioning && state.runtimeReport.booleanField("running") != true
    }

    fun shouldRetryAlternativeReconfigure(reason: String): Boolean {
        if (reason.isBlank()) return false
        return reason.contains("reconfigure_no_compatible_output", ignoreCase = true) ||
            reason.contains("reconfigure_sample_rate_failed", ignoreCase = true) ||
            reason.contains("reconfigure_requires_reopen", ignoreCase = true)
    }

    fun hasHealthySession(state: UsbExclusiveNativeState, ioGateOpen: Boolean): Boolean {
        if (!hasOpenedPlayerSession(state)) return false
        return hasHealthyActiveSession(state, ioGateOpen)
    }

    private fun hasHealthyActiveSession(state: UsbExclusiveNativeState, ioGateOpen: Boolean): Boolean {
        if (state.transitioning) return false
        return hasHealthyOpenGate(state, ioGateOpen)
    }

    private fun hasHealthyOpenGate(state: UsbExclusiveNativeState, ioGateOpen: Boolean): Boolean {
        if (!ioGateOpen) return false
        return hasHealthyReportedTransport(state)
    }

    private fun hasHealthyReportedTransport(state: UsbExclusiveNativeState): Boolean {
        if (!state.lastError.isNullOrBlank()) return false
        val metrics = state.runtimeReport.usbRuntimeMetrics()
        return metrics.deviceOnline != false && metrics.hasHealthyTransport
    }
}
