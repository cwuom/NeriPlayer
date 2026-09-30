package moe.ouom.neriplayer.data.ltw.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStreamRecoveryPolicyTest {
    @Test
    fun `shared stream reload skips unchanged pending unavailable and complete local sources`() {
        val url = "https://music.126.net/new"
        assertTrue(shouldReloadListenTogetherAuthoritativeStream(url, null))
        assertFalse(shouldReloadListenTogetherAuthoritativeStream(null, null))
        assertFalse(shouldReloadListenTogetherAuthoritativeStream(url, url))
        assertFalse(shouldReloadListenTogetherAuthoritativeStream(url, "https://music.126.net/complete", false))
        assertFalse(shouldReloadListenTogetherAuthoritativeStream(url, null, pendingAuthoritativeStreamUrl = url))
    }

    @Test
    fun `link retries require a pending unresolved target and finite attempt budget`() {
        assertFalse(shouldRetryControllerLinkResolution(0, 0, false, false))
        assertFalse(shouldRetryControllerLinkResolution(0, 3, true, false))
        assertFalse(shouldRetryControllerLinkResolution(0, 3, false, true))
        assertTrue(shouldRetryControllerLinkResolution(0, 3, false, false))
        assertFalse(shouldPublishControllerLinkUnavailable(0, 0, false, false))
        assertFalse(shouldPublishControllerLinkUnavailable(0, 3, false, true))
        assertFalse(shouldPublishControllerLinkUnavailable(0, 3, false, false))
        assertTrue(shouldPublishControllerLinkUnavailable(2, 3, false, false))
        assertFalse(shouldDeferControllerLinkResolution(true, null, "target"))
        assertFalse(shouldDeferControllerLinkResolution(true, " ", "target"))
        assertFalse(shouldDeferControllerLinkResolution(true, "target", " "))
    }
}
