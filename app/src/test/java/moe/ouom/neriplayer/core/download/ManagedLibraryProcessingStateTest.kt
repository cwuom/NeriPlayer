package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(16, ownerRetry?.processed)
        assertEquals(2_000, ownerRetry?.total)
    }

    @Test
    fun `process death restores an interrupted mutation as retryable`() {
        val restored = restoreManagedLibraryProcessingState(
            operationId = "interrupted",
            reasonName = ManagedLibraryProcessingReason.DIRECTORY_CHANGE.name,
            phaseName = ManagedLibraryProcessingPhase.REBUILDING_INDEX.name,
            processed = 128,
            total = 2_000,
            currentItem = "track-128.mp3"
        )

        assertTrue(restored is ManagedLibraryProcessingState.WaitingForRetry)
        assertEquals("interrupted", restored.operationId)
        assertEquals(ManagedLibraryProcessingReason.DIRECTORY_CHANGE, restored.reason)
        assertEquals(ManagedLibraryProcessingPhase.REBUILDING_INDEX, restored.phase)
        assertEquals(128, restored.processed)
        assertEquals(2_000, restored.total)
        assertEquals("track-128.mp3", restored.currentItem)
    }

    @Test
    fun `resume keeps durable progress instead of returning to zero`() {
        val waiting = ManagedLibraryProcessingState.WaitingForRetry(
            operationId = "migration",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
            processed = 640,
            total = 2_000,
            currentItem = "track-640.mp3"
        )

        val resumed = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = waiting,
            operationId = "new-work-id",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
            resumeWaitingOperation = true
        )

        assertEquals("migration", resumed?.operationId)
        assertEquals(640, resumed?.processed)
        assertEquals(2_000, resumed?.total)
        assertEquals("track-640.mp3", resumed?.currentItem)
    }

    @Test
    fun `progress updates are bounded and monotonic for one operation`() {
        val running = ManagedLibraryProcessingStateMachine.begin(
            operationId = "migration",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
            processed = 4,
            total = 10
        )

        val regressed = ManagedLibraryProcessingStateMachine.updateProgress(
            current = running,
            operationId = "migration",
            processed = -1,
            total = 10,
            currentItem = "  next.mp3  "
        )
        val completed = ManagedLibraryProcessingStateMachine.updateProgress(
            current = regressed,
            operationId = "migration",
            processed = 99,
            total = 10
        )

        assertEquals(4, regressed.processed)
        assertEquals(10, regressed.total)
        assertEquals("next.mp3", regressed.currentItem)
        assertEquals(10, completed.processed)
        assertEquals(10, completed.total)
    }

    @Test
    fun `resumed batch progress keeps the original total`() {
        val running = ManagedLibraryProcessingStateMachine.begin(
            operationId = "upgrade",
            reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
            phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE,
            processed = 640,
            total = 2_000
        )

        val resumed = ManagedLibraryProcessingStateMachine.updateProgress(
            current = running,
            operationId = "upgrade",
            processed = 16,
            total = 1_360,
            currentItem = "track-656.mp3"
        )

        assertEquals(656, resumed.processed)
        assertEquals(2_000, resumed.total)
        assertEquals("track-656.mp3", resumed.currentItem)
    }

    @Test
    fun `unknown persisted progress remains unknown`() {
        val restored = restoreManagedLibraryProcessingState(
            operationId = "interrupted",
            reasonName = ManagedLibraryProcessingReason.DIRECTORY_CHANGE.name,
            phaseName = ManagedLibraryProcessingPhase.REBUILDING_INDEX.name
        )

        assertNull(restored.processed)
        assertNull(restored.total)
        assertNull(restored.currentItem)
    }
}
