package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerRetryBootstrapPolicyTest {

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
}
