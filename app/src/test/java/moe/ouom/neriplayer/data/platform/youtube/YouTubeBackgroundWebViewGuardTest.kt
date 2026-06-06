package moe.ouom.neriplayer.data.platform.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBackgroundWebViewGuardTest {

    @Test
    fun buildYouTubeBackgroundMediaSessionGuardScript_patchesPositionStateOnly() {
        val script = buildYouTubeBackgroundMediaSessionGuardScript()

        assertTrue(script.contains("navigator?.mediaSession"))
        assertTrue(script.contains("setPositionState"))
        assertTrue(script.contains("Object.defineProperty"))
        assertTrue(script.contains("MediaSession?.prototype"))
    }

    @Test
    fun youtubeBackgroundWebViewGuardOriginRules_matchBootstrapOrigins() {
        assertEquals(
            setOf(
                "https://www.youtube.com",
                "https://music.youtube.com"
            ),
            YOUTUBE_BACKGROUND_WEBVIEW_GUARD_ORIGIN_RULES
        )
    }
}
