package moe.ouom.neriplayer.api.youtube.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthRecoveryPolicyTest {
    @Test
    fun webAuthRecoveryAcceptsUnauthorizedOnly() {
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 401")))
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 403")))
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 429")))
        assertTrue(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 401")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 403")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 429")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("blocked response")))
    }
}
