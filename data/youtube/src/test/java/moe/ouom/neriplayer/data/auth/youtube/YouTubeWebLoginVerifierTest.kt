package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeWebLoginVerifierTest {

    @Test
    fun parseYouTubeBootstrapSessionState_rejectsGuestBootstrap() {
        val state = parseYouTubeBootstrapSessionState(
            """
                <html><head><script>
                  ytcfg.set({
                    "LOGGED_IN": false,
                    "SESSION_INDEX": "0"
                  });
                </script></head></html>
            """.trimIndent(),
            origin = "https://music.youtube.com"
        )

        assertFalse(state.hasLiveSessionSignal())
        assertEquals("0", state.sessionIndex)
    }

    @Test
    fun parseYouTubeBootstrapSessionState_acceptsLoggedInBootstrap() {
        val state = parseYouTubeBootstrapSessionState(
            """
                <html><head><script>
                  ytcfg.set({
                    "LOGGED_IN": true,
                    "SESSION_INDEX": "0",
                    "USER_SESSION_ID": "session-id"
                  });
                </script></head></html>
            """.trimIndent()
        )

        assertTrue(state.hasLiveSessionSignal())
        assertTrue(state.loggedIn)
    }

    @Test
    fun verifyBlocking_returnsMissingWithoutLoginCookies() {
        var executed = false
        val verifier = YouTubeWebLoginVerifier(
            executeText = {
                executed = true
                ""
            }
        )

        val state = verifier.verifyBlocking(YouTubeAuthBundle())

        assertFalse(state.hasLiveSessionSignal())
        assertFalse(executed)
    }

    @Test
    fun verifyBlocking_fallsBackToSecondOrigin() {
        val responses = ArrayDeque(
            listOf(
                """
                    <html><head><script>
                      ytcfg.set({
                        "LOGGED_IN": false,
                        "SESSION_INDEX": "0"
                      });
                    </script></head></html>
                """.trimIndent(),
                """
                    <html><head><script>
                      ytcfg.set({
                        "LOGGED_IN": true,
                        "SESSION_INDEX": "1",
                        "DELEGATED_SESSION_ID": "delegated-id"
                      });
                    </script></head></html>
                """.trimIndent()
            )
        )
        val verifier = YouTubeWebLoginVerifier(
            executeText = { responses.removeFirst() }
        )

        val state = verifier.verifyBlocking(
            YouTubeAuthBundle(
                cookies = mapOf("SAPISID" to "cookie-value"),
                xGoogAuthUser = "0"
            )
        )

        assertTrue(state.hasLiveSessionSignal())
        assertEquals("https://www.youtube.com", state.origin)
        assertEquals("1", state.sessionIndex)
    }
}
