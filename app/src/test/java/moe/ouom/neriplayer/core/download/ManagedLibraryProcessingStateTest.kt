package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryProcessingStateTest {
    @Test
    fun `older operation cannot clear a newer directory rebuild`() {
        val first = ManagedLibraryProcessingStateMachine.begin(
            operationId = "first",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val second = ManagedLibraryProcessingStateMachine.begin(
            operationId = "second",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

        val afterStaleCompletion = ManagedLibraryProcessingStateMachine.complete(
            current = second,
            operationId = first.operationId
        )

        assertEquals(second, afterStaleCompletion)
        assertEquals(
            ManagedLibraryProcessingState.Idle,
            ManagedLibraryProcessingStateMachine.complete(second, second.operationId)
        )
    }

    @Test
    fun `provider failure remains visible and retryable`() {
        val running = ManagedLibraryProcessingStateMachine.begin(
            operationId = "reindex",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

        val waiting = ManagedLibraryProcessingStateMachine.waitingForRetry(
            current = running,
            operationId = running.operationId
        )

        assertTrue(waiting is ManagedLibraryProcessingState.WaitingForRetry)
        assertEquals(running.operationId, waiting.operationId)
        assertEquals(running.reason, waiting.reason)
    }

    @Test
    fun `legacy upgrade progress ignores stale operation updates`() {
        val running = ManagedLibraryProcessingStateMachine.begin(
            operationId = "upgrade",
            reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
            phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE
        )

        val stale = ManagedLibraryProcessingStateMachine.updateProgress(
            current = running,
            operationId = "stale",
            processed = 64,
            total = 128
        )
        val updated = ManagedLibraryProcessingStateMachine.updateProgress(
            current = running,
            operationId = running.operationId,
            processed = 64,
            total = 128
        )

        assertEquals(running, stale)
        assertEquals(64, updated.processed)
        assertEquals(128, updated.total)
    }

    @Test
    fun `running operation excludes another library mutation`() {
        val running = ManagedLibraryProcessingStateMachine.begin(
            operationId = "upgrade",
            reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
            phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE
        )

        val blocked = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = running,
            operationId = "directory",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val progressed = ManagedLibraryProcessingStateMachine.updateProgress(
            current = running,
            operationId = running.operationId,
            processed = 16,
            total = 2_000
        )
        val retry = ManagedLibraryProcessingStateMachine.waitingForRetry(
            current = progressed,
            operationId = running.operationId
        )
        val resumed = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = retry,
            operationId = "directory",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val ownerRetry = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = retry,
            operationId = "replacement",
            reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
            phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE,
            resumeWaitingOperation = true
        )

        assertEquals(null, blocked)
        assertEquals(null, resumed)
        assertEquals("upgrade", ownerRetry?.operationId)
        assertEquals(null, ownerRetry?.processed)
        assertEquals(null, ownerRetry?.total)
    }

    @Test
    fun `process death restores an interrupted mutation as retryable`() {
        val restored = restoreManagedLibraryProcessingState(
            operationId = "interrupted",
            reasonName = ManagedLibraryProcessingReason.DIRECTORY_CHANGE.name,
            phaseName = ManagedLibraryProcessingPhase.REBUILDING_INDEX.name
        )

        assertTrue(restored is ManagedLibraryProcessingState.WaitingForRetry)
        assertEquals("interrupted", restored.operationId)
        assertEquals(ManagedLibraryProcessingReason.DIRECTORY_CHANGE, restored.reason)
    }
}
