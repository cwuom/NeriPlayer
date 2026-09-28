package moe.ouom.neriplayer.core.api.youtube

import moe.ouom.neriplayer.data.settings.YouTubePlaybackSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackSourcePolicyTest {
    private val automaticOrder = listOf(
        YouTubePlayerClientSource.VISION_OS,
        YouTubePlayerClientSource.ANDROID_VR,
        YouTubePlayerClientSource.WEB_REMIX,
        YouTubePlayerClientSource.TV_HTML5,
        YouTubePlayerClientSource.WEB_CREATOR,
        YouTubePlayerClientSource.TV_HTML5_LEGACY
    )

    @Test
    fun automatic_usesTheLowLatencyFallbackOrder() {
        assertEquals(
            automaticOrder,
            resolveYouTubePlayerClientOrder(YouTubePlaybackSourcePreference.Automatic)
        )
    }

    @Test
    fun automatic_authenticatedPlaybackStartsWithPoTokenCapableWebRemix() {
        assertEquals(
            listOf(
                YouTubePlayerClientSource.WEB_REMIX,
                YouTubePlayerClientSource.TV_HTML5,
                YouTubePlayerClientSource.WEB_CREATOR,
                YouTubePlayerClientSource.TV_HTML5_LEGACY,
                YouTubePlayerClientSource.VISION_OS,
                YouTubePlayerClientSource.ANDROID_VR
            ),
            resolveYouTubePlayerClientOrder(
                preference = YouTubePlaybackSourcePreference.Automatic,
                preferAuthenticatedWebPlayback = true
            )
        )
    }

    @Test
    fun anonymousNewPipeFallbackIsDisabledForSignedInPlayback() {
        assertFalse(shouldUseAnonymousYouTubeNewPipeFallback(hasLoginCookies = true))
        assertTrue(shouldUseAnonymousYouTubeNewPipeFallback(hasLoginCookies = false))
    }

    @Test
    fun manualSource_movesOnlyTheSelectedClientAheadOfAllFallbacks() {
        val manualSources = mapOf(
            YouTubePlaybackSourcePreference.VisionOs to YouTubePlayerClientSource.VISION_OS,
            YouTubePlaybackSourcePreference.AndroidVr to YouTubePlayerClientSource.ANDROID_VR,
            YouTubePlaybackSourcePreference.WebRemix to YouTubePlayerClientSource.WEB_REMIX,
            YouTubePlaybackSourcePreference.TvHtml5 to YouTubePlayerClientSource.TV_HTML5,
            YouTubePlaybackSourcePreference.WebCreator to YouTubePlayerClientSource.WEB_CREATOR
        )

        manualSources.forEach { (preference, source) ->
            assertEquals(
                listOf(source) + automaticOrder.filterNot { it == source },
                resolveYouTubePlayerClientOrder(preference)
            )
        }
    }
}
