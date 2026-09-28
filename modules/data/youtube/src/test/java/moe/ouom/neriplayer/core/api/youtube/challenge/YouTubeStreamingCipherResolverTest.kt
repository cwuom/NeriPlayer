package moe.ouom.neriplayer.core.api.youtube.challenge

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.concurrent.atomic.AtomicBoolean

class YouTubeStreamingCipherResolverTest {
    private val playerJsUrl = "https://music.youtube.com/s/player/base.js"

    @Before
    fun resetFallbackFailures() {
        NewPipeFallbackTracker.reset()
    }

    @After
    fun clearFallbackFailures() {
        NewPipeFallbackTracker.reset()
    }

    @Test
    fun unresolvedSignatureReturnsNullWhenBothEnginesAreUnavailable() = runBlocking {
        NewPipeFallbackTracker.recordSignatureFailure(playerJsUrl)
        val resolver = resolverWithoutEjs()

        assertNull(resolver.resolveSignatureAsync("encrypted-signature"))
    }

    @Test
    fun unresolvedThrottlingDiscardsCandidateInsteadOfReturningObfuscatedUrl() = runBlocking {
        NewPipeFallbackTracker.recordThrottlingFailure(playerJsUrl)
        val resolver = resolverWithoutEjs()
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"

        assertEquals("", resolver.resolveStreamingUrl(url))
        assertEquals("", resolver.resolveStreamingUrlAsync(url))
    }

    @Test
    fun urlWithoutThrottlingParameterPassesThroughUnchanged() = runBlocking {
        val resolver = resolverWithoutEjs()
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140"

        assertEquals(url, resolver.resolveStreamingUrl(url))
        assertEquals(url, resolver.resolveStreamingUrlAsync(url))
    }

    @Test
    fun prewarmWithoutEjsLeavesSynchronousCacheEmpty() = runBlocking {
        val resolver = resolverWithoutEjs()

        resolver.prewarmChallengesAsync("encrypted-signature", "obfuscated")

        assertNull(resolver.resolveSignature("encrypted-signature"))
    }

    @Test
    fun prewarmRequiresBothNonBlankChallenges() {
        assertNull(cipherPrewarmRequest(null, "n"))
        assertNull(cipherPrewarmRequest(" ", "n"))
        assertNull(cipherPrewarmRequest("sig", null))
        assertNull(cipherPrewarmRequest("sig", " "))
        val request = cipherPrewarmRequest("sig", "n")
        assertEquals("sig", request?.signature)
        assertEquals("n", request?.throttling)
    }

