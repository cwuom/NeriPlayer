package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class NowPlayingLyricsFastStageTest {
    private val context = Mockito.mock(Context::class.java)
    private val localSong = SongItem(
        5L, "Local", "Artist", "__local_files__", 0L, 90_000L, null,
        mediaUri = "content://media/audio/5", localFileName = "track.mp3"
    )

    @Test
    fun `confirmed edits overlay stale preferred local and downloaded first frames per variant`() = runTest {
        val preferred = PreferredLyricSourceResult(
            listOf(LyricEntry("preferred original", 1000, 2000)),
            listOf(LyricEntry("preferred translation", 1000, 2000)),
            listOf(LyricEntry("preferred romanized", 1000, 2000)), LyricSourcePreference.Kugou
        )
        for (managed in listOf(false, true)) {
            val sources = object : FakeNowPlayingLyricsSources() {
                override fun hasManagedDownload(song: SongItem) = managed
                override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean) =
                    LocalLyricsScanMetadata("local old", "local translation", "local romanized")
                override fun fastDownloaded(context: Context, song: SongItem) =
                    ManagedDownloadStorage.DownloadedLyricsBundle("download old", "download translation", "download romanized")
            }
            val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
            val base = localSong.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
            val edits = listOf(
                base.copy(matchedLyric = "[00:01.00]edited", matchedTranslatedLyric = "[00:01.00]edited translation",
                    matchedRomanizedLyric = "[00:01.00]edited romanized"),
                base.copy(matchedTranslatedLyric = "[00:01.00]edited translation"),
                base.copy(matchedRomanizedLyric = "[00:01.00]edited romanized"),
                base.copy(matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = "")
            )
            for (edited in edits) {
                val input = request(edited).copy(cachedPreferredLyrics = preferred, defaultLyricSource = LyricSourcePreference.Kugou)
                for (state in listOf(stages.readInitial(input).state, stages.readFast(input).state)) {
                    assertEquals(edited.matchedLyric, state.rawLyrics)
                    assertEquals(edited.matchedTranslatedLyric, state.rawTranslatedLyrics)
                    assertEquals(edited.matchedRomanizedLyric, state.rawPhoneticLyrics)
                    assertEquals(edited.matchedLyric?.substringAfter(']') ?: "preferred original",
                        state.lyrics.firstOrNull()?.text.orEmpty())
                    assertEquals(edited.matchedTranslatedLyric?.substringAfter(']') ?: "preferred translation",
                        state.translatedLyrics.firstOrNull()?.text.orEmpty())
                    assertEquals(edited.matchedRomanizedLyric?.substringAfter(']') ?: "preferred romanized",
                        state.phoneticLyrics.firstOrNull()?.text.orEmpty())
                }
            }
        }
    }

    @Test
    fun `local fast stage avoids embedded scan then background completes unresolved scan`() = runTest {
        val includesEmbedded = mutableListOf<Boolean>()
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata {
                includesEmbedded += includeEmbedded
                return if (includeEmbedded) {
                    LocalLyricsScanMetadata(
                        lyric = "[00:03.00]embedded", translatedLyric = null,
                        romanizedLyric = null, sourceResolved = true
                    )
                } else {
                    LocalLyricsScanMetadata(
                        lyric = "[00:01.00]stored", translatedLyric = null,
                        romanizedLyric = null, sourceResolved = false
                    )
                }
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val fast = stages.readFast(request(localSong))
        assertEquals(listOf(false), includesEmbedded)
        assertEquals("stored", fast.state.lyrics.single().text)

        val full = stages.readBackground(request(localSong), fast)
        assertEquals(listOf(false, true), includesEmbedded)
        assertEquals("embedded", full.lyrics.single().text)
    }

    @Test
    fun `managed download bypasses ordinary local probe and backfills partial index`() = runTest {
        var scheduled = 0
        var fullReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun hasManagedDownload(song: SongItem) = true
            override fun scheduleManagedRefresh(context: Context) { scheduled++ }
            override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata {
                error("ordinary local probe must not run")
            }
            override fun fastDownloaded(context: Context, song: SongItem) =
                ManagedDownloadStorage.DownloadedLyricsBundle(
                    lyric = "[00:01.00]indexed", translatedLyric = null,
                    romanizedLyric = null, hasOriginalSidecar = true
                )
            override fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle {
                fullReads++
                return ManagedDownloadStorage.DownloadedLyricsBundle(
                    lyric = "[00:01.00]complete",
                    translatedLyric = "[00:01.00]translation",
                    romanizedLyric = "[00:01.00]phonetic",
                    hasOriginalSidecar = true,
                    hasTranslatedSidecar = true,
                    hasRomanizedSidecar = true
                )
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val fast = stages.readFast(request(localSong))
        assertTrue(fast.isManagedLocalDownload)
        assertTrue(fast.canReadManagedDownloadLyrics)
        assertEquals(1, scheduled)
        assertEquals("indexed", fast.state.lyrics.single().text)

        val full = stages.readBackground(request(localSong), fast)
        assertEquals(1, fullReads)
        assertEquals("complete", full.lyrics.single().text)
        assertEquals("translation", full.translatedLyrics.single().text)
    }

    @Test
    fun `fast source failure retains song snapshot`() = runTest {
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata {
                error("provider unavailable")
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val fast = stages.readFast(request(localSong.copy(matchedLyric = "[00:02.00]snapshot")))
        assertFalse(fast.isManagedLocalDownload)
        assertEquals("snapshot", fast.state.lyrics.single().text)
    }

    @Test
    fun `resolved local sidecar is reused without embedded rescan`() = runTest {
        val includesEmbedded = mutableListOf<Boolean>()
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata {
                includesEmbedded += includeEmbedded
                return LocalLyricsScanMetadata(
                    lyric = "[00:01.00]sidecar", translatedLyric = null, romanizedLyric = null,
                    hasOriginalSidecar = true, sourceResolved = true
                )
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val request = request(localSong)
        val fast = stages.readFast(request)
        val background = stages.readBackground(request, fast)
        assertEquals(listOf(false), includesEmbedded)
        assertEquals("sidecar", background.lyrics.single().text)
    }

    @Test
    fun `failed downloaded backfill keeps indexed sidecar`() = runTest {
        val sources = object : FakeNowPlayingLyricsSources() {
            override fun hasManagedDownload(song: SongItem) = true
            override fun fastDownloaded(context: Context, song: SongItem) =
                ManagedDownloadStorage.DownloadedLyricsBundle(
                    lyric = "[00:01.00]indexed", translatedLyric = null, romanizedLyric = null,
                    hasOriginalSidecar = true
                )
            override fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle {
                error("provider unavailable")
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val request = request(localSong)
        val fast = stages.readFast(request)
        val background = stages.readBackground(request, fast)
        assertEquals("indexed", background.lyrics.single().text)
    }

    private fun request(song: SongItem) = NowPlayingLyricsLoadRequest(
        context, song, null, true, LyricSourcePreference.Automatic, null
    )
}
