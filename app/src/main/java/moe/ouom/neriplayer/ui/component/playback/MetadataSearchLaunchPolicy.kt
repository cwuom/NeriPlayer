package moe.ouom.neriplayer.ui.component.playback

import androidx.media3.common.Player

internal fun shouldDeferMetadataAutoSearch(
    pendingMediaLoad: Boolean,
    playbackState: Int,
    playWhenReady: Boolean
): Boolean {
    if (pendingMediaLoad) return true
    if (!playWhenReady) return false
    return playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING
}
