package moe.ouom.neriplayer.core.player.runtime.progress

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressIdentityTest {
    @Test
    fun `duration backfill preserves the current representation using injected identity`() {
        val original = song(1L)
        val downloaded = song(2L)
        val port = RecordingPort(original)
        val owner = PlaybackProgressOwner(port, sameSong = { first, second ->
            first.artist == second?.artist
        }, nowElapsedMs = { 0L })

        owner.maybeUpdateSongDuration(downloaded, 120_000L)

        assertEquals(original.id, port.current.id)
        assertEquals(120_000L, port.current.durationMs)
        assertEquals(120_000L, owner.duration.value)
        assertEquals(1, port.persistRequests)
    }

    private class RecordingPort(var current: SongItem) : PlaybackProgressPort {
        var persistRequests = 0
        override fun rememberLongFormEnabled() = false
        override fun rememberedPosition(song: SongItem) = 0L
        override fun writeRememberedPosition(song: SongItem, positionMs: Long) = Unit
        override fun currentSong() = current
        override fun reportedPositionMs() = 0L
        override fun playerPositionMs(): Long? = null
        override fun playerDurationMs(): Long? = null
        override fun updateQueuedDurationIfUnknown(song: SongItem, durationMs: Long) = false
        override fun replaceCurrentSong(song: SongItem) { current = song }
        override fun scheduleImmediateStatePersist() { persistRequests++ }
        override fun pendingMediaLoadActive() = false
        override fun pendingMediaLoadPositionMs() = 0L
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song $id",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 0L,
        coverUrl = null
    )
}
