package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.core.api.search.SearchApi
import moe.ouom.neriplayer.core.api.search.SongDetails
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class EditableLyricsMatcherTest {
    private val kugou = mock(KugouLyricsClient::class.java)
    private val lrcLib = mock(LrcLibClient::class.java)
    private val amll = mock(AmllTtmlClient::class.java)
    private val youtube = mock(YouTubeMusicClient::class.java)
    private val request = EditableLyricMatchRequest(
        keyword = " Signal ", trackName = " Signal ", artistName = " Artist ",
        durationMs = 180_000L, sources = setOf(EditableLyricMatchSource.CLOUD_MUSIC)
    )

    @Test
    fun `selected source resolves details once across repeated queries`() = runTest {
        val queries = mutableListOf<String>()
        val details = mutableListOf<String>()
        val api = object : SearchApi {
            override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
                queries += keyword
                assertEquals(1, page)
                return listOf(SongSearchInfo("id", "Signal", "Artist", "3:00", MusicPlatform.CLOUD_MUSIC, null, null))
            }
            override suspend fun getSongInfo(id: String): SongDetails {
                details += id
                return SongDetails(id, "Signal", "Artist", "Album", null, "[00:01.00]hello\n[00:02.00]world")
            }
        }
        val result = matcher(api).matchLyrics(request).single()
        assertEquals(EditableLyricMatchSource.CLOUD_MUSIC, result.candidate.source)
        assertEquals(listOf("id"), details)
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.all { it == it.trim() })
        verifyNoInteractions(kugou, lrcLib, amll, youtube)
    }

    @Test
    fun `empty request never invokes a source`() = runTest {
        var calls = 0
        val matcher = matcher(failingApi {
            calls += 1
            throw AssertionError("unexpected lookup")
        })
        assertTrue(matcher.matchLyrics(request.copy(keyword = " ")).isEmpty())
        assertTrue(matcher.matchLyrics(request.copy(sources = emptySet())).isEmpty())
        assertEquals(0, calls)
        verifyNoInteractions(kugou, lrcLib, amll, youtube)
    }

    @Test
    fun `QQ matching fetches native lyrics without AMLL replacement`() = runTest {
        val qq = mock(QQMusicSearchApi::class.java)
        `when`(qq.search(anyString(), eq(1))).thenReturn(listOf(
            SongSearchInfo("id", "Signal", "Artist", "3:00", MusicPlatform.QQ_MUSIC, null, null)
        ))
        `when`(qq.getNativeSongInfo("id")).thenReturn(
            SongDetails("id", "Signal", "Artist", "Album", null, "[00:01.00]hello\n[00:02.00]world")
        )
        val result = matcher(
            cloud = failingApi { throw AssertionError("cloud source is not selected") }, qq = qq
        ).matchLyrics(request.copy(sources = setOf(EditableLyricMatchSource.QQ_MUSIC))).single()
        assertEquals(EditableLyricMatchSource.QQ_MUSIC, result.candidate.source)
        verify(qq).getNativeSongInfo("id")
        verify(qq, never()).getSongInfo(anyString())
        verifyNoInteractions(kugou, lrcLib, amll, youtube)
    }

    @Test
    fun `source cancellation propagates`() {
        val failure = CancellationException("cancelled")
        val actual = assertThrows(CancellationException::class.java) {
            runTest { matcher(failingApi { throw failure }).matchLyrics(request) }
        }
        assertSame(failure, actual)
    }

    @Test
    fun `ordinary source errors yield no match`() = runTest {
        assertTrue(matcher(failingApi { throw IllegalStateException("unavailable") }).matchLyrics(request).isEmpty())
    }

    private fun matcher(
        cloud: SearchApi,
        qq: SearchApi = failingApi { throw AssertionError("QQ source is not selected") }
    ) = EditableLyricsMatcher(
        cloudMusicSearchApi = cloud,
        qqMusicSearchApi = qq,
        kugouLyricsClient = kugou,
        lrcLibClient = lrcLib,
        amllTtmlClient = amll,
        youtubeMusicClient = youtube
    )

    private fun failingApi(failure: () -> Nothing) = object : SearchApi {
        override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> = failure()
        override suspend fun getSongInfo(id: String): SongDetails = failure()
    }
}
