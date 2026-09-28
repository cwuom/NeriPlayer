package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubePlaybackSourcePreferenceTest {
    @Test
    fun normalize_keepsSupportedValuesAndAliases() {
        assertEquals(
            YouTubePlaybackSourcePreference.Automatic.storageValue,
            YouTubePlaybackSourcePreferencePolicy.normalize(" automatic ")
        )
        assertEquals(
            YouTubePlaybackSourcePreference.TvHtml5.storageValue,
            YouTubePlaybackSourcePreferencePolicy.normalize("TV_HTML5")
        )
        assertEquals(
            YouTubePlaybackSourcePreference.VisionOs.storageValue,
            YouTubePlaybackSourcePreferencePolicy.normalize("vision_os")
        )
        assertEquals(
            YouTubePlaybackSourcePreference.AndroidVr.storageValue,
            YouTubePlaybackSourcePreferencePolicy.normalize("AndroidVr")
        )
        assertEquals(
            YouTubePlaybackSourcePreference.WebCreator.storageValue,
            YouTubePlaybackSourcePreferencePolicy.normalize("creator")
        )
    }

    @Test
    fun normalize_usesAutomaticForMissingOrUnsupportedValues() {
        assertEquals(
            DEFAULT_YOUTUBE_PLAYBACK_SOURCE,
            YouTubePlaybackSourcePreferencePolicy.normalize("")
        )
        assertEquals(
            DEFAULT_YOUTUBE_PLAYBACK_SOURCE,
            YouTubePlaybackSourcePreferencePolicy.normalize("unknown-client")
        )
        assertEquals(
            YouTubePlaybackSourcePreference.Automatic,
            YouTubePlaybackSourcePreferencePolicy.fromStorage(null)
        )
    }
}
