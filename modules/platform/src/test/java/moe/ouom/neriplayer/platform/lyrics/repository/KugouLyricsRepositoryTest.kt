package moe.ouom.neriplayer.platform.lyrics.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.lyrics.api.client.KugouLyricsClient
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class KugouLyricsRepositoryTest {
    private val api = mock(KugouLyricsClient::class.java)
    private val repository = KugouLyricsRepository(api)
    private val song = KugouSongSearchResult("song", "hash", "Signal", "Artist", null, 180_000)
    private val near = KugouLyricCandidate("near", "key", 180_000, 90)
    private val far = KugouLyricCandidate("far", "key", 220_000, 90)
    private val lower = KugouLyricCandidate("lower", "key", 180_000, 80)

    @Test
    fun `all ranked word timed candidates are attempted before line timed fallback`() = runTest {
        `when`(api.searchLyricCandidates(song)).thenReturn(listOf(lower, far, near))
        `when`(api.downloadLrcLyric(near)).thenReturn(KugouLyricsPayload("[00:01.00]line"))

        assertEquals("[00:01.00]line", repository.getBestLyrics(song))

        val requests = inOrder(api)
        requests.verify(api).searchLyricCandidates(song)
        requests.verify(api).downloadKrcLyric(near)
        requests.verify(api).downloadKrcLyric(far)
        requests.verify(api).downloadKrcLyric(lower)
        requests.verify(api).downloadLrcLyric(near)
        verifyNoMoreInteractions(api)
    }

    @Test
    fun `word timed success preserves translation and avoids line timed requests`() = runTest {
        val payload = KugouLyricsPayload("[1000,300](1000,300,0)line", "[00:01.00]translated")
        `when`(api.searchLyricCandidates(song)).thenReturn(listOf(near, far))
        `when`(api.downloadKrcLyric(near)).thenReturn(payload)

        assertEquals(payload, repository.getBestLyricPayload(song))

        verify(api).searchLyricCandidates(song)
        verify(api).downloadKrcLyric(near)
        verifyNoMoreInteractions(api)
    }

    @Test
    fun `download cancellation stops fallback and reaches caller`() = runTest {
        val cancellation = CancellationException("cancelled")
        `when`(api.searchLyricCandidates(song)).thenReturn(listOf(near, far))
        `when`(api.downloadKrcLyric(near)).thenThrow(cancellation)

        val actual = runCatching { repository.getBestLyricPayload(song) }.exceptionOrNull()

        assertSame(cancellation, generateSequence(actual) { it.cause }.last())
        verify(api).searchLyricCandidates(song)
        verify(api).downloadKrcLyric(near)
        verifyNoMoreInteractions(api)
    }
}
