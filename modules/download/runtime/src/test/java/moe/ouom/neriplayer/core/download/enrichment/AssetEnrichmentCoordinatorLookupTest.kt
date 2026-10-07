package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssetEnrichmentCoordinatorLookupTest {
    @Test
    fun `activity lookups trim operation ids and ignore blank or unknown ones`() = runTest {
        val coordinator = AssetEnrichmentCoordinator(backgroundScope, startJob = {})
        coordinator.enqueue(" enrich-1 ") { error("lazy enrichment must not start") }
        try {
            assertTrue(coordinator.isActive("enrich-1"))
            assertTrue(coordinator.isActive("  enrich-1  "))
            assertFalse(coordinator.isActive("   "))
            assertFalse(coordinator.isActive("enrich-2"))
        } finally {
            coordinator.cancelAndSettle("enrich-1")
        }
    }

    @Test
    fun `waiting for any completion reports unknown ids at once and times out on unfinished work`() = runTest {
        val coordinator = AssetEnrichmentCoordinator(backgroundScope, startJob = {})
        coordinator.enqueue("running") { error("lazy enrichment must not start") }
        try {
            assertEquals(emptySet<String>(), coordinator.awaitAnyCompletion(emptyList(), timeoutMs = 1_000L))
            assertEquals(
                setOf("finished"),
                coordinator.awaitAnyCompletion(listOf("running", "finished", "finished"), timeoutMs = 1_000L)
            )
            assertEquals(0L, testScheduler.currentTime)

            assertEquals(emptySet<String>(), coordinator.awaitAnyCompletion(listOf("running"), timeoutMs = 1_000L))
            assertEquals(1_000L, testScheduler.currentTime)
        } finally {
            coordinator.cancelAndSettle("running")
        }
    }

    @Test
    fun `cancel reports only work that was still running`() = runTest {
        val releaseCompletion = CountDownLatch(1)
        val coordinator = AssetEnrichmentCoordinator(backgroundScope, startJob = {})
        coordinator.enqueue("enrich-1", onCompletion = { releaseCompletion.await() }) {
            error("lazy enrichment must not start")
        }
        try {
            assertFalse(coordinator.cancel("   "))
            assertFalse(coordinator.cancel("enrich-2"))
            assertTrue(coordinator.cancel(" enrich-1 "))
            assertFalse(coordinator.cancel("enrich-1"))
            assertTrue(coordinator.isActive("enrich-1"))
        } finally {
            releaseCompletion.countDown()
            coordinator.cancelAndSettle("enrich-1")
        }
        assertFalse(coordinator.isActive("enrich-1"))
    }

    private suspend fun AssetEnrichmentCoordinator.cancelAndSettle(operationId: String) {
        cancelAll()
        withContext(Dispatchers.Default) {
            assertTrue(awaitCompletion(listOf(operationId), timeoutMs = 5_000L))
        }
    }
}
