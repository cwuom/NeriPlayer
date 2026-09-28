package moe.ouom.neriplayer.core.api.lyrics.amll

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlClient
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlLyrics
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlSearchResult
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.io.IOException

class AmllLyricsResolverTest {
    private val client = mock(AmllTtmlClient::class.java)
    private val first = candidate("first.ttml")
    private val second = candidate("second.ttml")
    private val wordLyrics = """
        <tt xmlns="http://www.w3.org/ns/ttml">
            <body><div><p begin="00:01.000" end="00:02.000">
                <span begin="00:01.000" end="00:01.500">爱</span>
                <span begin="00:01.500" end="00:02.000">你</span>
            </p></div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `search cancellation propagates without fetching candidates`() = runTest {
        val cancellation = CancellationException("search cancelled")
        `when`(client.searchLyrics("Signal", "Artist")).thenThrow(cancellation)

        val actual = runCatching { resolve() }.exceptionOrNull()

        assertCancellation(cancellation, actual)
        verify(client).searchLyrics("Signal", "Artist")
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `candidate cancellation propagates without fetching another candidate`() = runTest {
        val cancellation = CancellationException("candidate cancelled")
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first, second))
        `when`(client.getLyrics(first)).thenThrow(cancellation)

        val actual = runCatching { resolve() }.exceptionOrNull()

        assertCancellation(cancellation, actual)
        verify(client).searchLyrics("Signal", "Artist")
        verify(client).getLyrics(first)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `ordinary search failure returns no lyrics`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenAnswer { throw IOException("search unavailable") }

        assertNull(resolve())

        verify(client).searchLyrics("Signal", "Artist")
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `ordinary candidate failure continues to a usable candidate`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first, second))
        `when`(client.getLyrics(first)).thenAnswer { throw IOException("candidate unavailable") }
        `when`(client.getLyrics(second)).thenReturn(AmllTtmlLyrics(wordLyrics, second.file))

        val resolved = resolve()

        assertEquals(wordLyrics, resolved?.rawLyrics)
        assertEquals("爱你", resolved?.entries?.single()?.text)
        assertEquals(2, resolved?.entries?.single()?.words?.size)
        verify(client).searchLyrics("Signal", "Artist")
        verify(client).getLyrics(first)
        verify(client).getLyrics(second)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `unavailable candidate continues in search order and stops at the first usable lyric`() = runTest {
        val third = candidate("third.ttml")
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first, second, third))
        `when`(client.getLyrics(first)).thenReturn(null)
        `when`(client.getLyrics(second)).thenReturn(AmllTtmlLyrics(wordLyrics, second.file))

        assertEquals(wordLyrics, resolve()?.rawLyrics)

        val requests = inOrder(client)
        requests.verify(client).searchLyrics("Signal", "Artist")
        requests.verify(client).getLyrics(first)
        requests.verify(client).getLyrics(second)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `blank and line timed candidates are exhausted without returning lyrics`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first, second))
        `when`(client.getLyrics(first)).thenReturn(AmllTtmlLyrics(" ", first.file))
        `when`(client.getLyrics(second)).thenReturn(AmllTtmlLyrics("[00:01.00]爱你", second.file))

        assertNull(resolve())

        verify(client).searchLyrics("Signal", "Artist")
        verify(client).getLyrics(first)
        verify(client).getLyrics(second)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `lookup only fetches the first five search candidates`() = runTest {
        val results = (1..6).map { candidate("candidate-$it.ttml") }
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(results)
        results.take(5).forEach { result -> `when`(client.getLyrics(result)).thenReturn(null) }
        `when`(client.getLyrics(results.last())).thenReturn(AmllTtmlLyrics(wordLyrics, results.last().file))

        assertNull(resolve())

        verify(client).searchLyrics("Signal", "Artist")
        results.take(5).forEach { result -> verify(client).getLyrics(result) }
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `duration mismatch continues to a candidate whose last word matches the song`() = runTest {
        val fullLengthLyrics = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body><div>
                    <p begin="00:01.000" end="00:02.000">
                        <span begin="00:01.000" end="00:02.000">爱</span>
                    </p>
                    <p begin="02:59.000" end="03:00.000">
                        <span begin="02:59.000" end="03:00.000">你</span>
                    </p>
                </div></body>
            </tt>
        """.trimIndent()
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first, second))
        `when`(client.getLyrics(first)).thenReturn(AmllTtmlLyrics(wordLyrics, first.file))
        `when`(client.getLyrics(second)).thenReturn(AmllTtmlLyrics(fullLengthLyrics, second.file))

        val resolved = resolve(durationMs = 180_000L)

        assertEquals(fullLengthLyrics, resolved?.rawLyrics)
        assertEquals(listOf(2_000L, 180_000L), resolved?.entries?.map { it.endTimeMs })
        verify(client).searchLyrics("Signal", "Artist")
        verify(client).getLyrics(first)
        verify(client).getLyrics(second)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `unknown duration rejects candidates when duration matching is required`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first))
        `when`(client.getLyrics(first)).thenReturn(AmllTtmlLyrics(wordLyrics, first.file))

        assertNull(resolve(durationMs = 0L))
    }

    @Test
    fun `unknown duration accepts word timed candidates when matching is optional`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first))
        `when`(client.getLyrics(first)).thenReturn(AmllTtmlLyrics(wordLyrics, first.file))

        assertEquals(wordLyrics, resolve(durationMs = 0L, requireDurationMatch = false)?.rawLyrics)
    }

    @Test
    fun `song lookup uses the longest end time across overlapping word timed lines`() = runTest {
        val overlappingLyrics = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body><div>
                    <p begin="02:00.000" end="03:00.000">
                        <span begin="02:00.000" end="03:00.000">爱</span>
                    </p>
                    <p begin="02:10.000" end="02:20.000">
                        <span begin="02:10.000" end="02:20.000">你</span>
                    </p>
                </div></body>
            </tt>
        """.trimIndent()
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(listOf(first))
        `when`(client.getLyrics(first)).thenReturn(AmllTtmlLyrics(overlappingLyrics, first.file))

        val entries = AmllLyricsResolver.loadForSong(song(), client, requireDurationMatch = true)

        assertEquals(listOf("爱", "你"), entries.map { it.text })
        assertEquals(listOf(180_000L, 140_000L), entries.map { it.endTimeMs })
        assertTrue(entries.all { !it.words.isNullOrEmpty() })
        verify(client).searchLyrics("Signal", "Artist")
        verify(client).getLyrics(first)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `song lookup returns an empty list when no candidate exists`() = runTest {
        `when`(client.searchLyrics("Signal", "Artist")).thenReturn(emptyList())

        assertTrue(AmllLyricsResolver.loadForSong(song(), client, requireDurationMatch = true).isEmpty())

        verify(client).searchLyrics("Signal", "Artist")
        verifyNoMoreInteractions(client)
    }

    private fun song() = SongItem(
        id = 1L,
        name = "Signal",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 180_000L,
        coverUrl = null
    )

    private suspend fun resolve(
        durationMs: Long = 2_000L,
        requireDurationMatch: Boolean = true
    ) = AmllLyricsResolver.loadRawByMetadata(
        trackName = "Signal",
        artistName = "Artist",
        durationMs = durationMs,
        amllTtmlClient = client,
        requireDurationMatch = requireDurationMatch
    )

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue("cancellation must reach the caller", actual is CancellationException)
        assertSame(expected, generateSequence(actual) { it.cause }.last())
    }

    private fun candidate(file: String) = AmllTtmlSearchResult(
        file = file,
        title = "Signal",
        titles = listOf("Signal"),
        artist = "Artist",
        artists = listOf("Artist"),
        albums = emptyList(),
        ncmIds = emptyList(),
        qqIds = emptyList(),
        score = 100
    )
}
