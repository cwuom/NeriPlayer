package moe.ouom.neriplayer.core.download

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import moe.ouom.neriplayer.core.download.execution.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.DownloadPumpCompletion
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCoreDownloadRecoveryPolicyTest {
    @Test
    fun `late core commits coalesce into one successor instead of being lost at worker completion`() {
        val coordinator = PostCoreDownloadRecoveryWorker.scheduleCoordinator
        coordinator.invalidate()
        try {
            val generation = requireNotNull(coordinator.request())
            assertTrue(coordinator.markWorkEnqueueStarted(generation))
            assertTrue(coordinator.claimWorker(generation))
            repeat(1_000) { assertEquals(null, coordinator.request()) }
            assertEquals(
                DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
                coordinator.complete(generation, workWillRetry = false)
            )
            val successor = requireNotNull(coordinator.request())
            assertTrue(successor > generation)
            assertTrue(coordinator.markWorkEnqueueStarted(successor))
            assertTrue(coordinator.claimWorker(successor))
            assertEquals(DownloadPumpCompletion.COMPLETED, coordinator.complete(successor, false))
        } finally {
            coordinator.invalidate()
        }
    }

    @Test
    fun `shared worker requires connectivity and uses durable exponential retry`() {
        val request = PostCoreDownloadRecoveryWorker.buildRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertTrue(request.tags.contains("download_execution_all"))
        assertTrue(request.tags.contains("download_post_core_recovery"))
    }

    @Test
    fun `old retries lead each window while one slot remains for fresh finalization`() {
        val candidates = listOf(
            candidate("retry-new", "DEGRADED_COMPLETE", queueOrder = 9, updatedAtMs = 900),
            candidate("fresh-one", "CORE_COMMITTED", queueOrder = 1, updatedAtMs = 100),
            candidate("retry-old", "DEGRADED_COMPLETE", queueOrder = 8, updatedAtMs = 200),
            candidate("retry-middle", "DEGRADED_COMPLETE", queueOrder = 7, updatedAtMs = 500),
            candidate("retry-latest", "DEGRADED_COMPLETE", queueOrder = 6, updatedAtMs = 1_000),
            candidate("fresh-two", "ASSETS_ENRICHING", queueOrder = 2, updatedAtMs = 110)
        )

        assertEquals(
            listOf("retry-old", "retry-middle", "retry-new", "fresh-one"),
            selectPostCoreDownloadRecoveryCandidates(
                candidates = candidates,
                capacity = 4,
                currentNetworkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false
            ).map(PostCoreDownloadRecoveryCandidate::operationId)
        )
    }

    @Test
    fun `selection excludes active and attempted rows and respects wifi policy`() {
        val candidates = listOf(
            candidate("active", "DEGRADED_COMPLETE", requiresWifi = false),
            candidate("attempted", "DEGRADED_COMPLETE", requiresWifi = false),
            candidate("wifi-only", "DEGRADED_COMPLETE", requiresWifi = true),
            candidate("mobile-safe", "CORE_COMMITTED", requiresWifi = false)
        )

        assertEquals(
            listOf("mobile-safe"),
            selectPostCoreDownloadRecoveryCandidates(
                candidates = candidates,
                capacity = 4,
                activeOperationIds = setOf("active"),
                attemptedOperationIds = setOf("attempted"),
                currentNetworkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            ).map(PostCoreDownloadRecoveryCandidate::operationId)
        )
        assertTrue(
            selectPostCoreDownloadRecoveryCandidates(
                candidates = candidates,
                capacity = 4,
                currentNetworkType = null,
                mobileDataOverrideAllowed = true
            ).isEmpty()
        )
        assertTrue(
            isPostCoreRecoveryNetworkEligible(
                requiresWifiNetwork = true,
                currentNetworkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true
            )
        )
        assertFalse(
            isPostCoreRecoveryNetworkEligible(
                requiresWifiNetwork = true,
                currentNetworkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
    }

    @Test
    fun `post core retry deadline prevents hot looping until it is due`() {
        val candidate = candidate(
            operationId = "deferred",
            state = "DEGRADED_COMPLETE",
            requiresWifi = false,
            nextRetryAtMs = 2_000L
        )

        assertTrue(
            selectPostCoreDownloadRecoveryCandidates(
                candidates = listOf(candidate),
                capacity = 1,
                currentNetworkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false,
                nowMs = 1_999L
            ).isEmpty()
        )
        assertEquals(
            listOf("deferred"),
            selectPostCoreDownloadRecoveryCandidates(
                candidates = listOf(candidate),
                capacity = 1,
                currentNetworkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false,
                nowMs = 2_000L
            ).map(PostCoreDownloadRecoveryCandidate::operationId)
        )
    }

    @Test
    fun `historical batch repair normalizes durable post core operation ids`() {
        assertEquals(
            setOf("post-core-a", "post-core-b"),
            normalizedPostCoreRecoveryOperationIds(
                listOf(" post-core-a ", "", "post-core-b", "post-core-a")
            )
        )
    }

    private fun candidate(
        operationId: String,
        state: String,
        queueOrder: Int = 0,
        updatedAtMs: Long = 0L,
        requiresWifi: Boolean = true,
        nextRetryAtMs: Long? = null
    ): PostCoreDownloadRecoveryCandidate {
        return PostCoreDownloadRecoveryCandidate(
            operationId = operationId,
            state = state,
            queueOrder = queueOrder,
            updatedAtMs = updatedAtMs,
            requiresWifiNetwork = requiresWifi,
            nextRetryAtMs = nextRetryAtMs
        )
    }
}
