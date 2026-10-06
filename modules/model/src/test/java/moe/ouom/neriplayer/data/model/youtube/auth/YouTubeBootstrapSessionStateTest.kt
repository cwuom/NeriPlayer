package moe.ouom.neriplayer.data.model.youtube.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBootstrapSessionStateTest {

    @Test
    fun `login flag or any non blank session id is a live session signal`() {
        assertTrue(YouTubeBootstrapSessionState(loggedIn = true).hasLiveSessionSignal())
        assertTrue(YouTubeBootstrapSessionState(delegatedSessionId = "delegated").hasLiveSessionSignal())
        assertTrue(YouTubeBootstrapSessionState(userSessionId = "user").hasLiveSessionSignal())
        assertFalse(
            YouTubeBootstrapSessionState(
                origin = "https://music.youtube.com",
                sessionIndex = "0",
                delegatedSessionId = " ",
                userSessionId = " "
            ).hasLiveSessionSignal()
        )
    }
}
