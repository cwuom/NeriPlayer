package moe.ouom.neriplayer.core.download.admission

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionLifecycleTest {
    @Test
    fun `detached clear releases waiters without letting its late finish release a new clear`() = runTest {
        val gate = DownloadAdmissionGate()
        gate.awaitOpen()
        val old = gate.beginClear()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitOpen() }
        assertFalse(waiting.isCompleted)
        assertTrue(gate.detachClearForDurableRecovery(old))
        waiting.await()

        val current = gate.beginClear()
        assertFalse(gate.detachClearForDurableRecovery(old))
        gate.runClear(old) {}
        assertNull(gate.openTicketOrNull())
        gate.runClear(current) {}
        assertEquals(current.generation, gate.openTicketOrNull())
    }

    @Test
    fun `follower tokens cannot run or detach the owner's clear`() = runTest {
        val gate = DownloadAdmissionGate()
        val owner = gate.beginClear()
        val follower = gate.beginClear()
        assertTrue(runCatching { gate.runClear(follower) {} }
            .exceptionOrNull() is IllegalArgumentException)
        assertThrows(IllegalArgumentException::class.java) {
            gate.detachClearForDurableRecovery(follower)
        }
        assertNull(gate.openTicketOrNull())
        gate.runClear(owner) {}
    }

    @Test
    fun `old persisted progress cannot mark or restore the current generation`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val old = gate.beginClear()
        gate.runClear(old) {}
        val current = gate.beginClear()
        visibility.begin(current)

        visibility.markFencePersisted(old)
        visibility.restore(old, progress(ClearPhase.PURGING, steps = 4))
        assertFalse(visibility.isTaskPresentationCleared.value)
        assertEquals(ClearPhase.PREPARING, visibility.progress.value?.phase)

        visibility.restore(current, progress(ClearPhase.PREPARING, steps = 0))
        assertFalse(visibility.isTaskPresentationCleared.value)
        gate.runClear(current) {}
        visibility.finish(current)
    }

    @Test
    fun `purging reaches one hundred only after steps and residual items finish`() {
        assertEquals(95, progress(ClearPhase.PURGING, steps = 3).displayPercentage)
        assertEquals(95, progress(ClearPhase.PURGING, steps = 4, completed = 1, total = 2).displayPercentage)
        val completed = progress(ClearPhase.PURGING, steps = 4, completed = 2, total = 2)
        assertEquals(100, completed.displayPercentage)
        assertEquals(1f, completed.displayFraction)
        assertEquals(100, progress(ClearPhase.PURGING, steps = 4).displayPercentage)
        assertEquals(5, progress(ClearPhase.CANCELLING, steps = 1).displayPercentage)
        assertEquals(10, progress(ClearPhase.CLEANING, steps = 2).displayPercentage)
    }

    @Test
    fun `invalid progress counters stay bounded before a clear is restored`() {
        val empty = progress(ClearPhase.PREPARING, steps = -1).copy(totalSteps = 0)
        assertEquals(0, empty.percentage)
        assertEquals(0f, empty.fraction)
        assertEquals(0f, empty.itemFraction)
        val excess = progress(ClearPhase.CLEANING, steps = 99, completed = 99, total = 1)
        assertEquals(100, excess.percentage)
        assertEquals(1f, excess.itemFraction)
        assertEquals(90, excess.displayPercentage)
    }

    private fun progress(phase: ClearPhase, steps: Int, completed: Int = 0, total: Int = 0) =
        ClearProgress(
            phase = phase,
            completedSteps = steps,
            totalSteps = 4,
            affectedItemCount = total,
            completedItemCount = completed,
            totalItemCount = total
        )
}
