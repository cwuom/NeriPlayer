package moe.ouom.neriplayer.core.download.resource

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTransferPermitRegistryTest {

    @Test
    fun `eight configured slots report active and progressing transfers`() = runBlocking {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 8,
            nowNs = { nowNs }
        )

        val permits = coroutineScope {
            (0 until 8).map { index ->
                async { registry.acquire("operation-$index", configuredParallelism = 8) }
            }.map { it.await() }
        }
        permits.forEach { permit ->
            permit.markNetworkIoStarted()
            assertTrue(permit.recordProgress(1024L))
        }

        val snapshot = registry.snapshot(nowNs)
        assertEquals(8, snapshot.permitCount)
        assertEquals(8, snapshot.activeTransferCount)
        assertEquals(8, snapshot.progressingTransferCount)
        assertEquals(0, snapshot.waitingCount)

        permits.forEach { it.release() }
        assertEquals(0, registry.snapshot(nowNs).permitCount)
    }

    @Test
    fun `manual retry adds only one overflow transfer`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val base = registry.acquire("base", configuredParallelism = 1)
        val firstManual = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("manual-one", allowSingleOverflow = true)
        }
        val firstManualPermit = withTimeout(1_000L) { firstManual.await() }
        val secondManual = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("manual-two", allowSingleOverflow = true)
        }
        yield()

        assertEquals(2, registry.snapshot().permitCount)
        assertFalse(secondManual.isCompleted)
        assertEquals(listOf("manual-two"), registry.snapshot().waitingOwners)

        base.release()
        val secondManualPermit = withTimeout(1_000L) { secondManual.await() }
        assertEquals(2, registry.snapshot().permitCount)
        firstManualPermit.release()
        secondManualPermit.release()
        assertEquals(0, registry.snapshot().permitCount)
    }

    @Test
    fun `manual retry promotes an existing waiter into the overflow slot`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val base = registry.acquire("base")
        val normal = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("normal")
        }
        val manual = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("operation-id#7@song")
        }
        yield()

        assertTrue(registry.promoteWaitingOperation("operation-id"))
        val manualPermit = withTimeout(1_000L) { manual.await() }
        assertFalse(normal.isCompleted)
        assertEquals(2, registry.snapshot().permitCount)

        base.release()
        val normalPermit = withTimeout(1_000L) { normal.await() }
        manualPermit.release()
        normalPermit.release()
    }

    @Test
    fun `cancelled waiter is removed before the next owner is admitted`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val first = registry.acquire("first", configuredParallelism = 1)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("second", configuredParallelism = 1)
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("third", configuredParallelism = 1)
        }
        yield()
        assertEquals(listOf("second", "third"), registry.snapshot().waitingOwners)

        second.cancelAndJoin()
        first.release()
        val thirdPermit = withTimeout(1_000L) { third.await() }
        assertEquals("third", thirdPermit.ownerKey)
        thirdPermit.release()
        assertEquals(0, registry.snapshot().waitingCount)
    }

    @Test
    fun `cancelled owner can be requeued immediately without duplicate-owner failure`() =
        runBlocking {
            val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
            val first = registry.acquire("first")
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                registry.acquire("same-owner")
            }
            yield()

            cancelled.cancelAndJoin()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                registry.acquire("same-owner")
            }
            first.release()

            val replacementPermit = withTimeout(1_000L) { replacement.await() }
            assertEquals("same-owner", replacementPermit.ownerKey)
            replacementPermit.release()
            assertEquals(0, registry.snapshot().waitingCount)
        }

    @Test
    fun `repeated cancellation and requeue leaves no stale waiter`() = runBlocking {
        repeat(100) { cycle ->
            val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
            val first = registry.acquire("first-$cycle")
            val waiters = (0 until 8).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.acquire("waiter-$cycle-$index")
                }
            }
            yield()
            waiters.filterIndexed { index, _ -> index % 2 == 0 }
                .forEach { it.cancelAndJoin() }

            first.close()
            waiters.filterIndexed { index, _ -> index % 2 == 1 }
                .forEach { waiter ->
                    val permit = withTimeout(1_000L) { waiter.await() }
                    permit.close()
                }
            assertEquals(0, registry.snapshot().permitCount)
            assertEquals(0, registry.snapshot().waitingCount)
        }
    }

    @Test
    fun `raising the configured limit wakes a queued transfer immediately`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 4)
        val first = registry.acquire("first", configuredParallelism = 1)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("second", configuredParallelism = 1)
        }
        yield()
        assertEquals(1, registry.snapshot().waitingCount)

        val updated = registry.updateConfiguredParallelism(2, reason = "user_setting")
        assertEquals(2, updated.effectiveParallelism)
        val secondPermit = withTimeout(1_000L) { second.await() }
        assertEquals("second", secondPermit.ownerKey)

        first.release()
        secondPermit.release()
    }

    @Test
    fun `lowering the limit never revokes held permits`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 4)
        val first = registry.acquire("first", configuredParallelism = 4)
        val second = registry.acquire("second", configuredParallelism = 4)
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("third", configuredParallelism = 2)
        }
        yield()

        val lowered = registry.snapshot()
        assertEquals(2, lowered.effectiveParallelism)
        assertEquals(setOf("first", "second"), lowered.heldPermitOwners)
        assertEquals(listOf("third"), lowered.waitingOwners)

        first.release()
        val thirdPermit = withTimeout(1_000L) { third.await() }
        assertEquals(setOf("second", "third"), registry.snapshot().heldPermitOwners)

        second.release()
        thirdPermit.release()
    }

    @Test
    fun `snapshot callback failure does not break permit lifecycle`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            onSnapshotChanged = { error("diagnostic sink unavailable") }
        )
        val permit = registry.acquire("owner")
        permit.markNetworkIoStarted()
        assertTrue(permit.recordProgress(1L))
        permit.close()
        assertEquals(0, registry.snapshot().permitCount)
    }

    @Test
    fun `duplicate owner cannot hold or queue a second permit`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val first = registry.acquire("same", configuredParallelism = 1)
        var failed = false
        try {
            registry.acquire("same", configuredParallelism = 1)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        first.release()
    }

    @Test
    fun `progress leaves active transfer but expires from progressing sample`() = runBlocking {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            activityGraceNs = 10L,
            nowNs = { nowNs }
        )
        val permit = registry.acquire("operation", configuredParallelism = 1)
        permit.markNetworkIoStarted()
        permit.recordProgress(10L)

        nowNs = 11L
        val snapshot = registry.snapshot(nowNs)
        assertEquals(1, snapshot.activeTransferCount)
        assertEquals(0, snapshot.progressingTransferCount)
        assertEquals(10L, snapshot.reportedBytesByOwner["operation"])

        permit.markNetworkIoFinished()
        permit.release()
    }

    @Test
    fun `network finish keeps the permit held through core commit`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val first = registry.acquire("core-holder")
        first.markNetworkIoStarted()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("core-waiter")
        }
        yield()

        first.markNetworkIoFinished()
        val afterNetwork = registry.snapshot()
        assertEquals(1, afterNetwork.permitCount)
        assertEquals(0, afterNetwork.activeTransferCount)
        assertEquals(listOf("core-waiter"), afterNetwork.waitingOwners)
        assertFalse(waiting.isCompleted)

        first.release()
        val second = withTimeout(1_000L) { waiting.await() }
        assertEquals("core-waiter", second.ownerKey)
        second.release()
        assertEquals(0, registry.snapshot().permitCount)
    }

    @Test
    fun `invalid configured limit is clamped with an explicit reason`() {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 4)
        val snapshot = registry.updateConfiguredParallelism(99, reason = "thermal")

        assertEquals(99, snapshot.requestedParallelism)
        assertEquals(4, snapshot.effectiveParallelism)
        assertEquals("thermal", snapshot.limitReason)
    }

    @Test
    fun `older parallelism revision cannot overwrite a newer setting`() {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 8)
        registry.updateConfiguredParallelism(
            requestedParallelism = 8,
            reason = "user_setting",
            configurationRevision = 2L
        )

        val snapshot = registry.updateConfiguredParallelism(
            requestedParallelism = 1,
            reason = "user_setting",
            configurationRevision = 1L
        )

        assertEquals(8, snapshot.effectiveParallelism)
        assertEquals(8, snapshot.requestedParallelism)
    }

    @Test
    fun `owner key keeps same operation attempts distinct by song`() {
        assertEquals(
            "operation#3@song-a",
            DownloadTransferPermitRegistry.ownerKey("operation", 3L, "song-a")
        )
        assertEquals(
            "operation#3",
            DownloadTransferPermitRegistry.ownerKey("operation", 3L)
        )
    }

    @Test
    fun `stale generation cannot publish progress after owner is reacquired`() = runBlocking {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val first = registry.acquire("same-owner")
        val firstGeneration = first.generation
        first.release()

        val second = registry.acquire("same-owner")
        assertTrue(second.generation != firstGeneration)
        assertFalse(
            registry.recordProgress(
                ownerKey = "same-owner",
                generation = firstGeneration,
                absoluteBytes = 99L
            )
        )
        assertTrue(
            registry.recordProgress(
                ownerKey = "same-owner",
                generation = second.generation,
                absoluteBytes = 1L
            )
        )
        second.release()
    }

    @Test
    fun `waiting cancellation publishes the smaller queue snapshot`() = runBlocking {
        val snapshots = mutableListOf<DownloadTransferPermitRegistry.Snapshot>()
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            onSnapshotChanged = snapshots::add
        )
        val first = registry.acquire("first")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquire("waiting")
        }
        yield()

        waiting.cancelAndJoin()

        assertEquals(0, snapshots.last().waitingCount)
        first.release()
    }

    @Test
    fun `stale check requires current active generation`() = runBlocking {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            nowNs = { nowNs }
        )
        val first = registry.acquire("operation")
        first.markNetworkIoStarted()
        nowNs = 11L

        assertTrue(
            registry.isProgressStale(
                ownerKey = "operation",
                generation = first.generation,
                staleAfterNs = 10L,
                atNs = nowNs
            )
        )
        first.release()

        val second = registry.acquire("operation")
        second.markNetworkIoStarted()
        assertFalse(
            registry.isProgressStale(
                ownerKey = "operation",
                generation = first.generation,
                staleAfterNs = 10L,
                atNs = nowNs
            )
        )
        second.release()
    }

    @Test
    fun `network activity heartbeat prevents false stall without increasing bytes`() =
        runBlocking {
            var nowNs = 0L
            val registry = DownloadTransferPermitRegistry(
                maxParallelism = 1,
                nowNs = { nowNs }
            )
            val permit = registry.acquire("slow-reader")
            permit.markNetworkIoStarted()
            assertTrue(permit.recordProgress(128L))

            nowNs = 9L
            permit.markNetworkActivity()
            nowNs = 18L
            assertFalse(
                registry.isProgressStale(
                    ownerKey = "slow-reader",
                    generation = permit.generation,
                    staleAfterNs = 10L,
                    atNs = nowNs
                )
            )
            assertEquals(128L, registry.snapshot(nowNs).reportedBytesByOwner["slow-reader"])

            nowNs = 20L
            assertTrue(
                registry.isProgressStale(
                    ownerKey = "slow-reader",
                    generation = permit.generation,
                    staleAfterNs = 10L,
                    atNs = nowNs
                )
            )
            permit.close()
        }
}
