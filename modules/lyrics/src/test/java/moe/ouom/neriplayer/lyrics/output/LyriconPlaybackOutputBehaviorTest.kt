package moe.ouom.neriplayer.lyrics.output

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyriconPlaybackOutputBehaviorTest {
    @Test
    fun `resolved lyrics use the current song metadata and latest source offset snapshot`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val requested = song(1, offset = 50)
        output.publishSong(requested)
        output.syncSong(this, requested, preferences())
        assertEquals(150L, sink.updates.single().offset)
        runCurrent()

        val hydrated = requested.copy(name = "hydrated", userLyricOffsetMs = 80)
        output.publishSong(hydrated)
        output.updateOffset(hydrated, preferences(kugou = 450), 1_200L)
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()

        assertEquals(hydrated, sink.updates.last().song)
        assertEquals(530L, sink.updates.last().offset)
        assertEquals("resolved", sink.updates.last().lyrics?.single()?.text)
        output.updateOffset(hydrated, preferences(kugou = 550), 1_300L)
        assertEquals(630L, sink.offsets.last())
        assertEquals(1_300L, sink.positions.last())
    }

    @Test
    fun `current song identity prevents pending lyrics from reaching another song`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val first = song(1)
        output.publishSong(first)
        output.syncSong(this, first, preferences())
        runCurrent()
        output.publishSong(song(2))
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertEquals(null, sink.updates.single().lyrics)
    }

    @Test
    fun `cleared song rejects resolved lyrics even when the request was not replaced`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val first = song(1)
        output.publishSong(first)
        output.syncSong(this, first, preferences())
        runCurrent()
        output.publishSong(null)
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertEquals(null, sink.updates.single().lyrics)
    }

    @Test
    fun `rapid replacement cancels old request and resets its preferred source`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val first = song(1)
        output.publishSong(first)
        output.syncSong(this, first, preferences())
        runCurrent()
        val next = song(2, offset = 30)
        output.publishSong(next)
        output.syncSong(this, next, preferences())
        runCurrent()
        loader.complete(1, LyricSourcePreference.Kugou)
        loader.complete(2, LyricSourcePreference.QqMusic)
        runCurrent()
        assertEquals(listOf(1L, 2L, 2L), sink.updates.map { it.song.id })
        assertEquals(230L, sink.updates.last().offset)
        assertFalse(output.hasPendingUpdate())
    }

    @Test
    fun `disabling output cancels pending lyrics before stopping playback`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val song = song(1)
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        assertTrue(output.hasPendingUpdate())
        output.syncSong(this, song, preferences().copy(enabled = false))
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(listOf(false), sink.playing)
        assertEquals(1, sink.updates.size)
        assertFalse(output.hasPendingUpdate())
        output.updateOffset(song, preferences().copy(enabled = false), 99L)
        assertTrue(sink.positions.isEmpty())
    }

    @Test
    fun `clearing current song stops output and resets offset and position`() = runTest {
        val sink = RecordingSink()
        val output = output(DeferredLoader(), sink)
        output.publishSong(null)
        output.syncSong(this, null, preferences())
        assertEquals(listOf(false), sink.playing)
        assertEquals(listOf(0L), sink.offsets)
        assertEquals(listOf(0L), sink.positions)
        assertFalse(output.hasPendingUpdate())
    }

    @Test
    fun `cancellation invalidates lyrics even when the song remains selected`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val song = song(1)
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        output.cancel()
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertFalse(output.hasPendingUpdate())
    }

    @Test
    fun `explicit offset remains attached to the request after preferred lyrics resolve`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val song = song(1, offset = 40)
        output.publishSong(song)
        output.syncSong(this, song, preferences(), lyricOffsetOverrideMs = 777L)
        runCurrent()
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(listOf(777L, 777L), sink.updates.map { it.offset })
        assertEquals(LyricSourcePreference.CloudMusic, loader.requestedSources.single())
    }

    @Test
    fun `preferred source offset is used only for the recorded song key`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val song = song(1)
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        output.updateOffset(song(2, offset = 20), preferences(), 55L)
        assertEquals(120L, sink.offsets.last())
    }

    @Test
    fun `missing preferred source uses song platform offset and null offset updates reset output`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val output = output(loader, sink)
        val song = song(1).copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        loader.complete(1, null)
        runCurrent()
        assertEquals(200L, sink.updates.last().offset)
        output.updateOffset(null, preferences(), 999L)
        assertEquals(0L, sink.offsets.last())
        assertEquals(0L, sink.positions.last())
    }

    @Test
    fun `late callbacks after cancellation and same song replacement cannot overwrite new lyrics`() = runTest {
        val sink = RecordingSink()
        val publishCallbacks = mutableListOf<(List<LyricEntry>, List<LyricEntry>, LyricSourcePreference?) -> Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = LyriconLyricsLoader { _, _, publish ->
            publishCallbacks += publish
            release.await()
        }
        val output = output(loader, sink)
        val song = song(1, offset = 30)
        val staleLyrics = listOf(LyricEntry(startTimeMs = 0, endTimeMs = 1_000, text = "stale"))
        val freshLyrics = listOf(LyricEntry(startTimeMs = 0, endTimeMs = 1_000, text = "fresh"))
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        val oldPublish = publishCallbacks.single()

        output.cancel()
        runCurrent()
        try {
            oldPublish(staleLyrics, emptyList(), LyricSourcePreference.Kugou)
        } catch (_: CancellationException) {
        }
        assertEquals(1, sink.updates.size)
        assertEquals(null, sink.updates.single().lyrics)

        output.syncSong(this, song, preferences())
        runCurrent()
        try {
            oldPublish(staleLyrics, emptyList(), LyricSourcePreference.Kugou)
        } catch (_: CancellationException) {
        }
        assertEquals(2, sink.updates.size)
        assertTrue(sink.updates.all { it.lyrics == null })

        publishCallbacks.last()(freshLyrics, emptyList(), LyricSourcePreference.QqMusic)
        assertEquals(listOf(null, null, "fresh"), sink.updates.map { it.lyrics?.single()?.text })
        assertEquals(listOf(130L, 130L, 230L), sink.updates.map { it.offset })
        release.complete(Unit)
        runCurrent()
    }

    @Test
    fun `same song reload resets resolved source offset while waiting for new lyrics`() = runTest {
        val sink = RecordingSink()
        val results = listOf(
            CompletableDeferred<LyricSourcePreference>(),
            CompletableDeferred<LyricSourcePreference>(),
        )
        var requestIndex = 0
        val loader = LyriconLyricsLoader { _, _, publish ->
            val result = results[requestIndex++]
            val source = result.await()
            publish(listOf(LyricEntry(startTimeMs = 0, endTimeMs = 1_000, text = "resolved")), emptyList(), source)
        }
        val output = output(loader, sink)
        val song = song(1, offset = 40)
        output.publishSong(song)
        output.syncSong(this, song, preferences())
        runCurrent()
        results[0].complete(LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(340L, sink.updates.last().offset)
        output.updateOffset(song, preferences(), 450L)
        assertEquals(340L, sink.offsets.last())

        output.syncSong(this, song, preferences())
        runCurrent()
        assertEquals(null, sink.updates.last().lyrics)
        assertEquals(140L, sink.updates.last().offset)
        output.updateOffset(song, preferences(), 500L)
        assertEquals(140L, sink.offsets.last())
        assertEquals(500L, sink.positions.last())

        results[1].complete(LyricSourcePreference.QqMusic)
        runCurrent()
        assertEquals(listOf(140L, 340L, 140L, 240L), sink.updates.map { it.offset })
        assertEquals("resolved", sink.updates.last().lyrics?.single()?.text)
        output.updateOffset(song, preferences(), 550L)
        assertEquals(listOf(340L, 140L, 240L), sink.offsets)
        assertEquals(listOf(450L, 500L, 550L), sink.positions)
    }

    private fun output(loader: LyriconLyricsLoader, sink: RecordingSink) = LyriconPlaybackOutput(
        loader = loader,
        sink = sink,
        stableKey = { it.id.toString() },
        sameIdentity = { song, other -> song.id == other?.id },
    )

    private fun preferences(kugou: Long = 300) = LyriconPreferences(
        enabled = true,
        preferredSource = LyricSourcePreference.CloudMusic,
        cloudMusicOffsetMs = 100,
        qqMusicOffsetMs = 200,
        kugouOffsetMs = kugou,
        lrcLibOffsetMs = 400,
        amllTtmlOffsetMs = 500,
    )

    private fun song(id: Long, offset: Long = 0) = SongItem(
        id = id, name = "song$id", artist = "artist", album = "album", albumId = 0,
        durationMs = 10_000, coverUrl = null, userLyricOffsetMs = offset,
    )

    private class DeferredLoader : LyriconLyricsLoader {
        private val results = mutableMapOf<Long, CompletableDeferred<LyricSourcePreference?>>()
        val requestedSources = mutableListOf<LyricSourcePreference>()

        override suspend fun load(
            song: SongItem,
            preferredSource: LyricSourcePreference,
            publish: (List<LyricEntry>, List<LyricEntry>, LyricSourcePreference?) -> Unit,
        ) {
            requestedSources += preferredSource
            val source = results.getOrPut(song.id) { CompletableDeferred() }.await()
            publish(listOf(LyricEntry(startTimeMs = 0, endTimeMs = 1_000, text = "resolved")), emptyList(), source)
        }

        fun complete(id: Long, source: LyricSourcePreference?) {
            results.getValue(id).complete(source)
        }
    }

    private data class Update(
        val song: SongItem,
        val lyrics: List<LyricEntry>?,
        val offset: Long,
    )

    private class RecordingSink : LyriconOutputSink {
        val updates = mutableListOf<Update>()
        val playing = mutableListOf<Boolean>()
        val offsets = mutableListOf<Long>()
        val positions = mutableListOf<Long>()

        override fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?, lyricOffsetMs: Long) {
            updates += Update(song, lyrics, lyricOffsetMs)
        }
        override fun setPlaybackState(playing: Boolean) { this.playing += playing }
        override fun setLyricOffset(offsetMs: Long) { offsets += offsetMs }
        override fun setPosition(positionMs: Long) { positions += positionMs }
    }
}
