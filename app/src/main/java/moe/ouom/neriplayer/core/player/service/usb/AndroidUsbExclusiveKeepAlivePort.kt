package moe.ouom.neriplayer.core.player.service.usb

import android.content.Context
import android.os.SystemClock
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.recoverUsbExclusivePlaybackIfUnhealthy
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveWakeLock
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveBackgroundAudioAnchor
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState

internal interface UsbExclusiveKeepAliveServiceHost {
    fun foregroundStarted(): Boolean
    fun reassertForeground(reason: String): Boolean
    fun ensureForeground(): Boolean
    fun onForegroundFailure(reason: String)
    fun updatePlaybackPresentation()
}

internal class AndroidUsbExclusiveKeepAlivePort(
    private val context: Context,
    private val host: UsbExclusiveKeepAliveServiceHost,
) : UsbExclusiveKeepAlivePort {
    override fun appInForeground(): Boolean = PlayerManager.usbExclusiveAppInForeground

    override fun playbackActive(): Boolean =
        PlayerManager.isUsbExclusivePlaybackActiveForForegroundService()

    override fun foregroundStarted(): Boolean = host.foregroundStarted()

    override fun reassertForeground(reason: String): Boolean = host.reassertForeground(reason)

    override fun ensureForeground(): Boolean = host.ensureForeground()

    override fun onForegroundFailure(reason: String) = host.onForegroundFailure(reason)

    override fun startAnchor(reason: String) {
        UsbExclusiveBackgroundAudioAnchor.start(context, reason)
    }

    override fun stopAnchor(reason: String) = UsbExclusiveBackgroundAudioAnchor.stop(reason)

    override fun refreshNative() = UsbExclusiveSessionController.refresh(context)

    override fun maintainWakeLock() = UsbExclusiveSessionController.maintainWakeLock(context, "service_keepalive")

    override fun updatePlaybackPresentation() = host.updatePlaybackPresentation()

    override fun nativeState(): UsbExclusiveNativeState = UsbExclusiveSessionController.state.value

    override fun pathState(): UsbExclusiveAudioPathState = UsbExclusiveAudioPathTracker.state.value

    override fun wakeLockHeld(): Boolean = UsbExclusiveWakeLock.isHeld()

    override fun anchorDiagnostic(): String = UsbExclusiveBackgroundAudioAnchor.diagnosticSummary()

    override fun usbPlaybackEnabled(): Boolean = PlayerManager.usbExclusivePlaybackEnabled

    override fun transportActive(): Boolean = PlayerManager.isTransportActiveWithoutInitialization()

    override fun recover(reason: String) {
        PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(reason = reason, forceRecovery = true)
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
