package moe.ouom.neriplayer.data.local.database

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomRecoveryMergeTest {
    @Test
    fun unchangedLegacySnapshotDoesNotOverwriteRoomState() {
        val baseline = listOf(Snapshot("one", "legacy"))

        val merged = mergeRoomRecoverySnapshot(
            roomSnapshot = listOf(Snapshot("one", "room")),
            recoveryBaseline = baseline,
            currentSnapshot = baseline,
            keyOf = Snapshot::key,
            mergeLocalChange = { _, local -> local }
        )

        assertEquals(listOf(Snapshot("one", "room")), merged)
    }

    @Test
    fun localChangesAndRemovalsAreAppliedToRecoveredRoomState() {
        val baseline = listOf(
            Snapshot("one", "legacy"),
            Snapshot("two", "legacy")
        )

        val merged = mergeRoomRecoverySnapshot(
            roomSnapshot = listOf(
                Snapshot("one", "room"),
                Snapshot("two", "room"),
                Snapshot("three", "room")
            ),
            recoveryBaseline = baseline,
            currentSnapshot = listOf(
                Snapshot("one", "edited"),
                Snapshot("four", "new")
            ),
            keyOf = Snapshot::key,
            mergeLocalChange = { _, local -> local }
        )

        assertEquals(
            listOf(
                Snapshot("one", "edited"),
                Snapshot("three", "room"),
                Snapshot("four", "new")
            ),
            merged
        )
    }

    private data class Snapshot(
        val key: String,
        val value: String
    )
}
