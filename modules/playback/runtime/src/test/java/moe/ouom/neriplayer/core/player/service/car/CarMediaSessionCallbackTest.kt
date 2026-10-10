package moe.ouom.neriplayer.core.player.service.car

import android.os.Bundle
import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.core.player.service.car.library.CarPlaybackSelection
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaSessionCallbackTest {
    @Test
    fun `preparing an item never plays until an explicit play command`() {
        val port = FakePort()
        val callback = CarMediaSessionCallback(port)
        callback.onPrepareFromMediaId(port.mediaId(), null)
        assertTrue(port.calls.isEmpty())
        callback.onPlay()
        assertEquals(listOf("selection:1"), port.calls)
    }

    @Test
    fun `an invalid or stale prepared id does not resume unrelated playback`() {
        val port = FakePort()
        val callback = CarMediaSessionCallback(port)
        callback.onPrepareFromMediaId("invalid", null)
        callback.onPlay()
        assertTrue(port.calls.isEmpty())
        callback.onPrepareFromMediaId(port.mediaId(), null)
        port.songs = listOf(song(2))
        callback.onPlay()
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `voice without a query resumes while unmatched text does not`() {
        val port = FakePort()
        val callback = CarMediaSessionCallback(port)
        callback.onPlayFromSearch("  ", null)
        callback.onPlayFromSearch("does not exist", null)
        assertEquals(listOf("resume"), port.calls)
        callback.onPlayFromSearch("Song 1", null)
        assertEquals(listOf("resume", "selection:1"), port.calls)
    }

    @Test
    fun `search preparation and transport commands preserve their semantics`() {
        val port = FakePort()
        val callback = CarMediaSessionCallback(port)
        callback.onPrepareFromSearch("Song 1", null)
        assertTrue(port.calls.isEmpty())
        callback.onPlay()
        callback.onPause()
        callback.onStop()
        callback.onSeekTo(500)
        callback.onSkipToQueueItem(42)
        assertEquals(listOf("selection:1", "media_session_pause:false", "media_session_stop:true", "seek:500", "queue:42"), port.calls)
    }

    @Test
    fun `media selection waits for player initialization`() {
        val port = FakePort().apply { ready = false }
        val callback = CarMediaSessionCallback(port)
        callback.onPlayFromMediaId(port.mediaId(), null)
        assertTrue(port.calls.isEmpty())
        port.pending.single().invoke()
        assertEquals(listOf("selection:1"), port.calls)
    }

    @Test
    fun `cached media selection waits for local playlists and pause cancels a pending lookup`() {
        val port = FakePort().apply { libraryReady = false }
        val callback = CarMediaSessionCallback(port)
        callback.onPlayFromMediaId(port.mediaId(), null)
        assertTrue(port.calls.isEmpty())
        callback.onPause()
        port.libraryPending.single().invoke()
        assertEquals(listOf("media_session_pause:false"), port.calls)
    }

    @Test
    fun `foreground failure cancels pending lookups and preparation until a new play request`() {
        val port = FakePort().apply { libraryReady = false }
        val callback = CarMediaSessionCallback(port)
        callback.onPlayFromSearch("Song 1", null)
        callback.cancelPendingPlaybackRequests()
        port.libraryPending.single().invoke()
        assertTrue(port.calls.isEmpty())

        callback.onPrepareFromMediaId(port.mediaId(), null)
        callback.cancelPendingPlaybackRequests()
        callback.onPlay()
        assertEquals(listOf("resume"), port.calls)
        port.libraryReady = true
        callback.onPlayFromMediaId(port.mediaId(), null)
        assertEquals(listOf("resume", "selection:1"), port.calls)
    }

    @Test
    fun `empty voice query resumes without waiting for the library`() {
        val port = FakePort().apply { libraryReady = false }
        CarMediaSessionCallback(port).onPlayFromSearch("", null)
        assertEquals(listOf("resume"), port.calls)
        assertTrue(port.libraryPending.isEmpty())
    }

    @Test
    fun `a cancelled cached selection cannot resurrect an older prepared item`() {
        val port = FakePort().apply { libraryReady = false }
        val callback = CarMediaSessionCallback(port)
        callback.onPrepareFromMediaId(port.mediaId(), null)
        callback.onPlayFromSearch("Song 1", null)
        callback.onPause()
        port.libraryPending.single().invoke()
        callback.onPlay()
        assertEquals(listOf("media_session_pause:false", "resume"), port.calls)
    }

    private class FakePort : CarMediaSessionControlPort {
        var songs = listOf(song(1))
        var ready = true
        var libraryReady = true
        val calls = mutableListOf<String>()
        val pending = mutableListOf<() -> Unit>()
        val libraryPending = mutableListOf<() -> Unit>()
        override fun runWhenReady(source: String, action: () -> Unit) {
            if (ready) action() else pending += action
        }
        override fun runWhenLibraryReady(source: String, action: () -> Unit) = runWhenReady(source) {
            if (libraryReady) action() else libraryPending += action
        }
        override fun library() = CarMediaLibrary(CarLibrarySnapshot(queue = songs))
        fun mediaId() = library().children(CarMediaIds.QUEUE).first().mediaId
        override fun resume() { calls += "resume" }
        override fun playSelection(selection: CarPlaybackSelection) { calls += "selection:${selection.songs[selection.startIndex].id}" }
        override fun playQueueItem(id: Long) { calls += "queue:$id" }
        override fun pause(source: String, stopService: Boolean) { calls += "$source:$stopService" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun customAction(action: String, extras: Bundle?) { calls += action }
    }

    companion object {
        private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "netease", 0, 1_000L, null)
    }
}
