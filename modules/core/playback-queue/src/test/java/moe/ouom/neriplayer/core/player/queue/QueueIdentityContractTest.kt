package moe.ouom.neriplayer.core.player.queue
import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.model.QueueInsertPlacement
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueEditOwner
import moe.ouom.neriplayer.core.player.queue.policy.reorderQueueSongsPreservingLatestMetadata
import moe.ouom.neriplayer.core.player.queue.policy.resolvePlayerQueueRestoreOrder
import moe.ouom.neriplayer.core.player.queue.state.PlayerQueueStateStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueIdentityContractTest {
    private val first = SongItem(1L, "First", "Artist", "Album", 1L, 100L, null)

    @Test
    fun `same-source matching follows the injected semantics when stable keys differ`() {
        val alias = first.copy(id = 2L)
        val identity = object : QueueSongIdentity {
            override fun stableKey(song: SongItem): String = song.id.toString()
            override fun sameIdentity(song: SongItem, other: SongItem?): Boolean =
                other != null && song.albumId == other.albumId
        }
        val store = PlayerQueueStateStore(identity).also { it.publish(listOf(first), 0) }
        store.updateSongMatching(alias) { it.copy(name = "Updated") }
        assertEquals("Updated", store.snapshot().playlist.single().name)
        val inserted = PlayerQueueEditOwner(identity).insert(
            PlayerQueueSnapshot.from(listOf(first), 0), alias, first, QueueInsertPlacement.END
        )
        assertEquals(listOf(alias), inserted?.queue?.playlist)
        assertEquals(0, inserted?.queue?.currentIndex)
        assertEquals(1, resolvePlayerQueueRestoreOrder(listOf(first.copy(albumId = 99L), first), alias, 0, identity)?.currentIndex)
    }

    @Test
    fun `key grouping does not replace same-source matching for edits`() {
        val second = first.copy(id = 2L, name = "Second")
        val identity = object : QueueSongIdentity {
            override fun stableKey(song: SongItem): String = "shared-key"
            override fun sameIdentity(song: SongItem, other: SongItem?): Boolean = song.id == other?.id
        }
        val inserted = PlayerQueueEditOwner(identity).insert(
            PlayerQueueSnapshot.from(listOf(first), 0), second, first, QueueInsertPlacement.END
        )
        assertEquals(listOf(first, second), inserted?.queue?.playlist)
        assertEquals(listOf(second, first), reorderQueueSongsPreservingLatestMetadata(
            listOf(first, second), listOf(second, first), identity
        ))
    }
}
