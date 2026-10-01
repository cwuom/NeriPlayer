package moe.ouom.neriplayer.core.player.usb.route

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.PlayerManager

internal data class UsbRoutePlayerSnapshot(
    val initialized: Boolean,
    val hasMediaItem: Boolean,
    val mediaItemCount: Int,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val playbackState: Int
) {
    companion object {
        val Uninitialized = UsbRoutePlayerSnapshot(
            initialized = false,
            hasMediaItem = false,
            mediaItemCount = 0,
            mediaItemIndex = 0,
            positionMs = 0L,
            playWhenReady = false,
            isPlaying = false,
            playbackState = Player.STATE_IDLE
        )
    }
}

internal fun PlayerManager.usbRoutePlayerSnapshot(includeTransportState: Boolean = false): UsbRoutePlayerSnapshot {
    if (!isPlayerInitialized()) return UsbRoutePlayerSnapshot.Uninitialized
    return player.activeUsbRouteSnapshot(includeTransportState)
}

private fun Player.activeUsbRouteSnapshot(includeTransportState: Boolean): UsbRoutePlayerSnapshot {
    val transport = usbRouteTransport(includeTransportState)
    return UsbRoutePlayerSnapshot(
        initialized = true,
        hasMediaItem = currentMediaItem != null,
        mediaItemCount = mediaItemCount,
        mediaItemIndex = currentMediaItemIndex,
        positionMs = currentPosition,
        playWhenReady = transport.first,
        isPlaying = transport.second,
        playbackState = transport.third
    )
}

private fun Player.usbRouteTransport(includeTransportState: Boolean): Triple<Boolean, Boolean, Int> =
    if (includeTransportState) {
        Triple(playWhenReady, isPlaying, playbackState)
    } else {
        Triple(false, false, Player.STATE_IDLE)
    }
