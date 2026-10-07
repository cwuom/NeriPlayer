package moe.ouom.neriplayer.platform.lyrics.repository

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibResult
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricFormat
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLyrics
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.lyrics.parser.toEditableLyricsText
import moe.ouom.neriplayer.platform.search.api.NativeLyricSearchApi
import moe.ouom.neriplayer.platform.search.api.SearchApi
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class EditableLyricsMatcherSourceTest {
    private val kugou = mock(KugouLyricsRepository::class.java)
    private val lrcLib = mock(LrcLibLyricsRepository::class.java)
    private val amll = mock(AmllLyricsRepository::class.java)
    private val youtube = mock(YouTubeMusicClient::class.java)
    private val request = EditableLyricMatchRequest(
        keyword = "Signal Artist",
        trackName = "Signal",
        artistName = "Artist",
        durationMs = 180_000L,
        sources = setOf(EditableLyricMatchSource.CLOUD_MUSIC, EditableLyricMatchSource.QQ_MUSIC)
    )
    private val everySource = EditableLyricMatchRequest(
        keyword = "Signal 擱淺",
        trackName = "Signal",
        artistName = "Artist",
        sources = EditableLyricMatchSource.entries.toSet()
    )

    @Test
    fun `high confidence lookup ignores blank keywords and unselected sources`() = runTest {
        val matcher = matcher()

        assertTrue(
            matcher.matchHighConfidenceLyricsForSource(
                request.copy(keyword = "  "),
                EditableLyricMatchSource.CLOUD_MUSIC
            ).isEmpty()
        )
        assertTrue(
            matcher.matchHighConfidenceLyricsForSource(
                request.copy(sources = emptySet()),
                EditableLyricMatchSource.CLOUD_MUSIC
            ).isEmpty()
        )
        assertTrue(matcher.matchHighConfidenceLyricsForSource(request, EditableLyricMatchSource.KUGOU).isEmpty())
        verifyNoInteractions(kugou, lrcLib, amll, youtube)
    }

    @Test
    fun `high confidence cloud lookup keeps only reliable matches`() = runTest {
        val searches = mutableListOf<String>()
        val detailIds = mutableListOf<String>()
        val cloud = object : SearchApi {
            override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
                searches += keyword
                return listOf(
                    searchInfo("c-lrc"),
                    searchInfo("c-plain", platform = MusicPlatform.QQ_MUSIC),
                    searchInfo("c-live", title = "Signal (Live)")
                )
            }

            override suspend fun getSongInfo(id: String): SongDetails {
                detailIds += id
                return when (id) {
                    "c-lrc" -> songDetails(id, lyric = "[00:01.00]Hello\n[00:03.00]world")
                    "c-plain" -> songDetails(id, lyric = "Hello\nworld")
                    else -> songDetails(id, title = "Signal (Live)", lyric = "[00:01.00]Live\n[00:03.00]world")
                }
            }
        }

        val matches = matcher(cloud = cloud)
            .matchHighConfidenceLyricsForSource(request, EditableLyricMatchSource.CLOUD_MUSIC)

        assertEquals(listOf("c-lrc", "c-plain"), matches.map { it.candidate.id })
        assertEquals(listOf(EditableLyricFormat.LRC, EditableLyricFormat.PLAIN), matches.map { it.candidate.format })
        assertEquals(listOf(4, 3), matches.map { it.candidate.sourceScore })
        assertTrue(matches.all { it.confidence == EditableLyricMatchConfidence.HIGH })
        assertTrue(matches.all { it.candidate.durationMs == 180_000L })
        assertEquals(listOf("Signal Artist", "Signal"), searches)
        assertEquals(listOf("c-lrc", "c-plain", "c-live"), detailIds)
    }

    @Test
    fun `search api candidates without usable lyric details are skipped`() = runTest {
        val detailIds = mutableListOf<String>()
        val qq = object : SearchApi {
            override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> = listOf(
                searchInfo("q-null", platform = MusicPlatform.QQ_MUSIC),
                searchInfo("q-blank", platform = MusicPlatform.QQ_MUSIC),
                searchInfo("", platform = MusicPlatform.QQ_MUSIC)
            )

            override suspend fun getSongInfo(id: String): SongDetails {
                detailIds += id
                return when (id) {
                    "q-null" -> songDetails(id, lyric = null)
                    "q-blank" -> songDetails(id, lyric = "   ")
                    else -> throw IllegalStateException("detail unavailable")
                }
            }
        }

        val matches = matcher(qq = qq).matchHighConfidenceLyricsForSource(request, EditableLyricMatchSource.QQ_MUSIC)

        assertTrue(matches.isEmpty())
        assertEquals(setOf("q-null", "q-blank", ""), detailIds.toSet())
    }

    @Test
    fun `high confidence lookup dispatches to every other lyric source`() = runTest {
        val kugouSong = KugouSongSearchResult("k1", "hash", "Signal", "Artist", "Album", 200_000L)
        `when`(kugou.searchSongs(anyString(), eq(8))).thenReturn(listOf(kugouSong))
        `when`(kugou.getBestLyricPayload(kugouSong)).thenReturn(
            KugouLyricsPayload("[1000,1000](1000,500,0)Hello(1500,500,0) world", "[00:01.00]你好")
        )
        `when`(lrcLib.searchLyricsCandidates(anyString())).thenReturn(
            listOf(
                LrcLibResult(
                    syncedLyrics = "[00:01.00]Hello\n[00:03.00]world",
                    plainLyrics = null,
                    trackName = "Signal",
                    artistName = "Artist",
                    durationSeconds = 181L
                )
            )
        )
        val timed = amllResult("timed.ttml")
        val empty = amllResult("empty.ttml")
        val missing = amllResult("missing.ttml")
        `when`(amll.searchLyrics(anyString(), anyString(), anyString())).thenReturn(listOf(timed, empty, missing))
        `when`(amll.getLyrics(timed)).thenReturn(
            AmllTtmlLyrics(
                lyrics = "[0,9000](0,9000,0)First words\n" +
                    "[1000,1000](1000,1000,0)Second words\n" +
                    "[3000,8000](3000,8000,0)Third words",
                file = "timed.ttml"
            )
        )
        `when`(amll.getLyrics(empty)).thenReturn(AmllTtmlLyrics(lyrics = "", file = "empty.ttml"))
        `when`(amll.getLyrics(missing)).thenReturn(null)
        val video = YouTubeMusicSearchResult(
            "yt1", "Signal", "Artist", "", "", "", "3:00", 180_000L, YouTubeMusicSearchResultType.Song
        )
        `when`(youtube.search(anyString(), eq(5), eqValue(YouTubeMusicSearchFilter.Song))).thenReturn(listOf(video))
        `when`(youtube.getLyrics("yt1")).thenReturn(YouTubeMusicLyrics("Hello\nworld"))
        val qq = object : NativeLyricSearchApi {
            override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> =
                listOf(searchInfo("q1", duration = "1:02:03", platform = MusicPlatform.QQ_MUSIC))

            override suspend fun getSongInfo(id: String): SongDetails = throw AssertionError("QQ uses native lyrics")

            override suspend fun getNativeSongInfo(id: String): SongDetails = songDetails(
                id,
                lyric = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
                    """<p begin="00:01.000" end="00:02.000">Hello world</p></div></body></tt>"""
            )
        }
        val matcher = matcher(qq = qq)

        suspend fun only(source: EditableLyricMatchSource): EditableLyricMatchCandidate {
            val match = matcher.matchHighConfidenceLyricsForSource(everySource, source).single()
            assertEquals(EditableLyricMatchConfidence.HIGH, match.confidence)
            assertEquals(source, match.candidate.source)
            return match.candidate
        }

        val kugouMatch = only(EditableLyricMatchSource.KUGOU)
        val qqMatch = only(EditableLyricMatchSource.QQ_MUSIC)
        val lrcLibMatch = only(EditableLyricMatchSource.LRCLIB)
        val amllMatch = only(EditableLyricMatchSource.AMLL_TTML)
        val youtubeMatch = only(EditableLyricMatchSource.YOUTUBE_MUSIC)

        assertEquals(EditableLyricFormat.YRC, kugouMatch.format)
        assertEquals("[00:01.00]你好", kugouMatch.translatedLyrics)
        assertEquals(EditableLyricFormat.TTML, qqMatch.format)
        assertEquals(3_723_000L, qqMatch.durationMs)
        assertEquals("Signal:Artist:181", lrcLibMatch.id)
        assertEquals(181_000L, lrcLibMatch.durationMs)
        assertEquals("timed.ttml", amllMatch.id)
        assertEquals(11_000L, amllMatch.durationMs)
        assertEquals("Album", amllMatch.album)
        assertEquals(5, amllMatch.sourceScore)
        assertEquals(convertPlainLyricsToEntries("Hello\nworld", 180_000L).toEditableLyricsText(), youtubeMatch.lyrics)
        assertEquals(EditableLyricFormat.LRC, youtubeMatch.format)
        assertNull(youtubeMatch.album)
        verify(kugou).searchSongs("Signal 搁浅", 8)
        verify(lrcLib).searchLyricsCandidates("Signal 擱淺")
        verify(amll).searchLyrics("Signal 擱淺", "Signal", "Artist")
        verify(youtube).search("Signal 擱淺", 5, YouTubeMusicSearchFilter.Song)
    }

    private fun matcher(
        cloud: SearchApi = unexpectedApi("cloud"),
        qq: SearchApi = unexpectedApi("QQ")
    ) = EditableLyricsMatcher(
        cloudMusicSearchApi = cloud,
        qqMusicSearchApi = qq,
        kugouLyricsClient = kugou,
        lrcLibClient = lrcLib,
        amllTtmlClient = amll,
        youtubeMusicClient = youtube
    )

    /** Mockito's `eq` returns null for objects, which Kotlin rejects for non-null parameters. */
    private fun <T : Any> eqValue(value: T): T {
        eq(value)
        return value
    }

    private fun unexpectedApi(name: String) = object : SearchApi {
        override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> =
            throw AssertionError("$name search is outside this test")

        override suspend fun getSongInfo(id: String): SongDetails =
            throw AssertionError("$name details are outside this test")
    }

    private fun searchInfo(
        id: String,
        title: String = "Signal",
        duration: String = "3:00",
        platform: MusicPlatform = MusicPlatform.CLOUD_MUSIC
    ) = SongSearchInfo(id, title, "Artist", duration, platform, null, null)

    private fun songDetails(id: String, title: String = "Signal", lyric: String?) =
        SongDetails(id, title, "Artist", "Album", null, lyric)

    private fun amllResult(file: String) = AmllTtmlSearchResult(
        file = file,
        title = "Signal",
        titles = listOf("Signal"),
        artist = "Artist",
        artists = listOf("Artist"),
        albums = listOf("Album"),
        ncmIds = emptyList(),
        qqIds = emptyList(),
        score = 100
    )
}
