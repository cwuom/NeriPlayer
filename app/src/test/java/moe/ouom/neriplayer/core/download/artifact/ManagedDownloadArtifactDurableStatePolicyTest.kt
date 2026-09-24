package moe.ouom.neriplayer.core.download.artifact

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadArtifactDurableStatePolicyTest {
    @Test
    fun `durable artifact states reject retry and cancellation downgrades`() {
        val durableStates = listOf(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.FINALIZED
        )
        val downgradeRequests = listOf(
            ManagedDownloadArtifactState.FAILED_RETRYABLE,
            ManagedDownloadArtifactState.CANCELLED
        )

        durableStates.forEach { current ->
            downgradeRequests.forEach { requested ->
                assertEquals(
                    "$current must not be replaced by $requested",
                    current,
                    resolveArtifactStateUpdate(current, requested)
                )
            }
        }
    }
}
