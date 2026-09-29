package moe.ouom.neriplayer.core.player.usb.route

import android.app.Application
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState

internal interface UsbPlaybackNativeRoutePort {
    fun path(): UsbExclusiveAudioPathState
    fun native(): UsbExclusiveNativeState
    fun forcedFallbackReason(): String?
    fun updateRequested(enabled: Boolean)
    fun clearForcedFallback()
    fun clearRecoverableOpenBlock(reason: String)
    fun requireFreshOpen(reason: String)
    fun openGateReason(): String?
    fun stopPlayerPcm(reason: String)
    fun activateSoundGuard(application: Application, reason: String)
}

internal object AndroidUsbPlaybackNativeRoutePort : UsbPlaybackNativeRoutePort {
    override fun path(): UsbExclusiveAudioPathState = UsbExclusiveAudioPathTracker.state.value
    override fun native(): UsbExclusiveNativeState = UsbExclusiveSessionController.state.value
    override fun forcedFallbackReason(): String? = UsbExclusiveAudioPathTracker.forcedSystemFallbackReason()
    override fun updateRequested(enabled: Boolean) = UsbExclusiveAudioPathTracker.updateRequested(enabled)
    override fun clearForcedFallback() = UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    override fun clearRecoverableOpenBlock(reason: String) =
        UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock(reason)
    override fun requireFreshOpen(reason: String) = UsbExclusiveSessionController.requireFreshPlayerPcmOpen(reason)
    override fun openGateReason(): String? = UsbExclusiveSessionController.playerPcmOpenGateReason()
    override fun stopPlayerPcm(reason: String) = UsbExclusiveSessionController.stopPlayerPcmSession(reason)
    override fun activateSoundGuard(application: Application, reason: String) =
        UsbExclusiveSystemSoundGuard.activate(application, reason)
}
