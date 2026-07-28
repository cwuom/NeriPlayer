package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerRetryBootstrapPolicyTest {

    @Test
    fun requestUsesFreshBootstrapTimestampBeforeBackgroundCache() {
        assertEquals(
            20529,
            resolveYouTubeSignatureTimestamp(
                bootstrapTimestamp = null,
                cachedTimestamp = 20529
            )
        )
        assertEquals(
            20530,
            resolveYouTubeSignatureTimestamp(
                bootstrapTimestamp = 20530,
                cachedTimestamp = 20529
            )
        )
    }

    @Test
    fun keepsTheBootstrapWhenEveryClientAnsweredOk() {
        // 播放器都回了 OK, 只是签名解不出来, 重拉 bootstrap 拿到的会是一模一样的东西
        assertFalse(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = false,
                sawUndecipherableOkResponse = true,
                sawBootstrapSuspectOutcome = false
            )
        )
    }

    @Test
    fun refreshesWhenAClientReportedSomethingOtherThanOk() {
        assertTrue(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = false,
                sawUndecipherableOkResponse = true,
                sawBootstrapSuspectOutcome = true
            )
        )
    }

    @Test
    fun keepsBootstrapWhenAnyClientAlreadyAnsweredOk() {
        assertFalse(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = false,
                sawUndecipherableOkResponse = false,
                sawBootstrapSuspectOutcome = true,
                sawOkResponse = true
            )
        )
    }

    @Test
    fun refreshesWhenTheFallbackPathAlreadyAskedForIt() {
        assertTrue(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = true,
                sawUndecipherableOkResponse = true,
                sawBootstrapSuspectOutcome = false
            )
        )
    }

    @Test
    fun doesNotRefreshAfterOkClientAndTerminalFallback() {
        assertFalse(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = true,
                sawUndecipherableOkResponse = true,
                sawBootstrapSuspectOutcome = false,
                sawOkResponse = true
            )
        )
    }

    @Test
    fun refreshesWhenNoClientGotFarEnoughToSayAnything() {
        // 一次 OK 都没见到时还没有任何结论, 保持原来的重拉行为
        assertTrue(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = false,
                sawUndecipherableOkResponse = false,
                sawBootstrapSuspectOutcome = false
            )
        )
    }

    @Test
    fun refreshesWhenRequestsFailedOutright() {
        assertTrue(
            shouldRefreshBootstrapBeforePlayerRetry(
                refreshRequestedByFallback = false,
                sawUndecipherableOkResponse = false,
                sawBootstrapSuspectOutcome = true
            )
        )
    }

    @Test
    fun ignoresTvUnplayableAfterAnOkClientResponse() {
        assertFalse(
            shouldMarkBootstrapSuspectOutcome(
                playabilityStatus = "UNPLAYABLE",
                sawUndecipherableOkResponse = true
            )
        )
        assertTrue(
            shouldMarkBootstrapSuspectOutcome(
                playabilityStatus = "UNPLAYABLE",
                sawUndecipherableOkResponse = false
            )
        )
        assertTrue(
            shouldMarkBootstrapSuspectOutcome(
                playabilityStatus = "LOGIN_REQUIRED",
                sawUndecipherableOkResponse = true
            )
        )
    }

    @Test
    fun stopsRetryingWhenTvFallbackIsTerminalAfterAnOkClient() {
        assertTrue(
            shouldAbortPlayerRetryAfterTerminalFallback(
                sawUndecipherableOkResponse = true,
                sawTerminalFallbackOutcome = true,
                sawPlayerRequestFailure = false
            )
        )
        assertFalse(
            shouldAbortPlayerRetryAfterTerminalFallback(
                sawUndecipherableOkResponse = false,
                sawTerminalFallbackOutcome = true,
                sawPlayerRequestFailure = false
            )
        )
        assertFalse(
            shouldAbortPlayerRetryAfterTerminalFallback(
                sawUndecipherableOkResponse = true,
                sawTerminalFallbackOutcome = true,
                sawPlayerRequestFailure = true
            )
        )
    }

    @Test
    fun stopsAllTvFallbackVariantsAfterUnplayableResponseFollowingOkClient() {
        assertTrue(
            shouldStopRemainingPlayerFallbackRequests(
                clientName = "TVHTML5",
                playabilityStatus = "UNPLAYABLE",
                sawUndecipherableOkResponse = true
            )
        )
        assertTrue(
            shouldStopRemainingPlayerFallbackRequests(
                clientName = "TVHTML5",
                playabilityStatus = "LOGIN_REQUIRED",
                sawUndecipherableOkResponse = true
            )
        )
        assertFalse(
            shouldStopRemainingPlayerFallbackRequests(
                clientName = "TVHTML5",
                playabilityStatus = "UNPLAYABLE",
                sawUndecipherableOkResponse = false
            )
        )
        assertFalse(
            shouldStopRemainingPlayerFallbackRequests(
                clientName = "WEB_REMIX",
                playabilityStatus = "UNPLAYABLE",
                sawUndecipherableOkResponse = true
            )
        )
    }
}
