package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences

internal fun resolvePlaybackControlLayoutPreferences(
    nowPlayingPlacementValue: Int?,
    nowPlayingSizeValue: Int?,
    lyricsSizeValue: Int?,
    defaults: PlaybackControlLayoutPreferences = PlaybackControlLayoutPreferences()
): PlaybackControlLayoutPreferences {
    return PlaybackControlLayoutPreferences(
        nowPlayingPlacement = NowPlayingControlPlacement.entries
            .getOrElse(nowPlayingPlacementValue ?: -1) { defaults.nowPlayingPlacement },
        nowPlayingSize = PlaybackControlSize.entries
            .getOrElse(nowPlayingSizeValue ?: -1) { defaults.nowPlayingSize },
        lyricsSize = PlaybackControlSize.entries
            .getOrElse(lyricsSizeValue ?: -1) { defaults.lyricsSize }
    )
}
