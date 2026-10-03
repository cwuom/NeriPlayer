package moe.ouom.neriplayer.ui.screen

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LoadedLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingFastLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsRefreshVersions
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingInitialLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.nowPlayingLyricsReloadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsLoadOwnerTest {
    private val context = Mockito.mock(Context::class.java)
    private val song = SongItem(41L, "Current", "Artist", "Album", 1L, 60_000L, null)

    @Test
    fun `same revision confirmed clear replaces rendered state and rejects late old network publication`() = runTest {
        val oldBackground = CompletableDeferred<LoadedLyricsState>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) = fastState("stale source")
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult): LoadedLyricsState =
                if (request.song?.matchedLyric == "[00:01.00]user old") {
                    withContext(NonCancellable) { oldBackground.await() }
                } else lyricState("stale source")
        }
        val fixture = LyricsCompositionFixture(this)
        try {
            val original = song.copy(lyricSyncEdited = true, lyricSyncRevision = 20,
                matchedLyric = "[00:01.00]user old")
            val versions = NowPlayingLyricsRefreshVersions(0, 0, 0)
            val owner = fixture.compose(request(original), versions, stages)
            runCurrent()
            assertEquals("user old", owner.state.lyrics.single().text)
            val cleared = original.copy(matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = "")
            assertSame(owner, fixture.compose(request(cleared), versions, stages))
            runCurrent()
            assertEquals("", owner.state.rawLyrics)
            assertEquals("", owner.state.rawTranslatedLyrics)
            assertEquals("", owner.state.rawPhoneticLyrics)
            assertTrue(owner.state.lyrics.isEmpty())
            assertTrue(owner.state.translatedLyrics.isEmpty())
            assertTrue(owner.state.phoneticLyrics.isEmpty())
            oldBackground.complete(lyricState("late old network"))
            runCurrent()
            assertEquals("", owner.state.rawLyrics)
            assertTrue(owner.state.lyrics.isEmpty())
        } finally {
            oldBackground.complete(lyricState("cleanup"))
            fixture.close()
        }
    }

    @Test
    fun `partial confirmed clear removes old translation when other tracks have no source result`() = runTest {
        val previous = song.copy(lyricSyncEdited = true, lyricSyncRevision = 20,
            matchedLyric = "[00:01.00]old original", matchedTranslatedLyric = "[00:01.00]old translation")
        val empty = lyricState(null)
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(empty, null, null, false, false)
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) = empty
        }
        val owner = NowPlayingLyricsLoadOwner(buildNowPlayingInitialLyricsState(previous, null), this, stages)
        owner.reload(request(previous.copy(matchedLyric = null, matchedTranslatedLyric = "")))
        runCurrent()
        assertEquals("", owner.state.rawTranslatedLyrics)
        assertTrue(owner.state.translatedLyrics.isEmpty())
        assertNull(owner.state.rawLyrics)
        assertTrue(owner.state.lyrics.isEmpty())
    }

    @Test
    fun `reload key preserves every lyric edit source and file identity trigger`() {
        val original = song.copy(matchedLyric = "original", matchedTranslatedLyric = "translation",
            matchedRomanizedLyric = "romanized", originalLyric = "baseline",
            originalTranslatedLyric = "baseline translation", originalRomanizedLyric = "baseline romanized",
            matchedSongId = "matched", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            mediaUri = "https://media", localFilePath = "/music/song.flac",
            lyricSyncEdited = true, lyricSyncRevision = 20)
        val key = nowPlayingLyricsReloadKey(original)
        val changed = listOf(
            original.copy(id = 42), original.copy(album = "another album"),
            original.copy(mediaUri = null), original.copy(localFilePath = null),
            original.copy(matchedLyric = null), original.copy(matchedTranslatedLyric = null),
            original.copy(matchedRomanizedLyric = null), original.copy(originalLyric = null),
            original.copy(originalTranslatedLyric = null), original.copy(originalRomanizedLyric = null),
            original.copy(matchedSongId = null), original.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            original.copy(lyricSyncEdited = false), original.copy(lyricSyncEdited = null),
            original.copy(lyricSyncRevision = 21), original.copy(matchedLyric = ""),
            original.copy(matchedTranslatedLyric = ""), original.copy(matchedRomanizedLyric = "")
        )
        for (current in changed) assertNotEquals(key, nowPlayingLyricsReloadKey(current))
        assertEquals(key, nowPlayingLyricsReloadKey(original.copy()))
    }

    @Test
    fun `reload key ignores song metadata outside the original effect inputs`() {
        val unrelated = song.copy(name = "new title", artist = "new artist", albumId = 2,
            durationMs = 120_000, coverUrl = "https://cover", customName = "custom title",
            customArtist = "custom artist", customCoverUrl = "custom cover", userLyricOffsetMs = 50,
            originalName = "base title", originalArtist = "base artist", originalCoverUrl = "base cover",
            localFileName = "renamed.flac", channelId = "channel", audioId = "audio", subAudioId = "part",
            playlistContextId = "playlist", sourceStableKey = "source", streamUrl = "https://stream", addedAt = 5)
        assertEquals(nowPlayingLyricsReloadKey(song), nowPlayingLyricsReloadKey(unrelated))
    }

    @Test
    fun `reload key distinguishes no song unknown marker reset and zero revision`() {
        assertNull(nowPlayingLyricsReloadKey(null))
        val legacy = nowPlayingLyricsReloadKey(song)
        assertNotEquals(null, legacy)
        assertNotEquals(legacy, nowPlayingLyricsReloadKey(song.copy(lyricSyncEdited = false)))
        assertNotEquals(legacy, nowPlayingLyricsReloadKey(song.copy(lyricSyncEdited = true)))
        assertNotEquals(legacy, nowPlayingLyricsReloadKey(song.copy(lyricSyncRevision = 1)))
    }

    @Test
    fun `production composition initializes from fast cache and forwards current request payload`() = runTest {
        var cacheReads = 0
        val cached = PreferredLyricSourceResult(
            lyrics = listOf(LyricEntry(text = "cached", startTimeMs = 1000L, endTimeMs = 2000L)),
            source = LyricSourcePreference.Kugou
        )
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun cachedPreferred(song: SongItem, source: LyricSourcePreference,
                preferWordTimedLyrics: Boolean): PreferredLyricSourceResult {
                cacheReads++
                return cached
            }
            override suspend fun preferred(song: SongItem, source: LyricSourcePreference) = cached
        }
        val delegate = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val received = mutableListOf<NowPlayingLyricsLoadRequest>()
        val stages = object : NowPlayingLyricsStages by delegate {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult {
                received += request
                return delegate.readFast(request)
            }
        }
        val fixture = LyricsCompositionFixture(this)
        try {
            val input = request(song).copy(currentMediaUrl = "https://first", defaultLyricSource = LyricSourcePreference.Kugou)
            val versions = NowPlayingLyricsRefreshVersions(1, 2, 3)
            val owner = fixture.compose(input, versions, stages)
            assertEquals("cached", owner.state.lyrics.single().text)
            runCurrent()
            assertEquals(input.copy(cachedPreferredLyrics = cached), received.single())
            assertEquals(1, cacheReads)

            val newContext = Mockito.mock(Context::class.java)
            val changed = input.copy(context = newContext, currentMediaUrl = "https://second")
            assertSame(owner, fixture.compose(changed, versions, stages))
            runCurrent()
            assertEquals(changed.copy(cachedPreferredLyrics = cached), received.last())
            assertEquals(2, received.size)
            assertEquals(1, cacheReads)

            fixture.compose(changed.copy(preferWordTimedLyrics = false), versions, stages)
            runCurrent()
            assertFalse(received.last().preferWordTimedLyrics)
            assertEquals(2, cacheReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production composition schedules each external invalidation but ignores unrelated song metadata`() = runTest {
        val received = mutableListOf<NowPlayingLyricsLoadRequest>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult {
                received += request
                return fastState("loaded")
            }
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) = fast.state
        }
        val fixture = LyricsCompositionFixture(this)
        try {
            var input = request(song)
            var versions = NowPlayingLyricsRefreshVersions(0, 0, 0)
            val original = fixture.compose(input, versions, stages)
            runCurrent()
            input = input.copy(song = song.copy(customName = "unrelated title", durationMs = 120_000))
            assertSame(original, fixture.compose(input, versions, stages))
            runCurrent()
            assertEquals(1, received.size)

            versions = versions.copy(downloadPresenceVersion = 1)
            fixture.compose(input, versions, stages)
            runCurrent()
            assertEquals(2, received.size)
            versions = versions.copy(downloadedLyricsRefreshVersion = 1)
            fixture.compose(input, versions, stages)
            runCurrent()
            assertEquals(3, received.size)
            versions = versions.copy(lyricsPreferenceRevision = 1)
            fixture.compose(input, versions, stages)
            runCurrent()
            assertEquals(4, received.size)
            input = input.copy(defaultLyricSource = LyricSourcePreference.Kugou)
            assertNotSame(original, fixture.compose(input, versions, stages))
            runCurrent()
            assertEquals(5, received.size)
            assertEquals(LyricSourcePreference.Kugou, received.last().defaultLyricSource)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production local media change keeps background work while reset cancels stale publication and null clears`() = runTest {
        val pending = CompletableDeferred<LoadedLyricsState>()
        val received = mutableListOf<NowPlayingLyricsLoadRequest>()
        var cancelled = false
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult {
                received += request
                return NowPlayingFastLyricsResult(buildNowPlayingInitialLyricsState(request.song, null), null, null, false, false)
            }
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult): LoadedLyricsState {
                if (request.song?.lyricSyncEdited != true) return lyricState(null)
                return try { pending.await() } finally { cancelled = true }
            }
        }
        val fixture = LyricsCompositionFixture(this)
        try {
            val local = song.copy(localFilePath = "/music/local.flac", matchedLyric = "[00:01.00]old edit",
                lyricSyncEdited = true, lyricSyncRevision = 20)
            val versions = NowPlayingLyricsRefreshVersions(0, 0, 0)
            val input = request(local).copy(currentMediaUrl = "content://indexed")
            val previous = fixture.compose(input, versions, stages)
            runCurrent()
            assertEquals("old edit", previous.state.lyrics.single().text)
            assertSame(previous, fixture.compose(input.copy(currentMediaUrl = "content://playable"), versions, stages))
            runCurrent()
            assertEquals(1, received.size)
            assertFalse(cancelled)

            val reset = local.copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21)
            val current = fixture.compose(request(reset), versions, stages)
            assertNotSame(previous, current)
            runCurrent()
            assertTrue(cancelled)
            assertTrue(current.state.lyrics.isEmpty())
            pending.complete(lyricState("stale"))
            runCurrent()
            assertTrue(current.state.lyrics.isEmpty())
            val empty = fixture.compose(request(song).copy(song = null), versions, stages)
            runCurrent()
            assertNull(received.last().song)
            assertTrue(empty.state.lyrics.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production composition loads a new source identity even when lyric and media inputs are unchanged`() = runTest {
        val remote = song.copy(channelId = "bilibili", audioId = "BV1", subAudioId = "1")
        val pairs = listOf(
            song.copy(sourceStableKey = "41|netease|") to song.copy(sourceStableKey = "42|netease|"),
            remote to remote.copy(channelId = "netease"),
            remote to remote.copy(audioId = "BV2"),
            remote to remote.copy(subAudioId = "2")
        )
        for ((first, second) in pairs) {
            assertNotEquals(first.stableKey(), second.stableKey())
            assertEquals(nowPlayingLyricsReloadKey(first), nowPlayingLyricsReloadKey(second))
            val received = mutableListOf<SongItem?>()
            val stages = object : NowPlayingLyricsStages {
                override suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult {
                    received += request.song
                    return fastState(request.song?.stableKey().orEmpty())
                }
                override suspend fun readBackground(request: NowPlayingLyricsLoadRequest,
                    fast: NowPlayingFastLyricsResult) = fast.state
            }
            val fixture = LyricsCompositionFixture(this)
            try {
                val versions = NowPlayingLyricsRefreshVersions(0, 0, 0)
                val previous = fixture.compose(request(first), versions, stages)
                runCurrent()
                assertEquals(first.stableKey(), previous.state.rawLyrics)
                val current = fixture.compose(request(second), versions, stages)
                assertNotSame(previous, current)
                runCurrent()
                assertEquals(listOf(first, second), received)
                assertEquals(second.stableKey(), current.state.rawLyrics)
                assertTrue(current.secondaryResolved)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `new source identity loads while a disposed owner cannot publish late lyrics`() = runTest {
        val first = song.copy(sourceStableKey = "41|netease|")
        val second = song.copy(sourceStableKey = "42|netease|")
        val pending = CompletableDeferred<LoadedLyricsState>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) = fastState("fast")
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest,
                fast: NowPlayingFastLyricsResult): LoadedLyricsState =
                if (request.song?.sourceStableKey == first.sourceStableKey) {
                    withContext(NonCancellable) { pending.await() }
                } else lyricState("new source")
        }
        val fixture = LyricsCompositionFixture(this)
        try {
            val versions = NowPlayingLyricsRefreshVersions(0, 0, 0)
            val previous = fixture.compose(request(first), versions, stages)
            runCurrent()
            assertEquals("fast", previous.state.rawLyrics)
            val current = fixture.compose(request(second), versions, stages)
            assertNotSame(previous, current)
            runCurrent()
            assertEquals("new source", current.state.rawLyrics)
            pending.complete(lyricState("stale source"))
            runCurrent()
            assertEquals("new source", current.state.rawLyrics)
            assertEquals("fast", previous.state.rawLyrics)
        } finally {
            pending.complete(lyricState("cleanup"))
            fixture.close()
        }
    }

    private class LyricsCompositionFixture(scope: CoroutineScope) {
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(EmptyApplier(), recomposer)

        fun compose(request: NowPlayingLyricsLoadRequest, versions: NowPlayingLyricsRefreshVersions,
            stages: NowPlayingLyricsStages): NowPlayingLyricsLoadOwner {
            lateinit var owner: NowPlayingLyricsLoadOwner
            composition.setContent { owner = rememberNowPlayingLyricsLoadOwner(request, versions, stages) }
            return owner
        }

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
        }
    }

    @Test
    fun `translation only edit keeps online original fallback without preferred source replacing the edit`() = runTest {
        var onlineReads = 0
        var preferredReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult? {
                preferredReads++
                return null
            }
            override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> {
                onlineReads++
                return listOf(LyricEntry(text = "online original", startTimeMs = 1000L, endTimeMs = 2000L))
            }
        }
        val edited = song.copy(matchedTranslatedLyric = "[00:01.00]user translation",
            lyricSyncEdited = true, lyricSyncRevision = 20)
        val request = NowPlayingLyricsLoadRequest(context, edited, "https://media", true,
            LyricSourcePreference.Kugou, null)
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val fast = stages.readFast(request)
        val loaded = stages.readBackground(request, fast)
        assertEquals("online original", loaded.lyrics.single().text)
        assertEquals("user translation", loaded.translatedLyrics.single().text)
        assertEquals(1, onlineReads)
        assertEquals(1, preferredReads)
    }

    @Test
    fun `committed reset recreates composed owner and cannot retain or republish the previous edit`() = runTest {
        val oldBackground = CompletableDeferred<LoadedLyricsState>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(buildNowPlayingInitialLyricsState(request.song, null), null, null, false, false)

            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) =
                if (request.song?.lyricSyncEdited == true) oldBackground.await() else lyricState(null)
        }
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        val scope = this
        lateinit var rendered: NowPlayingLyricsLoadOwner
        fun composeSong(current: SongItem) {
            composition.setContent {
                rendered = rememberNowPlayingLyricsOwner(current, LyricSourcePreference.Automatic,
                    buildNowPlayingInitialLyricsState(current, null), scope, stages)
            }
        }
        try {
            val edited = song.copy(matchedLyric = "[00:01.00]old edit", lyricSyncEdited = true, lyricSyncRevision = 20)
            composeSong(edited)
            val previous = rendered
            previous.reload(request(edited))
            runCurrent()
            assertEquals("old edit", previous.state.lyrics.single().text)
            val reset = song.copy(lyricSyncEdited = false, lyricSyncRevision = 21)
            composeSong(reset)
            assertNotSame(previous, rendered)
            rendered.reload(request(reset))
            runCurrent()
            assertNull(rendered.state.rawLyrics)
            assertTrue(rendered.state.lyrics.isEmpty())
            oldBackground.complete(lyricState("stale background"))
            runCurrent()
            assertNull(rendered.state.rawLyrics)
            assertTrue(rendered.state.lyrics.isEmpty())
        } finally {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
        }
    }

    @Test
    fun `composed owner changes for marker only but survives an ordinary empty refresh`() = runTest {
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) = fastState("")
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) = lyricState(null)
        }
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        val scope = this
        lateinit var rendered: NowPlayingLyricsLoadOwner
        fun composeSong(current: SongItem) {
            composition.setContent {
                rendered = rememberNowPlayingLyricsOwner(current, LyricSourcePreference.Automatic,
                    buildNowPlayingInitialLyricsState(current, null), scope, stages)
            }
        }
        try {
            val cached = song.copy(matchedLyric = "[00:01.00]visible", lyricSyncEdited = false, lyricSyncRevision = 20)
            composeSong(cached)
            val previous = rendered
            val edited = cached.copy(lyricSyncEdited = true)
            composeSong(edited)
            assertNotSame(previous, rendered)
            val committed = rendered
            committed.reload(request(edited))
            runCurrent()
            assertEquals("visible", committed.state.lyrics.single().text)
            composeSong(edited.copy(customName = "unrelated metadata"))
            assertSame(committed, rendered)
        } finally {
            composition.dispose()
            recomposer.cancel()
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

    @Test
    fun `new request cancels old background publication`() = runTest {
        val oldBackground = CompletableDeferred<LoadedLyricsState>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                fastState(request.song?.name.orEmpty())

            override suspend fun readBackground(
                request: NowPlayingLyricsLoadRequest,
                fast: NowPlayingFastLyricsResult
            ): LoadedLyricsState = if (request.song?.name == "Old") {
                oldBackground.await()
            } else {
                lyricState("new background")
            }
        }
        val owner = NowPlayingLyricsLoadOwner(lyricState("initial"), this, stages)
        owner.reload(request(song.copy(name = "Old")))
        runCurrent()
        assertEquals("Old", owner.state.rawLyrics)
        assertFalse(owner.secondaryResolved)

        owner.reload(request(song.copy(name = "New")))
        runCurrent()
        assertEquals("new background", owner.state.rawLyrics)
        assertTrue(owner.secondaryResolved)

        oldBackground.complete(lyricState("stale background"))
        runCurrent()
        assertEquals("new background", owner.state.rawLyrics)
    }

    @Test
    fun `empty refresh retains rendered lyrics and disposal rejects late publish`() = runTest {
        val empty = lyricState(null)
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(empty, null, null, false, false)

            override suspend fun readBackground(
                request: NowPlayingLyricsLoadRequest,
                fast: NowPlayingFastLyricsResult
            ) = empty
        }
        val owner = NowPlayingLyricsLoadOwner(lyricState("visible"), this, stages)
        owner.reload(request(song))
        runCurrent()
        assertEquals("visible", owner.state.rawLyrics)
        assertTrue(owner.secondaryResolved)

        owner.dispose()
        owner.publish(1L, song, lyricState("late"), "background")
        assertEquals("visible", owner.state.rawLyrics)
    }

    private fun request(song: SongItem) = NowPlayingLyricsLoadRequest(
        context, song, null, true, LyricSourcePreference.Automatic, null
    )

    private fun fastState(text: String) = NowPlayingFastLyricsResult(
        lyricState(text), null, null, false, false
    )

    private fun lyricState(text: String?): LoadedLyricsState = buildNowPlayingFastLyricsState(text, null, null)
}
