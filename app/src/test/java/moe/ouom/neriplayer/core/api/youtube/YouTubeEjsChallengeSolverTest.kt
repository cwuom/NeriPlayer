package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException

class YouTubeEjsChallengeSolverTest {

    @Test
    fun propagateYouTubeJsChallengeCancellationRestoresInterruptedStatus() {
        val interrupted = InterruptedException("cancelled")

        try {
            propagateYouTubeJsChallengeCancellation(interrupted)
            fail("interruption must be rethrown")
        } catch (error: InterruptedException) {
            assertSame(interrupted, error)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun propagateYouTubeJsChallengeCancellationRethrowsCoroutineCancellation() {
        val cancellation = CancellationException("cancelled")

        try {
            propagateYouTubeJsChallengeCancellation(cancellation)
            fail("cancellation must be rethrown")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun parseYouTubeJsChallengeSolveResponse_returnsSuccessWhenSignatureIsResolved() {
        val result = parseYouTubeJsChallengeSolveResponse(
            responseJson = """
                {
                  "type": "result",
                  "responses": [
                    {
                      "type": "result",
                      "data": {
                        "sig-challenge": "resolved-signature"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            requestedSignature = "sig-challenge",
            requestedThrottling = null
        )

        assertTrue(result.isSuccess)
        assertEquals(YouTubeJsChallengeSolveStatus.SUCCESS, result.status)
        assertEquals("resolved-signature", result.solution.signature)
    }

    @Test
    fun parseYouTubeJsChallengeSolveResponse_returnsExplicitStatusWhenSignatureIsMissing() {
        val result = parseYouTubeJsChallengeSolveResponse(
            responseJson = """
                {
                  "type": "result",
                  "responses": [
                    {
                      "type": "result",
                      "data": {
                        "another-challenge": "resolved-signature"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            requestedSignature = "sig-challenge",
            requestedThrottling = null
        )

        assertEquals(YouTubeJsChallengeSolveStatus.SIGNATURE_NOT_RESOLVED, result.status)
        assertEquals(null, result.solution.signature)
    }

    @Test
    fun parseYouTubeJsChallengeSolveResponse_returnsExplicitStatusWhenThrottlingIsMissing() {
        val result = parseYouTubeJsChallengeSolveResponse(
            responseJson = """
                {
                  "type": "result",
                  "responses": [
                    {
                      "type": "result",
                      "data": {
                        "sig-challenge": "resolved-signature"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            requestedSignature = null,
            requestedThrottling = "n-challenge"
        )

        assertEquals(YouTubeJsChallengeSolveStatus.THROTTLING_NOT_RESOLVED, result.status)
        assertEquals(null, result.solution.throttlingParameter)
    }

    @Test
    fun parseYouTubeJsChallengeSolveResponse_returnsInvalidResponseForUnexpectedPayload() {
        val result = parseYouTubeJsChallengeSolveResponse(
            responseJson = """{"type":"error"}""",
            requestedSignature = "sig-challenge",
            requestedThrottling = null
        )

        assertEquals(YouTubeJsChallengeSolveStatus.INVALID_RESPONSE, result.status)
    }
}
