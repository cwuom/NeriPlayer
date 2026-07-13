package moe.ouom.neriplayer.core.startup.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStartupHistoryRecorderTest {
    @Test
    fun `does not record initial restored song`() = runTest {
        val initialSong = song(id = 1L)
        val currentSong = MutableStateFlow<SongItem?>(initialSong)
        val recorded = mutableListOf<SongItem>()
        val recorder = PlayerStartupHistoryRecorder(
            currentSongFlow = currentSong,
            recordSong = recorded::add,
            startupSongToSkip = currentSong.value,
            settleDelayMs = 700L
        )
        val job = launch {
            recorder.run()
        }

        runCurrent()
        job.cancel()

        assertEquals(emptyList<SongItem>(), recorded)
    }

    @Test
    fun `records song after it stays current through settle delay`() = runTest {
        val currentSong = MutableStateFlow<SongItem?>(null)
        val recorded = mutableListOf<SongItem>()
        val nextSong = song(id = 2L)
        val recorder = PlayerStartupHistoryRecorder(
            currentSongFlow = currentSong,
            recordSong = recorded::add,
            startupSongToSkip = currentSong.value,
            settleDelayMs = 700L
        )
        val job = launch {
            recorder.run()
        }

        currentSong.value = nextSong
        advanceTimeBy(699L)
        assertEquals(emptyList<SongItem>(), recorded)

        advanceTimeBy(1L)
        runCurrent()
        job.cancel()

        assertEquals(listOf(nextSong), recorded)
    }

    @Test
    fun `skips stale song and records latest after next settle delay`() = runTest {
        val currentSong = MutableStateFlow<SongItem?>(null)
        val recorded = mutableListOf<SongItem>()
        val firstSong = song(id = 3L)
        val secondSong = song(id = 4L)
        val recorder = PlayerStartupHistoryRecorder(
            currentSongFlow = currentSong,
            recordSong = recorded::add,
            startupSongToSkip = currentSong.value,
            settleDelayMs = 700L
        )
        val job = launch {
            recorder.run()
        }

        currentSong.value = firstSong
        advanceTimeBy(300L)
        currentSong.value = secondSong
        advanceTimeBy(700L)
        runCurrent()
        assertEquals(emptyList<SongItem>(), recorded)

        advanceTimeBy(700L)
        runCurrent()
        job.cancel()

        assertEquals(listOf(secondSong), recorded)
    }

    @Test
    fun `does not record restored song metadata refresh`() = runTest {
        val initialSong = song(id = 5L)
        val currentSong = MutableStateFlow<SongItem?>(initialSong)
        val recorded = mutableListOf<SongItem>()
        val recorder = PlayerStartupHistoryRecorder(
            currentSongFlow = currentSong,
            recordSong = recorded::add,
            startupSongToSkip = currentSong.value,
            settleDelayMs = 700L
        )
        val job = launch {
            recorder.run()
        }

        runCurrent()
        currentSong.value = initialSong.copy(customName = "Updated title")
        advanceTimeBy(700L)
        runCurrent()
        job.cancel()

        assertEquals(emptyList<SongItem>(), recorded)
    }

    @Test
    fun `records new song after restored song baseline`() = runTest {
        val initialSong = song(id = 6L)
        val currentSong = MutableStateFlow<SongItem?>(initialSong)
        val recorded = mutableListOf<SongItem>()
        val nextSong = song(id = 7L)
        val recorder = PlayerStartupHistoryRecorder(
            currentSongFlow = currentSong,
            recordSong = recorded::add,
            startupSongToSkip = currentSong.value,
            settleDelayMs = 700L
        )
        val job = launch {
            recorder.run()
        }

        runCurrent()
        currentSong.value = nextSong
        advanceTimeBy(700L)
        runCurrent()
        job.cancel()

        assertEquals(listOf(nextSong), recorded)
    }

    private fun song(id: Long): SongItem {
        return SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }
}
