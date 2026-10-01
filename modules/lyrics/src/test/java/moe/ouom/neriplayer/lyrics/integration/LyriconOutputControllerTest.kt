package moe.ouom.neriplayer.lyrics.integration

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
class LyriconOutputControllerTest {
    @Test
    fun `resolved lyrics use the current song metadata and latest source offset snapshot`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val requested = song(1, offset = 50)
        controller.publishSong(requested)
        controller.syncSong(this, requested, preferences())
        assertEquals(150L, sink.updates.single().offset)
        runCurrent()

        val hydrated = requested.copy(name = "hydrated", userLyricOffsetMs = 80)
        controller.publishSong(hydrated)
        controller.updateOffset(hydrated, preferences(kugou = 450), 1_200L)
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()

        assertEquals(hydrated, sink.updates.last().song)
        assertEquals(530L, sink.updates.last().offset)
        assertEquals("resolved", sink.updates.last().lyrics?.single()?.text)
        controller.updateOffset(hydrated, preferences(kugou = 550), 1_300L)
        assertEquals(630L, sink.offsets.last())
        assertEquals(1_300L, sink.positions.last())
    }

    @Test
    fun `current song identity prevents pending lyrics from reaching another song`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val first = song(1)
        controller.publishSong(first)
        controller.syncSong(this, first, preferences())
        runCurrent()
        controller.publishSong(song(2))
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertEquals(null, sink.updates.single().lyrics)
    }

    @Test
    fun `cleared song rejects resolved lyrics even when the request was not replaced`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val first = song(1)
        controller.publishSong(first)
        controller.syncSong(this, first, preferences())
        runCurrent()
        controller.publishSong(null)
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertEquals(null, sink.updates.single().lyrics)
    }

    @Test
    fun `rapid replacement cancels old request and resets its preferred source`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val first = song(1)
        controller.publishSong(first)
        controller.syncSong(this, first, preferences())
        runCurrent()
        val next = song(2, offset = 30)
        controller.publishSong(next)
        controller.syncSong(this, next, preferences())
        runCurrent()
        loader.complete(1, LyricSourcePreference.Kugou)
        loader.complete(2, LyricSourcePreference.QqMusic)
        runCurrent()
        assertEquals(listOf(1L, 2L, 2L), sink.updates.map { it.song.id })
        assertEquals(230L, sink.updates.last().offset)
        assertFalse(controller.hasPendingUpdate())
    }

    @Test
    fun `disabling output cancels pending lyrics before stopping playback`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val song = song(1)
        controller.publishSong(song)
        controller.syncSong(this, song, preferences())
        runCurrent()
        assertTrue(controller.hasPendingUpdate())
        controller.syncSong(this, song, preferences().copy(enabled = false))
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(listOf(false), sink.playing)
        assertEquals(1, sink.updates.size)
        assertFalse(controller.hasPendingUpdate())
        controller.updateOffset(song, preferences().copy(enabled = false), 99L)
        assertTrue(sink.positions.isEmpty())
    }

    @Test
    fun `clearing current song stops output and resets offset and position`() = runTest {
        val sink = RecordingSink()
        val controller = controller(DeferredLoader(), sink)
        controller.publishSong(null)
        controller.syncSong(this, null, preferences())
        assertEquals(listOf(false), sink.playing)
        assertEquals(listOf(0L), sink.offsets)
        assertEquals(listOf(0L), sink.positions)
        assertFalse(controller.hasPendingUpdate())
    }

    @Test
    fun `cancellation invalidates lyrics even when the song remains selected`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val song = song(1)
        controller.publishSong(song)
        controller.syncSong(this, song, preferences())
        runCurrent()
        controller.cancel()
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        assertEquals(1, sink.updates.size)
        assertFalse(controller.hasPendingUpdate())
    }

    @Test
    fun `explicit offset remains attached to the request after preferred lyrics resolve`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val song = song(1, offset = 40)
        controller.publishSong(song)
        controller.syncSong(this, song, preferences(), lyricOffsetOverrideMs = 777L)
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
        val controller = controller(loader, sink)
        val song = song(1)
        controller.publishSong(song)
        controller.syncSong(this, song, preferences())
        runCurrent()
        loader.complete(1, LyricSourcePreference.Kugou)
        runCurrent()
        controller.updateOffset(song(2, offset = 20), preferences(), 55L)
        assertEquals(120L, sink.offsets.last())
    }

    @Test
    fun `missing preferred source uses song platform offset and null offset updates reset output`() = runTest {
        val sink = RecordingSink()
        val loader = DeferredLoader()
        val controller = controller(loader, sink)
        val song = song(1).copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)
        controller.publishSong(song)
        controller.syncSong(this, song, preferences())
        runCurrent()
        loader.complete(1, null)
        runCurrent()
        assertEquals(200L, sink.updates.last().offset)
        controller.updateOffset(null, preferences(), 999L)
        assertEquals(0L, sink.offsets.last())
        assertEquals(0L, sink.positions.last())
    }

    private fun controller(loader: DeferredLoader, sink: RecordingSink) = LyriconOutputController(
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
