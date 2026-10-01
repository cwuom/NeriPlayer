package moe.ouom.neriplayer.core.download.resource.permit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPermitLifecycleTest {
    @Test
    fun `cancellation between grant and delivery returns the slot to the next owner`() = runTest {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val first = registry.acquire("first")
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { registry.acquire("cancelled") }
        val next = async(start = CoroutineStart.UNDISPATCHED) { registry.acquire("next") }

        first.release()
        assertEquals(setOf("cancelled"), registry.snapshot().heldPermitOwners)
        assertFalse(cancelled.isCompleted)
        cancelled.cancelAndJoin()

        val permit = next.await()
        assertEquals("next", permit.ownerKey)
        assertEquals(0, registry.snapshot().waitingCount)
        permit.close()
        assertEquals(0, registry.snapshot().permitCount)
    }

    @Test
    fun `cancelled waiter can be replaced with the same owner`() = runTest {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val held = registry.acquire("held")
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { registry.acquire("retry") }
        cancelled.cancelAndJoin()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { registry.acquire("retry") }
        held.release()
        replacement.await().release()
        assertEquals(0, registry.snapshot().permitCount)
    }

    @Test
    fun `stale permit callbacks cannot mutate a replacement with the same owner`() = runTest {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val old = registry.acquire("owner")
        old.release()
        val current = registry.acquire("owner")

        old.markNetworkIoStarted()
        old.markNetworkIoFinished()
        old.markNetworkActivity()
        assertFalse(old.recordProgress(100L))
        assertFalse(registry.markNetworkActivity("owner", old.generation))
        assertFalse(registry.recordProgress("missing", 10L))
        assertEquals(0, registry.snapshot().activeTransferCount)
        assertEquals(0L, registry.snapshot().reportedBytesByOwner["owner"])

        current.release()
        assertFalse(old.recordProgress(1L))
        assertFalse(registry.markNetworkActivity("owner", current.generation))
    }

    @Test
    fun `generation heartbeat needs active IO and progress never moves backwards`() = runTest {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1, nowNs = { nowNs })
        val permit = registry.acquire("owner")
        assertFalse(registry.markNetworkActivity("owner", permit.generation))
        permit.markNetworkIoStarted()
        permit.markNetworkIoStarted()
        assertFalse(registry.recordProgress(" owner ", -1L))
        assertTrue(registry.recordProgress(" owner ", 10L))
        assertFalse(registry.recordProgress("owner", 9L))
        assertFalse(registry.recordProgress("owner", 10L))
        nowNs = 9L
        assertTrue(registry.markNetworkActivity(" owner ", permit.generation))
        assertFalse(registry.isProgressStale("owner", permit.generation, 10L, atNs = 18L))
        assertEquals(10L, registry.snapshot().reportedBytesByOwner["owner"])
        permit.markNetworkIoFinished()
        permit.markNetworkIoFinished()
        assertFalse(registry.markNetworkActivity("owner", permit.generation))
        permit.release()
    }

    @Test
    fun `manual promotion is scoped to the waiting owner or stable operation`() = runTest {
        val registry = DownloadTransferPermitRegistry(maxParallelism = 1)
        val base = registry.acquire("base")
        assertFalse(registry.promoteWaitingOwner(" "))
        assertFalse(registry.promoteWaitingOwner("missing"))
        assertFalse(registry.promoteWaitingOperation(" "))
        assertFalse(registry.promoteWaitingOperation("missing"))
        assertTrue(registry.promoteWaitingOwner(" base "))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.acquire("operation#2@song") }
        assertTrue(registry.promoteWaitingOwner(" operation#2@song "))
        val promoted = waiting.await()
        assertEquals(2, registry.snapshot().permitCount)
        assertTrue(registry.promoteWaitingOperation(" operation "))
        promoted.release()
        base.release()
    }

    @Test
    fun `invalid registry and acquisition arguments fail before taking a slot`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { DownloadTransferPermitRegistry(0) }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTransferPermitRegistry(1, activityGraceNs = -1L)
        }
        val registry = DownloadTransferPermitRegistry(1)
        assertTrue(runCatching { registry.acquire(" ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, registry.snapshot().permitCount)
        assertEquals("anonymous#0", DownloadTransferPermitRegistry.ownerKey(null, null))
        assertEquals("anonymous#0", DownloadTransferPermitRegistry.ownerKey(" ", null, " "))
        assertEquals("operation#2@song", DownloadTransferPermitRegistry.ownerKey(" operation ", 2L, " song "))
    }
}
