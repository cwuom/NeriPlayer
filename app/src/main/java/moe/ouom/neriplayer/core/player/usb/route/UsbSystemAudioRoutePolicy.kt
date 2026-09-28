package moe.ouom.neriplayer.core.player.usb.route

import androidx.media3.common.Player

internal fun UsbSystemAudioSnapshot.watchdogRouteStillCurrent(expectedGeneration: Long, currentGeneration: Long): Boolean =
    playerInitialized && !usbEnabled && expectedGeneration == currentGeneration && playWhenReady && hasMediaItem

internal fun UsbSystemAudioSnapshot.playbackStalledAt(index: Int, positionMs: Long): Boolean =
    mediaItemIndex == index && !isPlaying && playbackState.canIndicateSystemAudioStall() &&
        this.positionMs.coerceAtLeast(0L) <= positionMs + SYSTEM_AUDIO_STALL_TOLERANCE_MS

internal fun UsbSystemAudioSnapshot.releaseStillCurrent(
    expectedGeneration: Long,
    currentGeneration: Long,
    disabling: Boolean
): Boolean = playerInitialized && expectedGeneration == currentGeneration && (!disabling || !usbEnabled)

internal fun UsbSystemAudioSnapshot.canResetAfterRelease(expectedGeneration: Long, currentGeneration: Long): Boolean =
    playerInitialized && !usbEnabled && expectedGeneration == currentGeneration

internal fun UsbSystemAudioSnapshot.hasResetMedia(): Boolean = mediaItemCount > 0 && hasMediaItem

private fun Int.canIndicateSystemAudioStall(): Boolean =
    this == Player.STATE_IDLE || this == Player.STATE_BUFFERING || this == Player.STATE_READY

private const val SYSTEM_AUDIO_STALL_TOLERANCE_MS = 50L
