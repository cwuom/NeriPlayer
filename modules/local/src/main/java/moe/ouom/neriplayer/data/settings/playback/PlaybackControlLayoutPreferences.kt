package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences

internal fun resolvePlaybackControlLayoutPreferences(
    nowPlayingPlacementValue: Int?,
    nowPlayingSizeValue: Int?,
    lyricsSizeValue: Int?
): PlaybackControlLayoutPreferences {
    return PlaybackControlLayoutPreferences(
        nowPlayingPlacement = NowPlayingControlPlacement.entries
            .getOrElse(nowPlayingPlacementValue ?: -1) { NowPlayingControlPlacement.LOWER },
        nowPlayingSize = PlaybackControlSize.entries
            .getOrElse(nowPlayingSizeValue ?: -1) { PlaybackControlSize.MEDIUM },
        lyricsSize = PlaybackControlSize.entries
            .getOrElse(lyricsSizeValue ?: -1) { PlaybackControlSize.MEDIUM }
    )
}
