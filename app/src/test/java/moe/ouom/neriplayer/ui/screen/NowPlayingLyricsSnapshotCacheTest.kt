package moe.ouom.neriplayer.ui.screen

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsPreparationInput
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LoadedLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingFastLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingInitialLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsRefreshVersions
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsSnapshotCache
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.nowPlayingLyricsSnapshotKey
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsLoadOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsSnapshotCacheTest {
    private val context = Mockito.mock(Context::class.java)
    private val song = SongItem(41L, "Current", "Artist", "Album", 1L, 60_000L, null)
    private val request = NowPlayingLyricsLoadRequest(context, song, "https://media", true,
        LyricSourcePreference.Automatic, null)
    private val versions = NowPlayingLyricsRefreshVersions(0, 0, 0)

    @Test
    fun `equal background publication keeps the visible advanced preparation in the reusable snapshot`() = runTest {
        val displayed = buildNowPlayingFastLyricsState("[00:01.00]ready", null, null)
        val background = displayed.copy()
        val pending = CompletableDeferred<LoadedLyricsState>()
        val cache = NowPlayingLyricsSnapshotCache()
        val stages = object : NowPlayingLyricsStages {
            override val snapshotCache = cache
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(displayed, null, null, false, false)
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) =
                pending.await()
        }
        val input = AdvancedLyricsPreparationInput(displayed.rawLyrics, null, displayed.lyrics, emptyList(), false)
        val entry = displayed.advancedLyricsPreparation.entryFor(input)
        entry.prepare()
        val prepared = checkNotNull(entry.ready)
        assertTrue(prepared.lines.isNotEmpty())
        val first = Page(this)
        try {
            val owner = first.compose(request, versions, stages)
            runCurrent()
            assertSame(displayed, owner.state)
            pending.complete(background)
            runCurrent()

            assertSame(displayed, owner.state)
            val reusable = checkNotNull(cache.reusable(nowPlayingLyricsSnapshotKey(request, versions, 0L)))
            assertSame("快照必须沿用实际显示且已准备完成的实例", displayed, reusable.state)
            assertSame(prepared, reusable.state.advancedLyricsPreparation.entryFor(input).ready)
        } finally {
            first.close()
        }
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            assertSame(displayed, owner.state)
            assertSame(prepared, owner.state.advancedLyricsPreparation.entryFor(input).ready)
            assertTrue(owner.secondaryResolved)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `reusable complete snapshot skips synchronous initial lyric parsing`() = runTest {
        val sources = OnlineSources().apply { translation = "translation" }
        val delegate = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        var initialReads = 0
        val stages = object : NowPlayingLyricsStages by delegate {
            override fun readInitial(request: NowPlayingLyricsLoadRequest): NowPlayingInitialLyricsResult {
                initialReads++
                return delegate.readInitial(request)
            }
        }
        loadedPage(this, request, versions, stages).close()
        assertEquals(1, initialReads)
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            assertEquals("online lyrics", owner.state.lyrics.single().text)
            assertEquals("translation", owner.state.translatedLyrics.single().text)
            assertTrue(owner.secondaryResolved)
            assertEquals("a ready snapshot must avoid main-thread initial parsing", 1, initialReads)
            runCurrent()
            assertEquals(1, sources.reads)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `reentry invalidates old display after source lyric preference metadata or download changes`() = runTest {
        val changed = listOf(
            request.copy(song = song.copy(id = 42)) to versions,
            request.copy(song = song.copy(sourceStableKey = "42|netease|")) to versions,
            request.copy(song = song.copy(name = "new name")) to versions,
            request.copy(song = song.copy(artist = "new artist")) to versions,
            request.copy(song = song.copy(durationMs = 120_000)) to versions,
            request.copy(song = song.copy(lyricSyncRevision = 1)) to versions,
            request.copy(song = song.copy(lyricSyncEdited = true)) to versions,
            request.copy(song = song.copy(matchedSongId = "42")) to versions,
            request.copy(preferWordTimedLyrics = false) to versions,
            request.copy(defaultLyricSource = LyricSourcePreference.Kugou) to versions,
            request.copy(currentMediaUrl = null) to versions,
            request to versions.copy(lyricsPreferenceRevision = 1),
            request to versions.copy(downloadPresenceVersion = 1),
            request to versions.copy(downloadedLyricsRefreshVersion = 1)
        )
        for ((input, refresh) in changed) {
            val sources = OnlineSources()
            val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
            loadedPage(this, request, versions, stages).close()
            sources.text = "new lyrics"
            val reopened = Page(this)
            try {
                val owner = reopened.compose(input, refresh, stages)
                assertTrue(owner.state.lyrics.isEmpty())
                runCurrent()
                assertEquals(listOf("new lyrics"), owner.state.lyrics.map { it.text })
                assertEquals(2, sources.reads)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun `refreshed playback url while closed still restores lyrics without blank or source reads`() = runTest {
        val sources = OnlineSources()
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        loadedPage(this, request, versions, stages).close()
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request.copy(currentMediaUrl = "https://media?sig=refreshed"), versions, stages)
            assertEquals("online lyrics", owner.state.lyrics.single().text)
            assertTrue(owner.secondaryResolved)
            runCurrent()
            assertEquals("online lyrics", owner.state.lyrics.single().text)
            assertEquals(1, sources.reads)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `clearing underlying cache during composition prevents the scheduled effect from reusing old lyrics`() = runTest {
        val sources = OnlineSources()
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        loadedPage(this, request, versions, stages).close()
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            assertEquals("online lyrics", owner.state.lyrics.single().text)
            sources.cacheGeneration++
            sources.text = "after clear"
            runCurrent()
            assertEquals("after clear", owner.state.lyrics.single().text)
            assertEquals(2, sources.reads)
        } finally {
            reopened.close()
        }
        val afterClear = Page(this)
        try {
            val owner = afterClear.compose(request, versions, stages)
            assertEquals("after clear", owner.state.lyrics.single().text)
            runCurrent()
            assertEquals("after clear", owner.state.lyrics.single().text)
            assertEquals(2, sources.reads)
        } finally {
            afterClear.close()
        }
    }

    @Test
    fun `expired display still renders immediately and retries missing secondary lyrics`() = runTest {
        var nowNanos = 0L
        val sources = OnlineSources()
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler),
            NowPlayingLyricsSnapshotCache { nowNanos })
        loadedPage(this, request, versions, stages).close()
        nowNanos = 60_000_000_000L
        sources.translation = "recovered translation"
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            assertEquals("online lyrics", owner.state.lyrics.single().text)
            runCurrent()
            assertEquals("recovered translation", owner.state.translatedLyrics.single().text)
            assertEquals(2, sources.reads)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `expired complete display keeps secondary tracks while fast reads only the stored original`() = runTest {
        var nowNanos = 0L
        var holdBackground = false
        val pending = CompletableDeferred<Unit>()
        val sources = object : OnlineSources() {
            override suspend fun onlineRomanized(song: SongItem) = listOf(LyricEntry("phonetic", 1000, 2000))
        }.apply { translation = "translation" }
        val delegate = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler),
            NowPlayingLyricsSnapshotCache { nowNanos })
        val stages = object : NowPlayingLyricsStages by delegate {
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) =
                run {
                    if (holdBackground) pending.await()
                    delegate.readBackground(request, fast)
                }
        }
        val stored = request.copy(song = song.copy(originalLyric = "[00:01.00]stored original"))
        loadedPage(this, stored, versions, stages).close()
        nowNanos = 60_000_000_000L
        holdBackground = true
        val reopened = Page(this)
        try {
            val owner = reopened.compose(stored, versions, stages)
            assertEquals("translation", owner.state.translatedLyrics.single().text)
            runCurrent()
            assertEquals(listOf("translation"), owner.state.translatedLyrics.map { it.text })
            assertEquals(listOf("phonetic"), owner.state.phoneticLyrics.map { it.text })
        } finally {
            pending.complete(Unit)
            reopened.close()
        }
    }

    @Test
    fun `cache clearing rejects an already pending background result and its completion marker`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var firstLoad = true
        val sources = OnlineSources()
        val delegate = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val stages = object : NowPlayingLyricsStages by delegate {
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) =
                if (firstLoad) {
                    firstLoad = false
                    withContext(NonCancellable) { pending.await() }
                    buildNowPlayingFastLyricsState("[00:01.00]stale before clear", null, null)
                } else delegate.readBackground(request, fast)
        }
        val first = Page(this)
        try {
            val owner = first.compose(request, versions, stages)
            runCurrent()
            sources.cacheGeneration++
            pending.complete(Unit)
            runCurrent()
            assertTrue(owner.state.lyrics.isEmpty())
            assertFalse(owner.secondaryResolved)
        } finally {
            pending.complete(Unit)
            first.close()
        }
    }

    @Test
    fun `empty source results remain retryable when reopening`() = runTest {
        val sources = OnlineSources().apply { text = "" }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        loadedPage(this, request, versions, stages).close()
        sources.text = "recovered lyrics"
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            runCurrent()
            assertEquals("recovered lyrics", owner.state.lyrics.single().text)
            assertEquals(2, sources.reads)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `confirmed user clear cannot resurrect the previous cached lyrics`() = runTest {
        val sources = OnlineSources()
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        loadedPage(this, request, versions, stages).close()
        val cleared = request.copy(song = song.copy(lyricSyncEdited = true, lyricSyncRevision = 1,
            matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = ""))
        val reopened = Page(this)
        try {
            val owner = reopened.compose(cleared, versions, stages)
            assertTrue(owner.state.lyrics.isEmpty())
            runCurrent()
            assertEquals("", owner.state.rawLyrics)
            assertTrue(owner.state.lyrics.isEmpty())
            assertTrue(owner.state.translatedLyrics.isEmpty())
            assertTrue(owner.state.phoneticLyrics.isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `local sidecar deletion is rechecked instead of restoring old online display cache`() = runTest {
        var lyric: String? = "[00:01.00]local lyrics"
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean) =
                LocalLyricsScanMetadata(lyric = lyric, translatedLyric = null, romanizedLyric = null,
                    sourceResolved = true, hasOriginalSidecar = lyric != null)
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val local = request.copy(song = song.copy(localFilePath = "/music/song.flac"))
        loadedPage(this, local, versions, stages).close()
        lyric = null
        val reopened = Page(this)
        try {
            val owner = reopened.compose(local, versions, stages)
            assertTrue(owner.state.lyrics.isEmpty())
            runCurrent()
            assertTrue(owner.state.lyrics.isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `partial snapshot resumes loading and disposed requests cannot overwrite completed display`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var loads = 0
        val stages = object : NowPlayingLyricsStages {
            override val snapshotCache = NowPlayingLyricsSnapshotCache()
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) = NowPlayingFastLyricsResult(
                buildNowPlayingFastLyricsState("[00:01.00]partial", null, null), null, null, false, false)
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) =
                if (++loads == 1) {
                    withContext(NonCancellable) { pending.await() }
                    buildNowPlayingFastLyricsState("[00:01.00]late disposed", null, null)
                } else buildNowPlayingFastLyricsState("[00:01.00]completed", null, null)
        }
        val first = loadedPage(this, request, versions, stages)
        first.dispose()
        val reopened = Page(this)
        try {
            val owner = reopened.compose(request, versions, stages)
            assertEquals("partial", owner.state.lyrics.single().text)
            assertFalse(owner.secondaryResolved)
            runCurrent()
            assertEquals("completed", owner.state.lyrics.single().text)
            assertTrue(owner.secondaryResolved)
            pending.complete(Unit)
            runCurrent()
        } finally {
            pending.complete(Unit)
            reopened.close()
            first.close()
        }
        val last = Page(this)
        try {
            val owner = last.compose(request, versions, stages)
            assertEquals("completed", owner.state.lyrics.single().text)
            runCurrent()
            assertEquals(2, loads)
        } finally {
            last.close()
        }
    }

    private fun loadedPage(scope: TestScope, request: NowPlayingLyricsLoadRequest,
        versions: NowPlayingLyricsRefreshVersions, stages: NowPlayingLyricsStages): Page = Page(scope).also {
        it.compose(request, versions, stages)
        scope.runCurrent()
    }

    private open class OnlineSources : FakeNowPlayingLyricsSources() {
        override var cacheGeneration = 0L
        var reads = 0
        var text = "online lyrics"
        var translation = ""
        override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> {
            reads++
            return text.takeIf { it.isNotEmpty() }?.let { listOf(LyricEntry(it, 1000, 2000)) }.orEmpty()
        }
        override suspend fun onlineTranslated(song: SongItem) =
            translation.takeIf { it.isNotEmpty() }?.let { listOf(LyricEntry(it, 1000, 2000)) }.orEmpty()
    }

    private class Page(scope: CoroutineScope) {
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(EmptyApplier(), recomposer)
        fun compose(request: NowPlayingLyricsLoadRequest, versions: NowPlayingLyricsRefreshVersions,
            stages: NowPlayingLyricsStages): NowPlayingLyricsLoadOwner {
            lateinit var owner: NowPlayingLyricsLoadOwner
            composition.setContent { owner = rememberNowPlayingLyricsLoadOwner(request, versions, stages) }
            return owner
        }
        fun dispose() {
            composition.dispose()
            recomposer.cancel()
        }
        suspend fun close() {
            dispose()
            recomposer.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
