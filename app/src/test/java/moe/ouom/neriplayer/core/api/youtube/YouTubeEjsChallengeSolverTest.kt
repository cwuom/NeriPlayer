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
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

class YouTubeEjsChallengeSolverTest {

    @Test
    fun ejsIsolateLetsTheProviderChooseItsBoundedDeviceHeapWhenSupported() {
        assertEquals(
            0L,
            YOUTUBE_EJS_ISOLATE_MAX_HEAP_SIZE_BYTES
        )
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
    fun memoryLimitFailureGetsOneFreshSandboxRetryInsteadOfCooldown() {
        val memoryLimitResult = YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            cause = ExecutionException(MemoryLimitExceededException("memory limit exceeded"))
        )
        val ordinaryResult = YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            cause = IllegalStateException("ordinary failure")
        )

        assertTrue(isYouTubeEjsMemoryLimitFailure(memoryLimitResult.cause))
        assertTrue(shouldRetryYouTubeEjsSandboxAfterMemoryFailure(memoryLimitResult))
        assertFalse(shouldRetryYouTubeEjsSandboxAfterMemoryFailure(ordinaryResult))
    }

    @Test
    fun unavailableSandboxStatesUseTheLocalWebViewFallback() {
        val unavailableStatuses = listOf(
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_UNSUPPORTED,
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TEMPORARILY_DISABLED,
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_CONNECTION_FAILED,
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT,
            YouTubeJsChallengeSolveStatus.MISSING_SANDBOX_FEATURES
        )

        unavailableStatuses.forEach { status ->
            assertTrue(
                shouldUseYouTubeEjsWebViewFallback(
                    YouTubeJsChallengeSolveResult(status = status)
                )
            )
        }
    }

    @Test
    fun onlyTerminalSandboxEvaluationFailuresUseTheLocalWebViewFallback() {
        val memoryFailure = YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            cause = ExecutionException(MemoryLimitExceededException("memory limit exceeded"))
        )
        val deadSandboxFailure = YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            cause = ExecutionException(SandboxDeadException("sandbox was dead"))
        )
        val ordinaryFailure = YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            cause = IllegalStateException("player parser failed")
        )

        assertTrue(shouldUseYouTubeEjsWebViewFallback(memoryFailure))
        assertTrue(shouldUseYouTubeEjsWebViewFallback(deadSandboxFailure))
        assertFalse(shouldUseYouTubeEjsWebViewFallback(ordinaryFailure))
    }

    @Test
    fun ejsPlayerScriptMemoryCacheKeepsOnlyTheActivePair() {
        assertEquals(2, YOUTUBE_EJS_PLAYER_SCRIPT_MEMORY_CACHE_CAPACITY)
    }

    @Test
    fun webViewWarmupHedgesWhenTheCompleteSandboxSessionMissesTheGracePeriod() {
        assertFalse(
            shouldStartYouTubeEjsWebViewWarmup(
                sandboxSessionReadyWithinGracePeriod = true
            )
        )
        assertTrue(
            shouldStartYouTubeEjsWebViewWarmup(
                sandboxSessionReadyWithinGracePeriod = false
            )
        )
        assertTrue(YOUTUBE_EJS_WEBVIEW_WARMUP_HEDGE_DELAY_MS > 0L)
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
    fun timedOutEjsSessionsAreDiscardedBeforeTheNextChallenge() {
        assertTrue(
            shouldDiscardYouTubeEjsPlayerSession(
                ExecutionException(TimeoutException("timed out"))
            )
        )
        assertTrue(
            shouldDiscardYouTubeEjsPlayerSession(
                ExecutionException(MemoryLimitExceededException("memory limit exceeded"))
            )
        )
        assertFalse(shouldDiscardYouTubeEjsPlayerSession(IllegalStateException("ordinary failure")))
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
    fun playerSessionInitializationPreprocessesThePlayerOnlyOnce() {
        val initializationScript = buildYouTubeEjsPlayerSessionInitializeScript("player_js_test")

        assertTrue(initializationScript.contains("consumeNamedDataAsArrayBuffer(\"player_js_test\")"))
        assertTrue(initializationScript.contains("output_preprocessed: true"))
        assertTrue(initializationScript.contains("Function(\"_result\", _preprocessed.preprocessed_player)"))
        assertTrue(initializationScript.contains("__neriPlayerEjsChallengeSession"))
        assertTrue(initializationScript.contains("type: \"session-ready\""))
    }

    @Test
    fun loadedPlayerSessionSolvesBothChallengesWithoutReloadingPlayerData() {
        val solveScript = buildYouTubeEjsLoadedPlayerSolveScript(
            encryptedSignature = "sig-challenge",
            throttlingParameter = "n-challenge"
        )

        assertTrue(solveScript.contains("__neriPlayerEjsChallengeSession"))
        assertTrue(solveScript.contains("\"type\":\"sig\""))
        assertTrue(solveScript.contains("\"type\":\"n\""))
        assertTrue(solveScript.contains("_solver(_challenge)"))
        assertFalse(solveScript.contains("consumeNamedDataAsArrayBuffer"))
        assertFalse(solveScript.contains("jsc("))
    }

    @Test
    fun webViewFallbackDocumentLoadsOnlyBundledSolverAssets() {
        val document = buildYouTubeEjsWebViewDocument("const player = '</script>';")

        assertTrue(document.contains("/assets/youtube/yt.solver.lib.min.js"))
        assertTrue(document.contains("/assets/youtube/yt.solver.core.min.js"))
        assertTrue(document.contains("__neriPlayerEjsChallengeSession"))
        assertTrue(document.contains("__neriPlayerEjsWebViewInitResult"))
        assertTrue(document.contains("<\\/script>"))
        assertFalse(document.contains("file:///"))
    }

    @Test
    fun playerSessionReadyResponseRequiresAtLeastOneChallengeFunction() {
        assertTrue(
            isYouTubeEjsPlayerSessionReady(
                """{"type":"session-ready","hasN":true,"hasSig":false}"""
            )
        )
        assertFalse(
            isYouTubeEjsPlayerSessionReady(
                """{"type":"session-ready","hasN":false,"hasSig":false}"""
            )
        )
        assertFalse(isYouTubeEjsPlayerSessionReady("not-json"))
    }

    @Test
    fun completeChallengeCacheBypassesTheFallbackSession() {
        val cached = cachedYouTubeEjsChallengeResult(
            requestedSignature = "signature",
            requestedThrottling = "throttling",
            cachedSignature = "resolved-signature",
            cachedThrottling = "resolved-throttling"
        )
        val incomplete = cachedYouTubeEjsChallengeResult(
            requestedSignature = "signature",
            requestedThrottling = "throttling",
            cachedSignature = "resolved-signature",
            cachedThrottling = null
        )

        assertEquals(YouTubeJsChallengeSolveStatus.SUCCESS, cached?.status)
        assertEquals("resolved-signature", cached?.solution?.signature)
        assertEquals("resolved-throttling", cached?.solution?.throttlingParameter)
        assertEquals(null, incomplete)
    }

    @Test
    fun playerSessionCacheReusesTheCurrentScriptAndClosesReplacedSessions() {
        val closedSessions = mutableListOf<String>()
        val cache = YouTubeEjsPlayerSessionCache<String> { closedSessions += it }
        var creationCount = 0

        val first = cache.withSession(
            playerJsUrl = "https://player/a.js",
            createSession = { "session-${++creationCount}" },
            block = { it }
        )
        val reused = cache.withSession(
            playerJsUrl = "https://player/a.js",
            createSession = { "session-${++creationCount}" },
            block = { it }
        )
        val replaced = cache.withSession(
            playerJsUrl = "https://player/b.js",
            createSession = { "session-${++creationCount}" },
            block = { it }
        )

        assertEquals("session-1", first)
        assertEquals("session-1", reused)
        assertEquals("session-2", replaced)
        assertEquals(2, creationCount)
        assertEquals(listOf("session-1"), closedSessions)

        cache.invalidate()

        assertEquals(listOf("session-1", "session-2"), closedSessions)
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