    @Test
    fun prewarmCachesSignatureAndThrottlingForSynchronousReads() {
        runBlocking {
            val solver = mock(YouTubeEjsChallengeSolver::class.java)
            val result = YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution(
                    signature = "resolved-signature",
                    throttlingParameter = "resolved-n"
                )
            )
            doReturn(result).`when`(solver).solveDetailedAsync(playerJsUrl, "encrypted", "obfuscated")
            val resolver = resolverWithEjs(solver)

            resolver.prewarmChallengesAsync("encrypted", "obfuscated")

            assertEquals("resolved-signature", resolver.resolveSignature("encrypted"))
            assertEquals("resolved-signature", resolver.resolveSignatureAsync("encrypted"))
            assertEquals(
                "https://rr1.googlevideo.com/videoplayback?itag=140&n=resolved-n",
                resolver.resolveStreamingUrl(
                    "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
                )
            )
            assertEquals(
                "https://rr1.googlevideo.com/videoplayback?itag=140&n=resolved-n",
                resolver.resolveStreamingUrlAsync(
                    "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
                )
            )
            verify(solver).solveDetailedAsync(playerJsUrl, "encrypted", "obfuscated")
        }
    }

    @Test
    fun prewarmRejectsInvalidSignatureAndMissingThrottling() = runBlocking {
        val solver = mock(YouTubeEjsChallengeSolver::class.java)
        doReturn(
            YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution(signature = "[object Object]")
            )
        ).`when`(solver).solveDetailedAsync(playerJsUrl, "encrypted", "obfuscated")
        val resolver = resolverWithEjs(solver)

        resolver.prewarmChallengesAsync("encrypted", "obfuscated")

        assertNull(resolver.resolveSignature("encrypted"))
        assertEquals(
            "",
            resolver.resolveStreamingUrl("https://rr1.googlevideo.com/videoplayback?n=obfuscated")
        )
    }

    @Test
    fun prewarmUsesFallbackPlayerScriptUrlWhenProvided() {
        runBlocking {
            val solver = mock(YouTubeEjsChallengeSolver::class.java)
            doReturn(
                YouTubeJsChallengeSolveResult(status = YouTubeJsChallengeSolveStatus.SUCCESS)
            ).`when`(solver).solveDetailedAsync(playerJsUrl, "encrypted", "obfuscated")
            val resolver = DefaultYouTubeStreamingCipherResolver(
                videoId = "video-id",
                playerJsUrl = "",
                fallbackPlayerJsUrl = { playerJsUrl },
                ejsChallengeSolver = solver
            )

            resolver.prewarmChallengesAsync("encrypted", "obfuscated")

            verify(solver).solveDetailedAsync(playerJsUrl, "encrypted", "obfuscated")
        }
    }

    @Test
    fun prewarmSkipsSolverWhenPlayerScriptUrlIsUnavailable() = runBlocking {
        val solver = mock(YouTubeEjsChallengeSolver::class.java)
        val resolver = DefaultYouTubeStreamingCipherResolver(
            videoId = "video-id",
            playerJsUrl = "",
            fallbackPlayerJsUrl = { "" },
            ejsChallengeSolver = solver
        )

        resolver.prewarmChallengesAsync("encrypted", "obfuscated")

        verifyNoInteractions(solver)
    }

    @Test
    fun ejsSignatureWinsWhenNewPipeVersionIsKnownBroken() = runBlocking {
        NewPipeFallbackTracker.recordSignatureFailure(playerJsUrl)
        val solver = mock(YouTubeEjsChallengeSolver::class.java)
        doReturn(
            YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution(signature = "resolved-signature")
            )
        ).`when`(solver).solveDetailedAsync(playerJsUrl, "encrypted", null)

        assertEquals("resolved-signature", resolverWithEjs(solver).resolveSignatureAsync("encrypted"))
    }

    @Test
    fun ejsThrottlingWinsWhenNewPipeVersionIsKnownBroken() = runBlocking {
        NewPipeFallbackTracker.recordThrottlingFailure(playerJsUrl)
        val solver = mock(YouTubeEjsChallengeSolver::class.java)
        doReturn(
            YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution(throttlingParameter = "resolved-n")
            )
        ).`when`(solver).solveDetailedAsync(playerJsUrl, null, "obfuscated")

        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=140&n=resolved-n",
            resolverWithEjs(solver).resolveStreamingUrlAsync(
                "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
            )
        )
    }

    @Test
    fun invalidEjsThrottlingResultCannotBecomeAStreamUrl() = runBlocking {
        NewPipeFallbackTracker.recordThrottlingFailure(playerJsUrl)
        val solver = mock(YouTubeEjsChallengeSolver::class.java)
        doReturn(
            YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution(throttlingParameter = "[object Object]")
            )
        ).`when`(solver).solveDetailedAsync(playerJsUrl, null, "obfuscated")

        assertEquals(
            "",
            resolverWithEjs(solver).resolveStreamingUrlAsync(
                "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
            )
        )
    }

    @Test
    fun newPipeCanResolveSignatureWhenEjsIsUnavailable() = runBlocking {
        val backend = FakeNewPipeCipherBackend(signature = "resolved-signature")

        assertEquals(
            "resolved-signature",
            resolverWithNewPipe(backend).resolveSignatureAsync("encrypted")
        )
        assertEquals(1, backend.signatureCalls)
    }

    @Test
    fun unchangedNewPipeSignatureIsRejectedAndVersionIsSkippedNextTime() = runBlocking {
        val backend = FakeNewPipeCipherBackend(signature = "encrypted")

        assertNull(resolverWithNewPipe(backend).resolveSignatureAsync("encrypted"))

        assertEquals(true, NewPipeFallbackTracker.maybeSkipSignature(playerJsUrl))
    }

    @Test
    fun failedNewPipeSignatureIsRecordedWithoutPromotingAValue() = runBlocking {
        val backend = FakeNewPipeCipherBackend(signatureFailure = IllegalStateException("player script changed"))

        assertNull(resolverWithNewPipe(backend).resolveSignatureAsync("encrypted"))

        assertEquals(true, NewPipeFallbackTracker.maybeSkipSignature(playerJsUrl))
    }

    @Test
    fun newPipeCanResolveThrottlingWhenEjsIsUnavailable() = runBlocking {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
        val backend = FakeNewPipeCipherBackend(streamingUrl = url.replace("obfuscated", "resolved-n"))

        assertEquals(url.replace("obfuscated", "resolved-n"), resolverWithNewPipe(backend).resolveStreamingUrlAsync(url))
        assertEquals(1, backend.streamingUrlCalls)
    }

    @Test
    fun newPipeInvalidThrottlingCannotWinTheRace() = runBlocking {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
        val backend = FakeNewPipeCipherBackend(streamingUrl = url.replace("obfuscated", "%5Bobject%20Object%5D"))

        assertEquals("", resolverWithNewPipe(backend).resolveStreamingUrlAsync(url))
    }

    @Test
    fun unchangedNewPipeThrottlingIsRejectedAndVersionIsSkippedNextTime() = runBlocking {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140&n=obfuscated"
        val backend = FakeNewPipeCipherBackend(streamingUrl = url)

        assertEquals("", resolverWithNewPipe(backend).resolveStreamingUrlAsync(url))

        assertEquals(true, NewPipeFallbackTracker.maybeSkipThrottling(playerJsUrl))
    }

    @Test
    fun resolutionLoggingEmitsFirstFastResultAndEverySlowResult() {
        val logged = AtomicBoolean(false)

        assertEquals(true, shouldLogStreamingCipherResolution(0L, logged))
        assertEquals(false, shouldLogStreamingCipherResolution(0L, logged))
        assertEquals(true, shouldLogStreamingCipherResolution(250L, logged))
        val slowFirst = AtomicBoolean(false)
        assertEquals(true, shouldLogStreamingCipherResolution(250L, slowFirst))
        assertEquals(false, slowFirst.get())
    }

    private fun resolverWithoutEjs() = DefaultYouTubeStreamingCipherResolver(
        videoId = "video-id",
        playerJsUrl = playerJsUrl,
        fallbackPlayerJsUrl = { "" },
        ejsChallengeSolver = null
    )

    private fun resolverWithEjs(solver: YouTubeEjsChallengeSolver) = DefaultYouTubeStreamingCipherResolver(
        videoId = "video-id",
        playerJsUrl = playerJsUrl,
        fallbackPlayerJsUrl = { "" },
        ejsChallengeSolver = solver
    )

    private fun resolverWithNewPipe(backend: YouTubeNewPipeCipherBackend) = DefaultYouTubeStreamingCipherResolver(
        videoId = "video-id",
        playerJsUrl = playerJsUrl,
        fallbackPlayerJsUrl = { "" },
        ejsChallengeSolver = null,
        newPipeBackend = backend
    )

    private class FakeNewPipeCipherBackend(
        private val signature: String? = null,
        private val streamingUrl: String? = null,
        private val signatureFailure: Throwable? = null
    ) : YouTubeNewPipeCipherBackend {
        var signatureCalls = 0
        var streamingUrlCalls = 0

        override suspend fun resolveSignature(videoId: String, encryptedSignature: String): String? {
            signatureCalls++
            signatureFailure?.let { throw it }
            return signature
        }

        override suspend fun resolveStreamingUrl(videoId: String, url: String): String? {
            streamingUrlCalls++
            return streamingUrl
        }
    }
}
