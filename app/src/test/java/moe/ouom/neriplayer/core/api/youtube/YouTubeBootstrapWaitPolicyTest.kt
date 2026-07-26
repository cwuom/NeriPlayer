package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBootstrapWaitPolicyTest {

    private val nowMs = 1_700_000_000_000L

    private fun bootstrap(
        fetchedAtMs: Long = nowMs,
        apiKey: String = "api-key",
        playerJsUrl: String = "https://player/base.js"
    ) = YouTubePlaybackBootstrap(
        apiKey = apiKey,
        webRemixClientVersion = "1.0",
        visitorData = "visitor",
        playerJsUrl = playerJsUrl,
        cookieHeader = "",
        authFingerprint = "fingerprint",
        sessionIndex = "0",
        userAgent = "ua",
        remoteHost = "",
        signatureTimestamp = 20655,
        appInstallData = "",
        coldConfigData = "",
        coldHashData = "",
        hotHashData = "",
        deviceExperimentId = "",
        rolloutToken = "",
        dataSyncId = "",
        delegatedSessionId = "",
        userSessionId = "",
        loggedIn = true,
        fetchedAtMs = fetchedAtMs
    )

    @Test
    fun acceptsASnapshotThatIsMerelyStale() {
        // 十分钟前的存档早过了新鲜期, 但拿来垫一次播放完全够用
        assertTrue(isUsableStaleBootstrap(bootstrap(fetchedAtMs = nowMs - 600_000L), nowMs))
    }

    @Test
    fun rejectsASnapshotPastItsMaxAge() {
        assertTrue(
            isUsableStaleBootstrap(
                bootstrap(fetchedAtMs = nowMs - BOOTSTRAP_SNAPSHOT_MAX_AGE_MS + 1L),
                nowMs
            )
        )
        assertFalse(
            isUsableStaleBootstrap(
                bootstrap(fetchedAtMs = nowMs - BOOTSTRAP_SNAPSHOT_MAX_AGE_MS),
                nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotStampedInTheFuture() {
        assertFalse(isUsableStaleBootstrap(bootstrap(fetchedAtMs = nowMs + 1_000L), nowMs))
    }

    @Test
    fun rejectsASnapshotMissingWhatAPlayerRequestNeeds() {
        assertFalse(isUsableStaleBootstrap(bootstrap(apiKey = ""), nowMs))
        assertFalse(isUsableStaleBootstrap(bootstrap(playerJsUrl = ""), nowMs))
    }

    @Test
    fun neverWaitsWhenAUsableStaleSnapshotIsInHand() {
        // 等待那十几秒会一比一变成首播延迟, 而旧的这份现在就能用
        assertFalse(
            shouldAwaitBootstrapLoad(forceRefresh = false, hasUsableStaleBootstrap = true)
        )
    }

    @Test
    fun waitsWhenThereIsNothingToFallBackOn() {
        assertTrue(
            shouldAwaitBootstrapLoad(forceRefresh = false, hasUsableStaleBootstrap = false)
        )
    }

    @Test
    fun waitsWhenTheCallerExplicitlyAskedForAFreshBootstrap() {
        assertTrue(
            shouldAwaitBootstrapLoad(forceRefresh = true, hasUsableStaleBootstrap = true)
        )
    }
}
