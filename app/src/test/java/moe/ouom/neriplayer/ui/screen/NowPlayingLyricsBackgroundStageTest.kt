package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.hasDisplayableContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class NowPlayingLyricsBackgroundStageTest {
    private val context = Mockito.mock(Context::class.java)
    private val remote = SongItem(7L, "Remote", "Artist", "Album", 1L, 60_000L, null)

    @Test
    fun `refreshed netease word timing confirms source before reusing romanization`() = runTest {
        var sourceConfirmed = false
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun neteaseOriginal(songId: Long) = "[1000,1000](1000,1000,0)fresh original"
            override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> {
                sourceConfirmed = true
                return listOf(LyricEntry("fresh original", 1_000, 2_000, listOf(WordTiming(1_000, 2_000))))
            }
            override suspend fun onlineRomanized(song: SongItem) = listOf(
                LyricEntry(if (sourceConfirmed) "current romaji" else "old amll romaji", 1_000, 2_000)
            )
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val input = request(remote.copy(album = "Netease"))

        val loaded = stages.readBackground(input, stages.readFast(input))

        assertEquals(listOf("fresh original"), loaded.lyrics.map { it.text })
        assertEquals(listOf("current romaji"), loaded.phoneticLyrics.map { it.text })
        assertTrue(sourceConfirmed)
    }

    @Test
    fun `automatic word timed source supplies its romanization before netease fallback`() = runTest {
        var romanizedReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun neteaseOriginal(songId: Long) = "[00:01.00]netease original"
            override suspend fun neteaseRomanized(songId: Long): String {
                romanizedReads++
                return "[00:01.00]netease romaji"
            }
            override suspend fun onlineOriginal(song: SongItem) = listOf(
                LyricEntry("amll original", 1_000, 2_000, listOf(WordTiming(1_000, 2_000)))
            )
            override suspend fun onlineRomanized(song: SongItem) = listOf(LyricEntry("amll romaji", 1_000, 2_000))
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val input = request(remote.copy(album = "Netease"))

        val loaded = stages.readBackground(input, stages.readFast(input))

        assertEquals(listOf("amll original"), loaded.lyrics.map { it.text })
        assertEquals(listOf("amll romaji"), loaded.phoneticLyrics.map { it.text })
        assertEquals(0, romanizedReads)
        assertTrue(loaded.rawPhoneticLyrics.isNullOrEmpty())
    }

    @Test
    fun `automatic source preserves embedded romanization before netease prefetch`() = runTest {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Hello</span>
            <span ttm:role="x-roman">Halo</span></p></div></body></tt>"""
        var romanizedReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun neteaseRomanized(songId: Long): String {
                romanizedReads++
                return "[00:01.00]network romaji"
            }
            override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = error("existing romanization must win")
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val input = request(remote.copy(album = "Netease", matchedLyric = raw), preferWordTimed = false)

        val loaded = stages.readBackground(input, stages.readFast(input))

        assertEquals(listOf("Halo"), loaded.phoneticLyrics.map { it.text })
        assertEquals(0, romanizedReads)
        assertTrue(loaded.rawPhoneticLyrics.isNullOrEmpty())
    }

    @Test
    fun `preferred background hit fills absent tracks and overlays partial edits before returning`() = runTest {
        var preferredReads = 0
        val preferred = PreferredLyricSourceResult(listOf(LyricEntry("preferred original", 1000, 2000)),
            listOf(LyricEntry("preferred translation", 1000, 2000)),
            listOf(LyricEntry("preferred romanized", 1000, 2000)), LyricSourcePreference.Kugou)
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult {
                preferredReads++
                return preferred
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val base = remote.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
        for (edited in listOf(base.copy(matchedLyric = "[00:01.00]user original"),
            base.copy(matchedTranslatedLyric = "[00:01.00]user translation"),
            base.copy(matchedRomanizedLyric = "[00:01.00]user romanized"), base.copy(matchedLyric = ""))) {
            val input = request(edited, source = LyricSourcePreference.Kugou)
            val loaded = stages.readBackground(input, stages.readFast(input))
            assertEquals(edited.matchedLyric?.substringAfter(']') ?: "preferred original", loaded.lyrics.firstOrNull()?.text.orEmpty())
            assertEquals(edited.matchedTranslatedLyric?.substringAfter(']') ?: "preferred translation", loaded.translatedLyrics.firstOrNull()?.text.orEmpty())
            assertEquals(edited.matchedRomanizedLyric?.substringAfter(']') ?: "preferred romanized", loaded.phoneticLyrics.firstOrNull()?.text.orEmpty())
        }
        assertEquals(4, preferredReads)
    }

    @Test
    fun `background sources cannot replace confirmed variants and unspecified tracks still use local fallback`() = runTest {
        val local = remote.copy(album = "__local_files__", mediaUri = "content://media/audio/7")
        for (managed in listOf(false, true)) {
            val sources = object : FakeNowPlayingLyricsSources() {
                override fun hasManagedDownload(song: SongItem) = managed
                override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean) =
                    LocalLyricsScanMetadata("[00:01.00]source original", "[00:01.00]source translation", "[00:01.00]source romanized")
                override fun fastDownloaded(context: Context, song: SongItem) = ManagedDownloadStorage.DownloadedLyricsBundle(
                    "[00:01.00]source original", "[00:01.00]source translation", "[00:01.00]source romanized",
                    hasOriginalSidecar = true, hasTranslatedSidecar = true, hasRomanizedSidecar = true)
                override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult =
                    error("confirmed user edit must bypass preferred replacement")
                override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> = error("local fallback must suffice")
                override suspend fun onlineTranslated(song: SongItem): List<LyricEntry> = error("local fallback must suffice")
                override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = error("local fallback must suffice")
            }
            val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
            val base = local.copy(lyricSyncEdited = true, lyricSyncRevision = 20)
            for (edited in listOf(
                base.copy(matchedLyric = "[00:01.00]user original", matchedTranslatedLyric = "[00:01.00]user translation",
                    matchedRomanizedLyric = "[00:01.00]user romanized"),
                base.copy(matchedTranslatedLyric = "[00:01.00]user translation"),
                base.copy(matchedRomanizedLyric = "[00:01.00]user romanized"),
                base.copy(matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = "")
            )) {
                val input = request(edited, source = LyricSourcePreference.Kugou)
                val loaded = stages.readBackground(input, stages.readFast(input))
                assertEquals(edited.matchedLyric?.substringAfter(']') ?: "source original", loaded.lyrics.firstOrNull()?.text.orEmpty())
                assertEquals(edited.matchedTranslatedLyric?.substringAfter(']') ?: "source translation", loaded.translatedLyrics.firstOrNull()?.text.orEmpty())
                assertEquals(edited.matchedRomanizedLyric?.substringAfter(']') ?: "source romanized", loaded.phoneticLyrics.firstOrNull()?.text.orEmpty())
            }
        }
    }

    @Test
    fun `preferred source replaces cached first frame without mixing fallback`() = runTest {
        var preferredReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult {
                preferredReads++
                return PreferredLyricSourceResult(
                    lyrics = listOf(LyricEntry("preferred", 1_000L, 2_000L)),
                    source = LyricSourcePreference.Kugou
                )
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val request = request(remote, source = LyricSourcePreference.Kugou)
        val fast = stages.readFast(request)
        val background = stages.readBackground(request, fast)
        assertEquals(1, preferredReads)
        assertEquals("preferred", background.lyrics.single().text)
        assertEquals(LyricSourcePreference.Kugou, background.preferredSource)
    }

    @Test
    fun `netease fallback reads only missing original and phonetic variants`() = runTest {
        var originalReads = 0
        var romanizedReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun neteaseOriginal(songId: Long): String {
                originalReads++
                assertEquals(123L, songId)
                return "[00:01.00]netease"
            }
            override suspend fun neteaseRomanized(songId: Long): String {
                romanizedReads++
                return "[00:01.00]romanized"
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val request = request(remote.copy(matchedSongId = "123", matchedLyricSource = MusicPlatform.CLOUD_MUSIC), preferWordTimed = false)
        val fast = stages.readFast(request)
        val background = stages.readBackground(request, fast)
        assertEquals(1, originalReads)
        assertEquals(1, romanizedReads)
        assertEquals("netease", background.lyrics.single().text)
        assertEquals("romanized", background.phoneticLyrics.single().text)
        assertTrue(background.hasDisplayableContent())
    }

    @Test
    fun `stored original skips netease original but still permits missing romanization`() = runTest {
        var originalReads = 0
        var romanizedReads = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun neteaseOriginal(songId: Long): String {
                originalReads++
                return "unexpected"
            }
            override suspend fun neteaseRomanized(songId: Long): String {
                romanizedReads++
                return ""
            }
        }
        val stages = NowPlayingLyricsLoadStages(sources, StandardTestDispatcher(testScheduler))
        val request = request(remote.copy(matchedSongId = "123", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedLyric = "[00:01.00]stored"), preferWordTimed = false)
        val fast = stages.readFast(request)
        val background = stages.readBackground(request, fast)
        assertEquals(0, originalReads)
        assertEquals(1, romanizedReads)
        assertEquals("stored", background.lyrics.single().text)
        assertFalse(background.phoneticLyrics.isNotEmpty())
    }

    private fun request(
        song: SongItem,
        source: LyricSourcePreference = LyricSourcePreference.Automatic,
        preferWordTimed: Boolean = true
    ) = NowPlayingLyricsLoadRequest(context, song, null, preferWordTimed, source, null)
}
