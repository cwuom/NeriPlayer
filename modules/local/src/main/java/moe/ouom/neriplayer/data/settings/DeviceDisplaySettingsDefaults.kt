package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences

private const val TABLET_SMALLEST_SCREEN_WIDTH_DP = 600

internal fun defaultPlaybackControlLayoutPreferences(
    smallestScreenWidthDp: Int
): PlaybackControlLayoutPreferences = PlaybackControlLayoutPreferences(
    nowPlayingPlacement = if (smallestScreenWidthDp >= TABLET_SMALLEST_SCREEN_WIDTH_DP) {
        NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS
    } else {
        NowPlayingControlPlacement.LOWER
    }
)

internal fun defaultLyricFontScales(smallestScreenWidthDp: Int): LyricFontScales {
    val isTablet = smallestScreenWidthDp >= TABLET_SMALLEST_SCREEN_WIDTH_DP
    val lyricScale = if (isTablet) 1.25f else 1.0f
    val translationScale = if (isTablet) 1.15f else 1.0f
    return LyricFontScales(
        coverLyric = lyricScale,
        coverTranslation = translationScale,
        lyricsPageLyric = lyricScale,
        lyricsPageTranslation = translationScale
    )
}
