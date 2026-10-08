package moe.ouom.neriplayer.core.player.usb.route

import android.os.Looper
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

private val IdleUsbRouteTransport = Triple(false, false, Player.STATE_IDLE)

internal fun PlayerManager.usbRoutePlayerSnapshot(includeTransportState: Boolean = false): UsbRoutePlayerSnapshot =
    selectUsbRoutePlayerSnapshot(
        initialized = isPlayerInitialized(),
        onPlayerThread = { Looper.myLooper() == player.applicationLooper },
        live = { player.activeUsbRouteSnapshot(includeTransportState) },
        mirrored = { mirroredUsbRouteSnapshot(includeTransportState) }
    )

/** ExoPlayer 只能在应用线程读取；音频 sink 等其他线程改用主线程同步出来的镜像状态 */
internal fun selectUsbRoutePlayerSnapshot(
    initialized: Boolean,
    onPlayerThread: () -> Boolean,
    live: () -> UsbRoutePlayerSnapshot,
    mirrored: () -> UsbRoutePlayerSnapshot
): UsbRoutePlayerSnapshot = when {
    !initialized -> UsbRoutePlayerSnapshot.Uninitialized
    onPlayerThread() -> live()
    else -> mirrored()
}

/** 播放器每次只持有当前一首，镜像状态按单曲换算 */
internal fun mirroredUsbRoutePlayerSnapshot(
    currentSong: Any?,
    positionMs: Long,
    includeTransportState: Boolean,
    transport: () -> Triple<Boolean, Boolean, Int>
): UsbRoutePlayerSnapshot {
    val hasMediaItem = currentSong != null
    val state = if (includeTransportState) transport() else IdleUsbRouteTransport
    return UsbRoutePlayerSnapshot(
        initialized = true,
        hasMediaItem = hasMediaItem,
        mediaItemCount = if (hasMediaItem) 1 else 0,
        mediaItemIndex = 0,
        positionMs = positionMs,
        playWhenReady = state.first,
        isPlaying = state.second,
        playbackState = state.third
    )
}

private fun PlayerManager.mirroredUsbRouteSnapshot(includeTransportState: Boolean): UsbRoutePlayerSnapshot =
    mirroredUsbRoutePlayerSnapshot(
        currentSong = _currentSongFlow.value,
        positionMs = _playbackPositionMs.value,
        includeTransportState = includeTransportState,
        transport = { Triple(_playWhenReadyFlow.value, _isPlayingFlow.value, _playerPlaybackStateFlow.value) }
    )

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
        IdleUsbRouteTransport
    }
