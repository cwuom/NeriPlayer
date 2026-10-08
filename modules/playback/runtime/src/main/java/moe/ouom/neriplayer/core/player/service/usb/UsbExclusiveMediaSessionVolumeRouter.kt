package moe.ouom.neriplayer.core.player.service.usb

import android.media.VolumeProvider
import moe.ouom.neriplayer.common.logging.NPLogger

internal interface UsbExclusiveVolumeRoutingPort {
    fun createProvider(): VolumeProvider
    fun setRemote(provider: VolumeProvider)
    fun setLocal()
    fun hasSession(): Boolean
    fun updateSessionFraction(fraction: Float)
    fun clearSessionFraction()
}

internal class UsbExclusiveMediaSessionVolumeRouter(
    private val port: UsbExclusiveVolumeRoutingPort,
) {
    private var provider: VolumeProvider? = null

    fun update(effectivePath: String, bitPerfect: Boolean, hardwareVolume: Boolean = false) {
        if (shouldUseUsbExclusiveRemoteVolumeRouting(effectivePath, bitPerfect, hardwareVolume)) {
            if (provider == null) enable()
        } else {
            disable("path=$effectivePath")
        }
    }

    fun disable(reason: String) {
        val wasRemote = provider != null
        provider = null
        if (wasRemote && port.hasSession()) restoreLocalRouting(reason)
        port.clearSessionFraction()
        if (wasRemote) NPLogger.i("NERI-APS", "USB exclusive MediaSession volume routing disabled: reason=$reason")
    }

    internal fun isRemote(): Boolean = provider != null

    private fun enable() {
        val newProvider = port.createProvider()
        runCatching { configureRemoteRouting(newProvider) }
            .onFailure(::rollbackRemoteRouting)
    }

    private fun configureRemoteRouting(newProvider: VolumeProvider) {
        port.setRemote(newProvider)
        provider = newProvider
        port.updateSessionFraction(usbExclusiveVolumeFractionFromProviderIndex(
            providerIndex = newProvider.currentVolume,
            providerMaxIndex = newProvider.maxVolume,
        ))
        NPLogger.i("NERI-APS", "USB exclusive MediaSession volume routing enabled")
    }

    private fun rollbackRemoteRouting(error: Throwable) {
        provider = null
        port.clearSessionFraction()
        runCatching { port.setLocal() }
        NPLogger.w("NERI-APS", "USB exclusive MediaSession volume routing failed", error)
    }

    private fun restoreLocalRouting(reason: String) {
        runCatching { port.setLocal() }
            .onFailure { error ->
                NPLogger.w("NERI-APS", "USB exclusive MediaSession volume routing reset failed: reason=$reason", error)
            }
    }
}
