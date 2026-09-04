package moe.ouom.neriplayer.core.download.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedDownloadDirectoryMutationFenceTest {
    @Test
    fun `directory change running and waiting states fence download commits`() {
        val running = ManagedLibraryProcessingState.Running(
            operationId = "migration-running",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val waiting = ManagedLibraryProcessingState.WaitingForRetry(
            operationId = "migration-waiting",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE
        )

        assertTrue(shouldFenceDownloadForDirectoryMutation(running, false))
        assertTrue(shouldFenceDownloadForDirectoryMutation(waiting, false))
        assertFalse(
            shouldFenceDownloadForDirectoryMutation(
                ManagedLibraryProcessingState.Idle,
                false
            )
        )
        assertFalse(
            shouldFenceDownloadForDirectoryMutation(
                ManagedLibraryProcessingState.Running(
                    operationId = "legacy-upgrade",
                    reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
                    phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
                ),
                false
            )
        )
    }

    @Test
    fun `closed in-memory gate fences commits before persistent state is visible`() {
        assertTrue(
            shouldFenceDownloadForDirectoryMutation(
                ManagedLibraryProcessingState.Idle,
                true
            )
        )
    }

    @Test
    fun `migration waits for old commit and rejects new commits until release`() = runTest {
        val gate = ManagedDownloadDirectoryMutationGate()
        val oldCommit = requireNotNull(gate.tryAcquireDownloadLease())

        val mutation = async { gate.closeAndDrain() }
        runCurrent()

        assertTrue(gate.isClosed())
        assertNull(gate.tryAcquireDownloadLease())
        assertFalse(mutation.isCompleted)

        oldCommit.close()
        val mutationLease = mutation.await()

        assertTrue(gate.isClosed())
        assertNull(gate.tryAcquireDownloadLease())

        val waitingForOpen = async { gate.awaitOpen() }
        runCurrent()
        assertFalse(waitingForOpen.isCompleted)

        mutationLease.close()
        waitingForOpen.await()

        assertFalse(gate.isClosed())
        val newCommit = gate.tryAcquireDownloadLease()
        assertNotNull(newCommit)
        newCommit?.close()
    }

    @Test
    fun `migration waits for startup recovery lease before draining`() = runTest {
        val gate = ManagedDownloadDirectoryMutationGate()
        val recovery = requireNotNull(gate.tryAcquireDownloadLease())

        val mutation = async { gate.closeAndDrain() }
        runCurrent()

        assertTrue(gate.isClosed())
        assertFalse(mutation.isCompleted)

        recovery.close()
        val mutationLease = mutation.await()
        mutationLease.close()

        assertFalse(gate.isClosed())
    }

    @Test
    fun `cancelled migration drain reopens admission without leaking the mutex`() = runTest {
        val gate = ManagedDownloadDirectoryMutationGate()
        val oldCommit = requireNotNull(gate.tryAcquireDownloadLease())
        val mutation = async { gate.closeAndDrain() }
        runCurrent()

        mutation.cancelAndJoin()

        assertFalse(gate.isClosed())
        gate.tryAcquireDownloadLease()?.close()
        oldCommit.close()

        val nextMutation = gate.closeAndDrain()
        nextMutation.close()
        assertFalse(gate.isClosed())
    }
}
