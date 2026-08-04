package moe.ouom.neriplayer.core.api.youtube

import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.SandboxDeadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException

class YouTubeEjsChallengeSolverTest {

    @Test
    fun ejsIsolateUsesExplicitHeapLimitOnlyWhenSupported() {
        assertEquals(
            YOUTUBE_EJS_ISOLATE_MAX_HEAP_SIZE_BYTES,
            youtubeEjsIsolateMaxHeapSizeBytes(supportsExplicitHeapLimit = true)
        )
        assertEquals(
            null,
            youtubeEjsIsolateMaxHeapSizeBytes(supportsExplicitHeapLimit = false)
        )
    }

    @Test
    fun terminalEjsSandboxFailuresInvalidateTheSharedSandbox() {
        assertTrue(
            shouldInvalidateYouTubeEjsSandbox(
                ExecutionException(MemoryLimitExceededException("memory limit exceeded"))
            )
        )
        assertTrue(
            shouldInvalidateYouTubeEjsSandbox(
                ExecutionException(SandboxDeadException("sandbox was dead"))
            )
        )
        assertFalse(shouldInvalidateYouTubeEjsSandbox(IllegalStateException("ordinary failure")))
    }

    @Test
    fun sandboxBootstrapInstallsIcuFallbackBeforeLoadingThePlayerSolver() {
        val libScript = "const lib = { marker: 'library' };"
        val coreScript = "const coreMarker = 'core';"

        val bootstrapScript = buildYouTubeEjsSandboxBootstrapScript(
            libScript = libScript,
            coreScript = coreScript
        )

        val fallbackIndex = bootstrapScript.indexOf("patchLocaleStringIfBroken")
        assertTrue(fallbackIndex >= 0)
        assertTrue(fallbackIndex < bootstrapScript.indexOf(libScript))
        assertTrue(bootstrapScript.indexOf(libScript) < bootstrapScript.indexOf(coreScript))
        assertTrue(bootstrapScript.contains("Number.prototype"))
        assertTrue(bootstrapScript.contains("Date.prototype"))
        assertTrue(bootstrapScript.contains("Boolean.prototype"))
        assertTrue(bootstrapScript.contains("{ style: \"percent\" }"))
        assertTrue(
            bootstrapScript.contains("sample === null || typeof sample === \"undefined\"")
        )
        assertTrue(bootstrapScript.contains("return this.toString();"))
    }

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
