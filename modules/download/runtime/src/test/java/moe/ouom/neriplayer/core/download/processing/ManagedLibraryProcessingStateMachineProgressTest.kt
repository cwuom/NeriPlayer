package moe.ouom.neriplayer.core.download.processing

import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase.REBUILDING_INDEX
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase.UPGRADING_DATABASE
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason.DIRECTORY_CHANGE
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.Idle
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.Running
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState.WaitingForRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryProcessingStateMachineProgressTest {
    @Test
    fun `begin clamps progress into the total and trims the current item`() {
        assertEquals(
            Running("op-1", DIRECTORY_CHANGE, REBUILDING_INDEX, processed = 10, total = 10, currentItem = "song.flac"),
            ManagedLibraryProcessingStateMachine.begin(
                "op-1", DIRECTORY_CHANGE, REBUILDING_INDEX,
                processed = 12, total = 10, currentItem = "  song.flac  "
            )
        )
        assertEquals(
            Running("op-2", DIRECTORY_CHANGE, REBUILDING_INDEX, processed = 0, total = 0),
            ManagedLibraryProcessingStateMachine.begin(
                "op-2", DIRECTORY_CHANGE, REBUILDING_INDEX,
                processed = -3, total = -1, currentItem = "   "
            )
        )
        assertEquals(
            Running("op-3", DIRECTORY_CHANGE, REBUILDING_INDEX, processed = 5),
            ManagedLibraryProcessingStateMachine.begin(
                "op-3", DIRECTORY_CHANGE, REBUILDING_INDEX, processed = 5
            )
        )
    }

    @Test
    fun `advancing a phase only changes the matching operation`() {
        val running = Running("op-1", DIRECTORY_CHANGE, UPGRADING_DATABASE, processed = 2, total = 4)
        val waiting = WaitingForRetry("op-2", DIRECTORY_CHANGE, processed = 1, total = 3, currentItem = "song.flac")

        assertSame(running, ManagedLibraryProcessingStateMachine.advancePhase(running, "op-other", REBUILDING_INDEX))
        assertSame(running, ManagedLibraryProcessingStateMachine.advancePhase(running, "op-1", UPGRADING_DATABASE))
        assertSame(Idle, ManagedLibraryProcessingStateMachine.advancePhase(Idle, "op-1", REBUILDING_INDEX))
        assertEquals(
            running.copy(phase = REBUILDING_INDEX),
            ManagedLibraryProcessingStateMachine.advancePhase(running, "op-1", REBUILDING_INDEX)
        )
        assertEquals(
            waiting.copy(phase = REBUILDING_INDEX),
            ManagedLibraryProcessingStateMachine.advancePhase(waiting, "op-2", REBUILDING_INDEX)
        )
    }

    @Test
    fun `restored running state is owned only by the process token that wrote it`() {
        assertTrue(isManagedLibraryProcessingOwnedByCurrentProcess("running", "token-1", "token-1"))
        listOf(
            Triple("waiting", "token-1", "token-1"),
            Triple(null, "token-1", "token-1"),
            Triple("running", null, null),
            Triple("running", "   ", "   "),
            Triple("running", "token-1", "token-2")
        ).forEach { (stateKind, ownerToken, currentToken) ->
            assertFalse(
                "$stateKind/$ownerToken/$currentToken",
                isManagedLibraryProcessingOwnedByCurrentProcess(stateKind, ownerToken, currentToken)
            )
        }
    }

    @Test
    fun `busy errors name the active reason or fall back to unknown`() {
        assertEquals(
            "managed library processing is busy: DIRECTORY_CHANGE",
            ManagedLibraryProcessingBusyException(DIRECTORY_CHANGE).message
        )
        assertEquals(
            "managed library processing is busy: unknown",
            ManagedLibraryProcessingBusyException(null).message
        )
    }
}
