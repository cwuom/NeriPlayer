package moe.ouom.neriplayer.core.player.service.artwork

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackArtworkOwnerTest {
    @Test
    fun `same cover URL on another song starts a new load and hides old artwork`() = runTest {
        val sources = FakeCoverSources().apply { immediateSource = "https://covers/shared.jpg" }
        val first = bitmap()
        val second = bitmap()
        val loaded = ArrayDeque(listOf(first, second))
        val requests = mutableListOf<String>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader {
            requests += it
            loaded.removeFirst()
        })
        val songA = song(1)
        val songB = song(2)

        owner.observe(songA)
        runCurrent()
        assertTrue(owner.snapshot(songA.playbackVisualKey()).mediaReady)

        val beforeSecondLoad = owner.observe(songB)
        assertFalse(beforeSecondLoad.mediaReady)
        assertFalse(beforeSecondLoad.notificationReady)
        runCurrent()

        assertEquals(listOf("https://covers/shared.jpg", "https://covers/shared.jpg"), requests)
        assertSame(second, owner.snapshot(songB.playbackVisualKey()).mediaBitmap)
        assertFalse(owner.snapshot(songA.playbackVisualKey()).mediaReady)
        owner.close()
    }

    @Test
    fun `stale SAF resolution cannot replace a newly selected song`() = runTest {
        val pending = CompletableDeferred<String?>()
        val sources = FakeCoverSources().apply {
            downloadedReference = DownloadedArtworkReference("content://covers/old.jpg", null)
            rebindResult = { _, _ -> pending.await() }
        }
        val events = mutableListOf<PlaybackArtworkChange>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader { null }, events)
        val songA = song(1)
        val songB = song(2)

        assertTrue(owner.observe(songA).pending)
        runCurrent()
        sources.immediateSource = "https://covers/new.jpg"
        owner.observe(songB)
        pending.complete("content://covers/recovered.jpg")
        runCurrent()

        assertEquals("https://covers/new.jpg", owner.snapshot(songB.playbackVisualKey()).coverSource)
        assertFalse(PlaybackArtworkChange.SOURCE_RESOLVED in events)
        owner.close()
    }

    @Test
    fun `repeated observation keeps one pending SAF resolution for the same song`() = runTest {
        val pending = CompletableDeferred<String?>()
        val sources = FakeCoverSources().apply {
            downloadedReference = DownloadedArtworkReference("content://covers/old.jpg", null)
            rebindResult = { _, _ -> pending.await() }
        }
        val owner = owner(sources, PlaybackArtworkBitmapLoader { null })
        val song = song(1)

        owner.observe(song)
        runCurrent()
        owner.observe(song)
        runCurrent()

        assertEquals(1, sources.rebindCalls)
        pending.complete(null)
        runCurrent()
        owner.close()
    }

    @Test
    fun `local load failure resolves a new cover before retrying the stale URI`() = runTest {
        val sources = FakeCoverSources().apply {
            local = true
            immediateSource = "content://covers/stale.jpg"
            resolvedLocal = "content://covers/current.jpg"
        }
        val requests = mutableListOf<String>()
        val events = mutableListOf<PlaybackArtworkChange>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader {
            requests += it
            null
        }, events)
        val song = song(1)

        owner.observe(song)
        runCurrent()

        assertEquals("content://covers/current.jpg", owner.snapshot(song.playbackVisualKey()).coverSource)
        assertTrue(PlaybackArtworkChange.SOURCE_RESOLVED in events)
        advanceTimeBy(MEDIA_ARTWORK_RETRY_COOLDOWN_MS)
        runCurrent()
        assertEquals(listOf("content://covers/stale.jpg"), requests)
        owner.close()
    }

    @Test
    fun `closing owner cancels a pending bitmap and clears retained artwork`() = runTest {
        val pending = CompletableDeferred<Bitmap?>()
        val sources = FakeCoverSources().apply { immediateSource = "https://covers/song.jpg" }
        val events = mutableListOf<PlaybackArtworkChange>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader { pending.await() }, events)
        val song = song(1)

        owner.observe(song)
        runCurrent()
        owner.close()
        pending.complete(bitmap())
        runCurrent()

        assertNull(owner.snapshot(song.playbackVisualKey()).mediaBitmap)
        assertFalse(PlaybackArtworkChange.BITMAP_READY in events)
    }

    @Test
    fun `resolved SAF cover survives a repeated observation of stale song metadata`() = runTest {
        val sources = FakeCoverSources().apply {
            downloadedReference = DownloadedArtworkReference("content://covers/old.jpg", null)
            rebindResult = { _, _ -> "content://covers/new.jpg" }
        }
        val owner = owner(sources, PlaybackArtworkBitmapLoader { null })
        val song = song(1)

        owner.observe(song)
        runCurrent()

        assertEquals("content://covers/new.jpg", owner.observe(song).coverSource)
        owner.close()
    }

    @Test
    fun `remote bitmap is retried after cooldown and becomes ready`() = runTest {
        val sources = FakeCoverSources().apply { immediateSource = "https://covers/song.jpg" }
        val bitmap = bitmap()
        val requests = mutableListOf<String>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader {
            requests += it
            if (requests.size == 1) null else bitmap
        })
        val song = song(1)

        owner.observe(song)
        runCurrent()
        assertFalse(owner.snapshot(song.playbackVisualKey()).mediaReady)
        advanceTimeBy(MEDIA_ARTWORK_RETRY_COOLDOWN_MS + 1L)
        runCurrent()

        assertEquals(2, requests.size)
        assertSame(bitmap, owner.snapshot(song.playbackVisualKey()).mediaBitmap)
        assertTrue(owner.snapshot(song.playbackVisualKey()).mediaReady)
        owner.close()
    }

    @Test
    fun `empty source resolution clears pending state`() = runTest {
        val events = mutableListOf<PlaybackArtworkChange>()
        val owner = owner(FakeCoverSources(), PlaybackArtworkBitmapLoader { null }, events)
        val song = song(1)

        assertTrue(owner.observe(song).pending)
        runCurrent()

        assertFalse(owner.snapshot(song.playbackVisualKey()).pending)
        assertTrue(PlaybackArtworkChange.RESOLUTION_FINISHED_EMPTY in events)
        owner.close()
    }

    @Test
    fun `new immediate cover replaces recovered source for the same song`() = runTest {
        val sources = FakeCoverSources().apply {
            downloadedReference = DownloadedArtworkReference("content://covers/old.jpg", null)
            rebindResult = { _, _ -> "content://covers/recovered.jpg" }
        }
        val owner = owner(sources, PlaybackArtworkBitmapLoader { null })
        val song = song(1)
        owner.observe(song)
        runCurrent()

        sources.immediateSource = "https://covers/new.jpg"
        assertEquals("https://covers/new.jpg", owner.observe(song).coverSource)
        owner.close()
    }

    @Test
    fun `source callback may synchronously refresh metadata without restarting the resolver`() = runTest {
        val sources = FakeCoverSources().apply {
            downloadedReference = DownloadedArtworkReference("content://covers/old.jpg", null)
            rebindResult = { _, _ -> "content://covers/new.jpg" }
        }
        val song = song(1)
        val events = mutableListOf<PlaybackArtworkChange>()
        lateinit var owner: PlaybackArtworkOwner
        owner = PlaybackArtworkOwner(
            resolver = PlaybackCoverSourceResolver(sources),
            loader = PlaybackArtworkBitmapLoader { null },
            clock = PlaybackArtworkClock { testScheduler.currentTime + 1_000L },
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onChange = { change ->
                events += change
                if (change == PlaybackArtworkChange.SOURCE_RESOLVED) owner.observe(song)
            },
        )

        owner.observe(song)
        runCurrent()

        assertEquals(1, events.count { it == PlaybackArtworkChange.SOURCE_RESOLVED })
        assertEquals("content://covers/new.jpg", owner.snapshot(song.playbackVisualKey()).coverSource)
        owner.close()
    }

    @Test
    fun `explicit custom remote artwork does not trigger local source recovery`() = runTest {
        val sources = FakeCoverSources().apply {
            local = true
            immediateSource = "https://covers/custom.jpg"
            resolvedLocal = "content://covers/local.jpg"
        }
        val events = mutableListOf<PlaybackArtworkChange>()
        val owner = owner(sources, PlaybackArtworkBitmapLoader { null }, events)
        val song = song(1).copy(customCoverUrl = "https://covers/custom.jpg")

        owner.observe(song)
        runCurrent()

        assertEquals("https://covers/custom.jpg", owner.snapshot(song.playbackVisualKey()).coverSource)
        assertFalse(PlaybackArtworkChange.SOURCE_RESOLVED in events)
        owner.close()
    }

    private fun kotlinx.coroutines.test.TestScope.owner(
        sources: FakeCoverSources,
        loader: PlaybackArtworkBitmapLoader,
        events: MutableList<PlaybackArtworkChange> = mutableListOf(),
    ): PlaybackArtworkOwner = PlaybackArtworkOwner(
        resolver = PlaybackCoverSourceResolver(sources),
        loader = loader,
        clock = PlaybackArtworkClock { testScheduler.currentTime + 1_000L },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        onChange = { events += it },
    )

    private fun bitmap(): Bitmap = mock(Bitmap::class.java).also {
        `when`(it.width).thenReturn(100)
        `when`(it.height).thenReturn(100)
        `when`(it.byteCount).thenReturn(40_000)
    }

    private fun song(id: Long): SongItem = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        durationMs = 1_000,
        coverUrl = null,
    )

    private class FakeCoverSources : PlaybackCoverSources {
        var local = false
        var immediateSource: String? = null
        var resolvedLocal: String? = null
        var downloadedReference: DownloadedArtworkReference? = null
        var rebindResult: suspend (String, Boolean) -> String? = { _, _ -> null }
        var rebindCalls = 0

        override fun isLocal(song: SongItem): Boolean = local
        override fun immediate(song: SongItem): String? = immediateSource
        override fun peekLocal(song: SongItem): String? = null
        override fun nearby(song: SongItem): String? = null
        override fun resolveLocal(song: SongItem): String? = resolvedLocal
        override fun downloaded(song: SongItem): DownloadedArtworkReference? = downloadedReference
        override suspend fun rebind(fileName: String, forceRefresh: Boolean): String? {
            rebindCalls += 1
            return rebindResult(fileName, forceRefresh)
        }
        override fun isUsable(reference: String): Boolean = true
    }
}
