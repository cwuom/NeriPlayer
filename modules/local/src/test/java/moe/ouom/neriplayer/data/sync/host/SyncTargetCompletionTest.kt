package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncTargetCompletionTest {
    @Test
    fun `a synchronization only completes when its exact target uses the current protocol`() = runTest {
        val loaded = mutableListOf<String>()
        val result = verifyTargetSyncCompletion(Result.success(completed), target) { value ->
            loaded += value
            SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION
        }

        assertSame(completed, result.getOrThrow())
        assertEquals(listOf(target), loaded)
    }

    @Test
    fun `a conflict success with traditional data does not report a finished upgrade`() = runTest {
        val conflict = SyncResult(true, "retry for local changes")
        val result = verifyTargetSyncCompletion(Result.success(conflict), target) {
            SyncProtocolUpgradeRepository.LEGACY_PROTOCOL_VERSION
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Sync database upgrade did not finish", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an unsupported future protocol cannot report this upgrade as complete`() = runTest {
        val result = verifyTargetSyncCompletion(Result.success(completed), target) {
            SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION + 1
        }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `failed results keep their error and never inspect protocol state`() = runTest {
        val offline = IOException("offline")
        val unsuccessful = SyncResult(false, "retry conflict")
        for (initial in listOf(Result.failure(offline), Result.success(unsuccessful))) {
            val actual = verifyTargetSyncCompletion(initial, target) { error("unexpected protocol read") }

            assertEquals(initial, actual)
        }
    }

    @Test
    fun `a protocol storage failure is returned instead of claiming completion`() = runTest {
        val unavailable = IOException("storage unavailable")
        val actual = verifyTargetSyncCompletion(Result.success(completed), target) { throw unavailable }

        assertSame(unavailable, actual.exceptionOrNull())
    }

    @Test
    fun `cancellation during protocol verification propagates unchanged`() = runTest {
        val cancelled = CancellationException("cancelled")
        try {
            verifyTargetSyncCompletion(Result.success(completed), target) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test
    fun `cancellation returned from synchronization is rethrown without inspecting protocol`() = runTest {
        val cancelled = CancellationException("cancelled")
        try {
            verifyTargetSyncCompletion(Result.failure(cancelled), target) { error("unexpected protocol read") }
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    private companion object {
        const val target = "sync-target"
        val completed = SyncResult(true, "completed", songsAdded = 2)
    }
}
