package moe.ouom.neriplayer.core.download.processing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase.REBUILDING_INDEX
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase.UPGRADING_DATABASE
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason.DIRECTORY_CHANGE
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.Idle
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.Running
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.WaitingForRetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ManagedLibraryProcessingCoordinatorLifecycleTest {
    private val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
    private val preferences = mock(SharedPreferences::class.java)
    private val context = mock(Context::class.java)
    private val coordinator = ManagedLibraryProcessingCoordinator

    @Before
    fun setUp() = runTest {
        `when`(editor.commit()).thenReturn(true)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        resetCoordinator()
        clearInvocations(editor)
    }

    @After
    fun tearDown() = runTest {
        resetCoordinator()
    }

    @Test
    fun `begin if idle starts one operation and shares it only for the same reason`() = runTest {
        val operationId = checkNotNull(coordinator.beginIfIdle(context, DIRECTORY_CHANGE, REBUILDING_INDEX))

        assertEquals(operationId, coordinator.beginIfIdle(context, DIRECTORY_CHANGE, UPGRADING_DATABASE))
        assertNull(coordinator.beginIfIdle(context, LEGACY_DATABASE_UPGRADE, UPGRADING_DATABASE))
        assertEquals(Running(operationId, DIRECTORY_CHANGE, REBUILDING_INDEX), coordinator.state.value)
        verify(editor).putString("operation_id", operationId)
        verify(editor).putString("state_kind", "running")
    }

    @Test
    fun `progress of the active operation is persisted and kept across a phase change`() = runTest {
        val operationId = checkNotNull(coordinator.beginIfIdle(context, DIRECTORY_CHANGE, REBUILDING_INDEX))

        coordinator.updateProgress(context, "other-operation", processed = 1, total = 2)
        coordinator.updateProgress(context, operationId, processed = 3, total = 10, currentItem = " song.flac ")

        val progressed = Running(
            operationId, DIRECTORY_CHANGE, REBUILDING_INDEX,
            processed = 3, total = 10, currentItem = "song.flac"
        )
        assertEquals(progressed, coordinator.state.value)
        verify(editor).putInt("processed", 3)
        verify(editor).putInt("total", 10)
        verify(editor).putString("current_item", "song.flac")

        coordinator.advancePhase(context, operationId, UPGRADING_DATABASE)

        assertEquals(progressed.copy(phase = UPGRADING_DATABASE), coordinator.state.value)
        verify(editor).putString("phase", "UPGRADING_DATABASE")
    }

    @Test
    fun `a waiting directory change is reused and completed only once orphaned`() = runTest {
        val waitingId = checkNotNull(coordinator.ensureWaitingForRetry(context, DIRECTORY_CHANGE))

        assertEquals(waitingId, coordinator.ensureWaitingForRetry(context, DIRECTORY_CHANGE))
        assertNull(coordinator.ensureWaitingForRetry(context, LEGACY_DATABASE_UPGRADE))
        assertEquals(WaitingForRetry(waitingId, DIRECTORY_CHANGE), coordinator.state.value)
        verify(editor).putString("state_kind", "waiting")

        assertFalse(
            coordinator.completeOrphanedTerminalDirectoryChange(
                context, waitingId, requestAutoResume = false, activeMigrationWorkPresent = true
            )
        )
        assertEquals(WaitingForRetry(waitingId, DIRECTORY_CHANGE), coordinator.state.value)
        assertTrue(
            coordinator.completeOrphanedTerminalDirectoryChange(
                context, " $waitingId ", requestAutoResume = false, activeMigrationWorkPresent = false
            )
        )
        assertEquals(Idle, coordinator.state.value)
        verify(editor).clear()
    }

    @Test
    fun `a running operation is not reported as waiting for retry`() = runTest {
        val runningId = checkNotNull(coordinator.beginIfIdle(context, DIRECTORY_CHANGE, REBUILDING_INDEX))

        assertNull(coordinator.ensureWaitingForRetry(context, DIRECTORY_CHANGE))
        assertEquals(Running(runningId, DIRECTORY_CHANGE, REBUILDING_INDEX), coordinator.state.value)
    }

    private suspend fun resetCoordinator() {
        coordinator.state.value.operationId?.let { operationId ->
            coordinator.complete(context, operationId)
        }
    }
}
