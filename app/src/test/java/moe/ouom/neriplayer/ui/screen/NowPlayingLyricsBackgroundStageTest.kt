package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class NowPlayingLyricsBackgroundStageTest {
    private val context = Mockito.mock(Context::class.java)
    private val remote = SongItem(7L, "Remote", "Artist", "Album", 1L, 60_000L, null)

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
        val request = request(remote.copy(matchedSongId = "123"), preferWordTimed = false)
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
        val request = request(remote.copy(matchedSongId = "123", matchedLyric = "[00:01.00]stored"), preferWordTimed = false)
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
