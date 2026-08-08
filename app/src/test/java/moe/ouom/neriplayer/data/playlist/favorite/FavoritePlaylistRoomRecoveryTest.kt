package moe.ouom.neriplayer.data.playlist.favorite

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritePlaylistRoomRecoveryTest {
    @Test
    fun unchangedLegacySnapshotDoesNotRemoveRoomOnlyFavorite() {
        val baseline = listOf(favorite(id = 1L, name = "legacy"))
        val room = listOf(
            favorite(id = 1L, name = "room"),
            favorite(id = 2L, name = "room-only")
        )

        val recovered = mergeFavoritePlaylistRoomRecovery(
            roomSnapshot = room,
            recoveryBaseline = baseline,
            currentSnapshot = baseline
        )

        assertEquals(room, recovered)
    }

    @Test
    fun localEditAndRemovalAreAppliedWithoutDroppingRoomOnlyFavorite() {
        val baseline = listOf(
            favorite(id = 1L, name = "legacy"),
            favorite(id = 2L, name = "removed")
        )
        val room = listOf(
            favorite(id = 1L, name = "room"),
            favorite(id = 2L, name = "room-removed"),
            favorite(id = 3L, name = "room-only")
        )

        val recovered = mergeFavoritePlaylistRoomRecovery(
            roomSnapshot = room,
            recoveryBaseline = baseline,
            currentSnapshot = listOf(favorite(id = 1L, name = "edited"))
        )

        assertEquals(
            listOf(
                favorite(id = 1L, name = "edited"),
                favorite(id = 3L, name = "room-only")
            ),
            recovered
        )
    }

    private fun favorite(id: Long, name: String): FavoritePlaylist {
        return FavoritePlaylist(
            id = id,
            name = name,
            coverUrl = null,
            trackCount = 0,
            source = "test",
            songs = emptyList(),
            addedTime = id,
            sortOrder = id,
            modifiedAt = id
        )
    }
}
