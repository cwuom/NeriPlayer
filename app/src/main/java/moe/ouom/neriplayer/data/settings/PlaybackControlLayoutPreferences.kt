package moe.ouom.neriplayer.data.settings

enum class NowPlayingControlPlacement {
    LOWER,
    BOTTOM,
    BOTTOM_WITH_PROGRESS;

    val placesControlsAtBottom: Boolean
        get() = this != LOWER

    val placesProgressAtBottom: Boolean
        get() = this == BOTTOM_WITH_PROGRESS
}

enum class PlaybackControlSize(
    val scale: Float
) {
    SMALL(0.9f),
    MEDIUM(1f),
    LARGE(1.2f)
}

data class PlaybackControlLayoutPreferences(
    val nowPlayingPlacement: NowPlayingControlPlacement = NowPlayingControlPlacement.LOWER,
    val nowPlayingSize: PlaybackControlSize = PlaybackControlSize.MEDIUM,
    val lyricsSize: PlaybackControlSize = PlaybackControlSize.MEDIUM
)

internal fun resolvePlaybackControlLayoutPreferences(
    nowPlayingPlacementValue: Int?,
    nowPlayingSizeValue: Int?,
    lyricsSizeValue: Int?
): PlaybackControlLayoutPreferences {
    return PlaybackControlLayoutPreferences(
        nowPlayingPlacement = NowPlayingControlPlacement.values()
            .getOrElse(nowPlayingPlacementValue ?: -1) { NowPlayingControlPlacement.LOWER },
        nowPlayingSize = PlaybackControlSize.values()
            .getOrElse(nowPlayingSizeValue ?: -1) { PlaybackControlSize.MEDIUM },
        lyricsSize = PlaybackControlSize.values()
            .getOrElse(lyricsSizeValue ?: -1) { PlaybackControlSize.MEDIUM }
    )
}
