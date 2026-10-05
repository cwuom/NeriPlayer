package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class CarArtworkPublicationOwnerTest {
    @Test
    fun `late cover publication cannot replace a newer song`() = runTest {
        val pending = mapOf(1L to CompletableDeferred<String?>(), 2L to CompletableDeferred<String?>())
        val port = object : PlaybackServicePresentationPort by mock(PlaybackServicePresentationPort::class.java) {
            override suspend fun publishArtwork(song: SongItem, bitmap: Bitmap, source: String?): String? =
                pending.getValue(song.id).await()
            override fun artworkUri(song: SongItem): String = "initial-${song.id}"
        }
        var published = 0
        val owner = CarArtworkPublicationOwner(backgroundScope, port) { published++ }
        val first = readyArtwork()
        val second = readyArtwork()
        assertEquals("initial-1", owner.observe(song(1L), first))
        runCurrent()
        assertEquals("initial-2", owner.observe(song(2L), second))
        runCurrent()
        pending.getValue(2L).complete("published-2")
        runCurrent()
        pending.getValue(1L).complete("published-1")
        runCurrent()
        assertEquals("published-2", owner.observe(song(2L), second))
        assertEquals(1, published)
    }

    @Test
    fun `repeated metadata updates reuse published cover and clear on empty song`() = runTest {
        var writes = 0
        val port = object : PlaybackServicePresentationPort by mock(PlaybackServicePresentationPort::class.java) {
            override suspend fun publishArtwork(song: SongItem, bitmap: Bitmap, source: String?): String {
                writes++
                return "published"
            }
            override fun artworkUri(song: SongItem): String = "initial"
        }
        val owner = CarArtworkPublicationOwner(backgroundScope, port) {}
        val artwork = readyArtwork()
        owner.observe(song(1L), artwork)
        runCurrent()
        repeat(20) { assertEquals("published", owner.observe(song(1L), artwork)) }
        assertEquals(1, writes)
        assertNull(owner.observe(null, artwork))
    }

    private fun readyArtwork() = PlaybackArtworkSnapshot("cover", mock(Bitmap::class.java), null, true, false, false)
    private fun song(id: Long) = SongItem(id, "Song", "Artist", "Album", 1L, 10L, null)
}
