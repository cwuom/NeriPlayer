package moe.ouom.neriplayer.lyrics.output

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@OptIn(ExperimentalCoroutinesApi::class)
class LyriconPlaybackOutputTest {
    @Test
    fun `public output preserves pending lyrics cancellation and offset inputs`() = runTest {
        val manager = mock(LyriconManager::class.java)
        val ready = CompletableDeferred<Unit>()
        val output = LyriconPlaybackOutput(
            loader = LyriconLyricsLoader { _, _, publish ->
                ready.await()
                publish(emptyList(), emptyList(), null)
            },
            stableKey = { it.id.toString() },
            sameIdentity = { song, current -> song.id == current?.id },
            sink = LyriconManagerOutputSink(manager),
        )
        val song = song()
        val preferences = LyriconPreferences(enabled = true, cloudMusicOffsetMs = 100L)
        output.publishSong(song)
        output.syncSong(this, song, preferences)
        runCurrent()
        assertTrue(output.hasPendingUpdate())
        verify(manager).updateSong(song, null, null, 100L)
        output.updateOffset(song, preferences, 123L)
        verify(manager).setLyricOffset(100L)
        verify(manager).setPosition(123L)
        output.cancel()
        ready.complete(Unit)
        runCurrent()
        assertFalse(output.hasPendingUpdate())
        output.publishSong(null)
        output.syncSong(this, null, preferences)
        verify(manager).setPlaybackState(false)
        verify(manager).setLyricOffset(0L)
        verify(manager).setPosition(0L)
        verifyNoMoreInteractions(manager)
    }

    @Test
    fun `manager sink forwards parsed lyrics translations and exact offset`() {
        val manager = mock(LyriconManager::class.java)
        val sink = LyriconManagerOutputSink(manager)
        val song = song()
        val lyrics = listOf(LyricEntry(startTimeMs = 0, endTimeMs = 100, text = "original"))
        val translated = listOf(LyricEntry(startTimeMs = 0, endTimeMs = 100, text = "translated"))
        sink.updateSong(song, lyrics, translated, 456L)
        sink.setPlaybackState(true)
        sink.setLyricOffset(-30L)
        sink.setPosition(789L)
        verify(manager).updateSong(song, lyrics, translated, 456L)
        verify(manager).setPlaybackState(true)
        verify(manager).setLyricOffset(-30L)
        verify(manager).setPosition(789L)
        verifyNoMoreInteractions(manager)
    }

    @Test
    fun `default output can be constructed without starting an SDK publisher`() {
        val output = LyriconPlaybackOutput(
            loader = LyriconLyricsLoader { _, _, _ -> },
            stableKey = { it.id.toString() },
            sameIdentity = { song, current -> song.id == current?.id },
        )
        assertFalse(output.hasPendingUpdate())
    }

    private fun song() = SongItem(
        id = 1, name = "song", artist = "artist", album = "album", albumId = 0,
        durationMs = 10_000, coverUrl = null,
    )
}
