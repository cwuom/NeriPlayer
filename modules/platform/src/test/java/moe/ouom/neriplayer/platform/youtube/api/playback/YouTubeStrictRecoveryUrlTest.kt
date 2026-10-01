package moe.ouom.neriplayer.platform.youtube.api.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStrictRecoveryUrlTest {
    @Test
    fun strictRecovery_requiresPoTokenForWebClientsButKeepsAnonymousFallbacks() {
        val streamPrefix =
            "https://rr1---sn.googlevideo.com/videoplayback?source=youtube&c="

        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}WEB_REMIX"))
        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}WEB_CREATOR"))
        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}TVHTML5"))
        assertTrue(
            isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}TVHTML5&pot=po-token")
        )
        assertTrue(isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}VISIONOS"))
        assertTrue(isTrustedYouTubeDirectUrlForStrictRecovery("${streamPrefix}ANDROID_VR"))
    }
}
