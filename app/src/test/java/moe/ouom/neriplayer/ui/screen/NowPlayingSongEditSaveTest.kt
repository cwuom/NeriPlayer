package moe.ouom.neriplayer.ui.screen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongSaveResult
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongSaveSteps
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.captureEditSongOperation
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.executeEditSongSave
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.failureMessage
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.latestMatchingEditSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSongEditSaveTest {
    private val song = SongItem(8L, "Original", "Artist", "Album", 2L, 60_000L, null)

    @Test
    fun `captured edit operation returns ordinary failure but propagates cancellation`() = runTest {
        assertEquals("saved", captureEditSongOperation { "saved" }.getOrThrow())
        assertTrue(captureEditSongOperation<String> { error("write failed") }.isFailure)
        var cancellations = 0
        try {
            captureEditSongOperation(onCancelled = { cancellations++ }) {
                throw CancellationException(
                    "closed"
                )
            }
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // closing an edit session must still cancel the underlying job
        }
        assertEquals(1, cancellations)
        try {
            captureEditSongOperation<String> { throw AssertionError("fatal") }
            org.junit.Assert.fail("Fatal failures must propagate")
        } catch (_: AssertionError) {
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun `metadata write uses refreshed song after lyrics update`() = runTest {
        val events = mutableListOf<String>()
        var current = song
        val steps = object : EditSongSaveSteps {
            override fun latestSong(original: SongItem): SongItem {
                events += "latest"
                return current
            }

            override suspend fun writeLyrics(song: SongItem): Boolean {
                events += "lyrics:${song.name}"
                current = song.copy(name = "Refreshed")
                return true
            }

            override suspend fun writeMetadata(song: SongItem): Boolean {
                events += "metadata:${song.name}"
                return true
            }
        }

        assertEquals(EditSongSaveResult.SAVED, executeEditSongSave(song, steps))
        assertEquals(listOf("latest", "lyrics:Original", "latest", "metadata:Refreshed"), events)
    }

    @Test
    fun `metadata write keeps the prewrite snapshot if playback changes song`() = runTest {
        val initial = song.copy(customName = "Current metadata")
        val otherSong = song.copy(id = 99L)
        var current: SongItem? = initial
        var metadataInput: SongItem? = null
        val steps = object : EditSongSaveSteps {
            override fun latestSong(original: SongItem): SongItem =
                latestMatchingEditSong(current, original)

            override suspend fun writeLyrics(song: SongItem): Boolean {
                current = otherSong
                return true
            }

            override suspend fun writeMetadata(song: SongItem): Boolean {
                metadataInput = song
                return true
            }
        }

        assertEquals(EditSongSaveResult.SAVED, executeEditSongSave(song, steps))
        assertEquals(initial, metadataInput)
    }

    @Test
    fun `failed lyric write skips metadata write and reports its own error`() = runTest {
        val events = mutableListOf<String>()
        val result = executeEditSongSave(song, recordingSteps(events, lyricsSaved = false))

        assertEquals(EditSongSaveResult.LYRICS_FAILED, result)
        assertEquals(CoreCommonR.string.local_song_lyrics_write_failed, result.failureMessage())
        assertEquals(listOf("lyrics"), events)
    }

    @Test
    fun `failed metadata write reports metadata error`() = runTest {
        val events = mutableListOf<String>()
        val result = executeEditSongSave(song, recordingSteps(events, metadataSaved = false))

        assertEquals(EditSongSaveResult.METADATA_FAILED, result)
        assertEquals(CoreCommonR.string.local_song_metadata_write_failed, result.failureMessage())
        assertEquals(listOf("lyrics", "metadata"), events)
    }

    private fun recordingSteps(
        events: MutableList<String>,
        lyricsSaved: Boolean = true,
        metadataSaved: Boolean = true
    ) = object : EditSongSaveSteps {
        override fun latestSong(original: SongItem): SongItem = original

        override suspend fun writeLyrics(song: SongItem): Boolean {
            events += "lyrics"
            return lyricsSaved
        }

        override suspend fun writeMetadata(song: SongItem): Boolean {
            events += "metadata"
            return metadataSaved
        }
    }
}
