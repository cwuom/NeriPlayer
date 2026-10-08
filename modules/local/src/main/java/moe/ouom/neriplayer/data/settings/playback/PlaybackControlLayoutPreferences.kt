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
            .entryAtOr(nowPlayingPlacementValue, defaults.nowPlayingPlacement),
        nowPlayingSize = PlaybackControlSize.entries.entryAtOr(nowPlayingSizeValue, defaults.nowPlayingSize),
        lyricsSize = PlaybackControlSize.entries.entryAtOr(lyricsSizeValue, defaults.lyricsSize)
    )
}

private fun <T> List<T>.entryAtOr(ordinal: Int?, fallback: T): T = ordinal?.let(::getOrNull) ?: fallback
