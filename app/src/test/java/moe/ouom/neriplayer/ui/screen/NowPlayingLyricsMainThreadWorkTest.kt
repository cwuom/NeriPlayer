package moe.ouom.neriplayer.ui.screen

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingFastLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsRefreshVersions
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildPreferredLyricSourceState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.overlayConfirmedUserLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NowPlayingLyricsMainThreadWorkTest {
    private val context = Mockito.mock(Context::class.java)
    private val original = wordTimedLyrics("original")
    private val translated = wordTimedLyrics("translated")
    private val phonetic = wordTimedLyrics("phonetic")
    private val remote = SongItem(
        91L, "Cold lyrics", "Artist", "Album", 1L, 400_000L, null,
        matchedLyric = original, matchedTranslatedLyric = translated, matchedRomanizedLyric = phonetic
    )

    @Test
    fun localStoredLyricsAreParsedOnIoInsteadOfTheSynchronousInitialRead() = runBlocking {
        assertDeferredUntilFastRead(remote.copy(album = "__local_files__", localFilePath = "/music/cold.flac"))
    }

    @Test
    fun coldOnlineStoredLyricsAreParsedOnIoInsteadOfTheSynchronousInitialRead() = runBlocking {
        assertDeferredUntilFastRead(remote)
    }

    @Test
    fun confirmedEditsWaitForIoWithoutShowingAnOlderPreferredCache() = runBlocking {
        val stale = PreferredLyricSourceResult(listOf(LyricEntry("stale preferred", 1000, 2000)),
            source = LyricSourcePreference.Kugou)
        assertDeferredUntilFastRead(remote.copy(lyricSyncEdited = true, lyricSyncRevision = 20), stale)
    }

    @Test
    fun coldProductionCompositionDoesNotParseBeforeItsIoStageRuns() = runTest {
        val stages = NowPlayingLyricsLoadStages(FakeNowPlayingLyricsSources(), StandardTestDispatcher(testScheduler))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        lateinit var owner: NowPlayingLyricsLoadOwner
        try {
            Mockito.mockStatic(parserClass, Mockito.CALLS_REAL_METHODS).use { parser ->
                composition.setContent {
                    owner = rememberNowPlayingLyricsLoadOwner(
                        request(remote.copy(album = "__local_files__", localFilePath = "/music/cold.flac")),
                        NowPlayingLyricsRefreshVersions(0, 0, 0), stages
                    )
                }

                parser.verify({ parseNeteaseLyricsAuto(anyString()) }, Mockito.never())
                assertTrue(owner.state.lyrics.isEmpty())
            }
            runCurrent()
            assertEquals(300, owner.state.lyrics.size)
            assertEquals(300, owner.state.translatedLyrics.size)
            assertEquals(300, owner.state.phoneticLyrics.size)
            assertTrue(owner.state.lyrics.all { it.words?.isNotEmpty() == true })
        } finally {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
        }
    }

    @Test
    fun alreadyPreparedConfirmedLyricsPublishWithoutParsingAgainOnTheOwnerThread() = runTest {
        val edited = remote.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
        val empty = buildNowPlayingFastLyricsState(null, null, null)
        val prepared = overlayConfirmedUserLyrics(edited, empty)
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(prepared, null, null, false, false)
            override suspend fun readBackground(request: NowPlayingLyricsLoadRequest, fast: NowPlayingFastLyricsResult) = prepared
        }
        val owner = NowPlayingLyricsLoadOwner(empty, this, stages)
        Mockito.mockStatic(parserClass, Mockito.CALLS_REAL_METHODS).use { parser ->
            owner.reload(request(edited))
            runCurrent()

            parser.verify({ parseNeteaseLyricsAuto(anyString()) }, Mockito.never())
            assertSame(prepared, owner.state)
            assertTrue(owner.secondaryResolved)
        }
        owner.dispose()
    }

    @Test
    fun repeatedConfirmedOverlayReusesTheStateButSameRevisionClearsInvalidateIt() {
        val edited = remote.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
        val prepared = overlayConfirmedUserLyrics(edited, buildNowPlayingFastLyricsState(null, null, null))

        assertSame(prepared, overlayConfirmedUserLyrics(edited, prepared))
        val cleared = overlayConfirmedUserLyrics(edited.copy(matchedTranslatedLyric = "", matchedRomanizedLyric = ""), prepared)
        assertNotSame(prepared, cleared)
        assertEquals("", cleared.rawTranslatedLyrics)
        assertEquals("", cleared.rawPhoneticLyrics)
        assertTrue(cleared.translatedLyrics.isEmpty())
        assertTrue(cleared.phoneticLyrics.isEmpty())
        assertTrue(cleared.embeddedPhoneticLyrics.isEmpty())
        assertTrue(cleared.lyrics.all { it.translation == null })
        assertTrue(cleared.plainLyrics.all { it.translation == null })
        assertEquals(300, cleared.lyrics.size)
        assertSame(cleared, overlayConfirmedUserLyrics(edited.copy(matchedTranslatedLyric = "", matchedRomanizedLyric = ""), cleared))
    }

    @Test
    fun matchingRawTextCannotMakeAnUnpreparedPreferredStateBypassConfirmedEdits() {
        val edited = remote.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
        val stale = buildPreferredLyricSourceState(PreferredLyricSourceResult(
            listOf(LyricEntry("stale preferred", 1000, 2000)), source = LyricSourcePreference.Kugou
        )).copy(rawLyrics = original, rawTranslatedLyrics = translated, rawPhoneticLyrics = phonetic)

        val prepared = overlayConfirmedUserLyrics(edited, stale)

        assertEquals(300, prepared.lyrics.size)
        assertEquals(300, prepared.translatedLyrics.size)
        assertEquals(300, prepared.phoneticLyrics.size)
        assertFalse(prepared.lyrics.any { it.text == "stale preferred" })
        assertNull(prepared.preferredSource)
    }

    private suspend fun assertDeferredUntilFastRead(song: SongItem, cached: PreferredLyricSourceResult? = null) {
        val caller = Thread.currentThread()
        val sourceThreads = ConcurrentLinkedQueue<Thread>()
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun hasManagedDownload(song: SongItem): Boolean {
                sourceThreads += Thread.currentThread()
                return false
            }
            override fun fastDownloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? {
                sourceThreads += Thread.currentThread()
                return null
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val stages = NowPlayingLyricsLoadStages(sources, io)
            val request = request(song).copy(cachedPreferredLyrics = cached)
            Mockito.mockStatic(parserClass, Mockito.CALLS_REAL_METHODS).use { parser ->
                val initial = stages.readInitial(request)

                parser.verify({ parseNeteaseLyricsAuto(anyString()) }, Mockito.never())
                assertTrue(sourceThreads.isEmpty())
                assertTrue(initial.state.lyrics.isEmpty())
                assertTrue(initial.state.translatedLyrics.isEmpty())
                assertTrue(initial.state.phoneticLyrics.isEmpty())

                val fast = stages.readFast(request)
                assertFalse(sourceThreads.isEmpty())
                assertTrue(sourceThreads.all { it !== caller })
                assertEquals(300, fast.state.lyrics.size)
                assertEquals(300, fast.state.translatedLyrics.size)
                assertEquals(300, fast.state.phoneticLyrics.size)
                assertTrue(fast.state.lyrics.all { it.words?.isNotEmpty() == true })
                assertEquals(original, fast.state.rawLyrics)
                assertEquals(translated, fast.state.rawTranslatedLyrics)
                assertEquals(phonetic, fast.state.rawPhoneticLyrics)
            }
        }
    }

    private fun request(song: SongItem) = NowPlayingLyricsLoadRequest(
        context, song, "content://playing", true, LyricSourcePreference.Automatic, null
    )

    private fun wordTimedLyrics(prefix: String): String = (0 until 300).joinToString("\n") { line ->
        val start = 1000 + line * 1000
        "[$start,1000]($start,500,0)$prefix(${start + 500},500,0)$line"
    }

    private companion object {
        val parserClass: Class<*> = Class.forName("moe.ouom.neriplayer.lyrics.parser.LyricParserKt")
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
