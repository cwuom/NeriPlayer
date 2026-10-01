package moe.ouom.neriplayer.data.platform.netease.playlist

import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class NeteasePlaylistSyncRequestsTest {
    @Test
    fun `empty batches succeed without network calls`() {
        val client = mock(NeteaseClient::class.java)

        assertTrue(addNeteasePlaylistSongIdsBatch(client, 91L, emptyList()))
        assertEquals(emptySet<Long>(), fetchResolvableNeteaseSongIds(client, emptyList(), "empty"))
        assertEquals(emptySet<Long>(), fetchNeteaseLikedSongDetailSummaryByPages(client, emptyList()).ids)
        verifyNoInteractions(client)
    }

    @Test
    fun `playlist detail retries one logged in 301 and accepts a confirmed empty playlist`() {
        val client = loggedInClient()
        `when`(client.getPlaylistDetail(91L)).thenReturn(
            "{\"code\":301}",
            "{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}"
        )

        val snapshot = fetchNeteasePlaylistTrackSnapshot(client, 91L)

        assertTrue(snapshot.compareSucceeded)
        assertTrue(snapshot.trackIds.isEmpty())
        verify(client, times(2)).getPlaylistDetail(91L)
        verify(client).ensureWeapiSession()
    }

    @Test
    fun `a repeated playlist detail 301 stops after the single retry`() {
        val client = loggedInClient()
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":301}")

        val snapshot = fetchNeteasePlaylistTrackSnapshot(client, 91L)

        assertFalse(snapshot.compareSucceeded)
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, snapshot.message)
        verify(client, times(2)).getPlaylistDetail(91L)
        verify(client).ensureWeapiSession()
    }

    @Test
    fun `a logged out playlist detail 301 never retries`() {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":301}")

        assertFalse(fetchNeteasePlaylistTrackSnapshot(client, 91L).compareSucceeded)
        verify(client).getPlaylistDetail(91L)
        verify(client, never()).ensureWeapiSession()
    }

    @Test
    fun `failed and incomplete playlist responses cannot be compared as empty playlists`() {
        for (raw in listOf("invalid", "{\"code\":500}", "{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":2}}")) {
            val client = mock(NeteaseClient::class.java)
            `when`(client.getPlaylistDetail(91L)).thenReturn(raw)

            val snapshot = fetchNeteasePlaylistTrackSnapshot(client, 91L)

            assertFalse(snapshot.compareSucceeded)
            assertTrue(snapshot.trackIds.isEmpty())
            assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, snapshot.message)
        }
        val client = mock(NeteaseClient::class.java)
        `when`(client.getPlaylistDetail(91L)).thenThrow(IllegalStateException("offline"))
        assertFalse(fetchNeteasePlaylistTrackSnapshot(client, 91L).compareSucceeded)
        verify(client).getPlaylistDetail(91L)
    }

    @Test
    fun `liked playlist resolution retries only once and retains a failed result`() {
        val recovered = loggedInClient()
        `when`(recovered.getLikedPlaylistId(0L)).thenReturn("{\"code\":301}", "{\"code\":200,\"playlistId\":91}")
        assertEquals(91L, resolveLikedNeteasePlaylistId(recovered))
        verify(recovered, times(2)).getLikedPlaylistId(0L)
        verify(recovered).ensureWeapiSession()

        val rejected = loggedInClient()
        `when`(rejected.getLikedPlaylistId(0L)).thenReturn("{\"code\":301}")
        assertNull(resolveLikedNeteasePlaylistId(rejected))
        verify(rejected, times(2)).getLikedPlaylistId(0L)
        verify(rejected).ensureWeapiSession()
    }

    @Test
    fun `liked playlist resolution preserves direct success and logged out failures`() {
        val success = mock(NeteaseClient::class.java)
        `when`(success.getLikedPlaylistId(0L)).thenReturn("{\"code\":200,\"playlistId\":91}")
        assertEquals(91L, resolveLikedNeteasePlaylistId(success))
        verify(success).getLikedPlaylistId(0L)
        verify(success, never()).ensureWeapiSession()

        val loggedOut = mock(NeteaseClient::class.java)
        `when`(loggedOut.getLikedPlaylistId(0L)).thenReturn("{\"code\":301}")
        assertNull(resolveLikedNeteasePlaylistId(loggedOut))
        verify(loggedOut).getLikedPlaylistId(0L)
        verify(loggedOut, never()).ensureWeapiSession()
    }

    @Test
    fun `liked playlist network failures stay unknown and a failed preheat still permits one retry`() {
        val failedRequest = mock(NeteaseClient::class.java)
        `when`(failedRequest.getLikedPlaylistId(0L)).thenThrow(IllegalStateException("initial request failed"))
        assertNull(resolveLikedNeteasePlaylistId(failedRequest))
        verify(failedRequest).getLikedPlaylistId(0L)

        val failedRetry = loggedInClient()
        `when`(failedRetry.getLikedPlaylistId(0L)).thenReturn("{\"code\":301}").thenThrow(IllegalStateException("retry failed"))
        assertNull(resolveLikedNeteasePlaylistId(failedRetry))
        verify(failedRetry, times(2)).getLikedPlaylistId(0L)
        verify(failedRetry).ensureWeapiSession()

        val failedPreheat = loggedInClient()
        doThrow(IllegalStateException("preheat failed")).`when`(failedPreheat).ensureWeapiSession()
        `when`(failedPreheat.getLikedPlaylistId(0L)).thenReturn("{\"code\":301}", "{\"code\":200,\"playlistId\":91}")
        assertEquals(91L, resolveLikedNeteasePlaylistId(failedPreheat))
        verify(failedPreheat, times(2)).getLikedPlaylistId(0L)
        verify(failedPreheat).ensureWeapiSession()
    }

    @Test
    fun `playlist insertion retries one logged in 301 and returns the retry outcome`() {
        val ids = listOf(1L, 2L)
        val recovered = loggedInClient()
        `when`(recovered.addSongsToPlaylist(91L, ids)).thenReturn("{\"code\":301}", "{\"code\":200}")
        assertTrue(addNeteasePlaylistSongIdsBatch(recovered, 91L, ids))
        verify(recovered, times(2)).addSongsToPlaylist(91L, ids)
        verify(recovered).ensureWeapiSession()

        val rejected = loggedInClient()
        `when`(rejected.addSongsToPlaylist(91L, ids)).thenReturn("{\"code\":301}")
        assertFalse(addNeteasePlaylistSongIdsBatch(rejected, 91L, ids))
        verify(rejected, times(2)).addSongsToPlaylist(91L, ids)
        verify(rejected).ensureWeapiSession()
    }

    @Test
    fun `playlist insertion preserves direct success and nonretryable outcomes`() {
        val ids = listOf(1L)
        for ((raw, expected) in listOf("{\"code\":200}" to true, "{\"code\":500}" to false, "{\"code\":301}" to false, "invalid" to false)) {
            val client = mock(NeteaseClient::class.java)
            `when`(client.addSongsToPlaylist(91L, ids)).thenReturn(raw)

            assertEquals(expected, addNeteasePlaylistSongIdsBatch(client, 91L, ids))
            verify(client).addSongsToPlaylist(91L, ids)
            verify(client, never()).ensureWeapiSession()
        }
    }

    @Test
    fun `playlist insertion retries at most once through preheat and network errors`() {
        val ids = listOf(1L)
        val failedRetry = loggedInClient()
        `when`(failedRetry.addSongsToPlaylist(91L, ids)).thenReturn("{\"code\":301}").thenThrow(IllegalStateException("retry failed"))
        assertFalse(addNeteasePlaylistSongIdsBatch(failedRetry, 91L, ids))
        verify(failedRetry, times(2)).addSongsToPlaylist(91L, ids)
        verify(failedRetry).ensureWeapiSession()

        val failedPreheat = loggedInClient()
        doThrow(IllegalStateException("preheat failed")).`when`(failedPreheat).ensureWeapiSession()
        `when`(failedPreheat.addSongsToPlaylist(91L, ids)).thenReturn("{\"code\":301}", "{\"code\":200}")
        assertTrue(addNeteasePlaylistSongIdsBatch(failedPreheat, 91L, ids))
        verify(failedPreheat, times(2)).addSongsToPlaylist(91L, ids)
    }

    @Test
    fun `song resolution distinguishes failed lookup from a confirmed unsupported result`() {
        val client = mock(NeteaseClient::class.java)
        val ids = listOf(1L)
        `when`(client.getSongDetail(ids)).thenReturn("invalid", "{\"code\":200,\"songs\":[]}")

        assertNull(fetchResolvableNeteaseSongIds(client, ids, "failed"))
        assertEquals(emptySet<Long>(), fetchResolvableNeteaseSongIds(client, ids, "unsupported"))
    }

    @Test
    fun `song resolution stops after one retry when 301 persists`() {
        val client = loggedInClient()
        val ids = listOf(1L)
        `when`(client.getSongDetail(ids)).thenReturn("{\"code\":301}")

        assertNull(fetchResolvableNeteaseSongIds(client, ids, "retry"))
        verify(client, times(2)).getSongDetail(ids)
        verify(client).ensureWeapiSession()
    }

    @Test
    fun `song resolution preserves recovered ids and does not retry logged out requests`() {
        val ids = listOf(1L)
        val recovered = loggedInClient()
        `when`(recovered.getSongDetail(ids)).thenReturn("{\"code\":301}", "{\"code\":200,\"songs\":[{\"id\":1}]}")
        assertEquals(setOf(1L), fetchResolvableNeteaseSongIds(recovered, ids, "recovered"))
        verify(recovered, times(2)).getSongDetail(ids)
        verify(recovered).ensureWeapiSession()

        val loggedOut = mock(NeteaseClient::class.java)
        `when`(loggedOut.getSongDetail(ids)).thenReturn("{\"code\":301}")
        assertNull(fetchResolvableNeteaseSongIds(loggedOut, ids, "logged out"))
        verify(loggedOut).getSongDetail(ids)
        verify(loggedOut, never()).ensureWeapiSession()
    }

    @Test
    fun `song resolution network failures never confirm that an id is unsupported`() {
        val ids = listOf(1L)
        val failedRequest = mock(NeteaseClient::class.java)
        `when`(failedRequest.getSongDetail(ids)).thenThrow(IllegalStateException("initial request failed"))
        assertNull(fetchResolvableNeteaseSongIds(failedRequest, ids, "initial failure"))

        val failedRetry = loggedInClient()
        `when`(failedRetry.getSongDetail(ids)).thenReturn("{\"code\":301}").thenThrow(IllegalStateException("retry failed"))
        assertNull(fetchResolvableNeteaseSongIds(failedRetry, ids, "retry failure"))
        verify(failedRetry, times(2)).getSongDetail(ids)

        val failedPreheat = loggedInClient()
        doThrow(IllegalStateException("preheat failed")).`when`(failedPreheat).ensureWeapiSession()
        `when`(failedPreheat.getSongDetail(ids)).thenReturn("{\"code\":301}", "{\"code\":200,\"songs\":[{\"id\":1}]}")
        assertEquals(setOf(1L), fetchResolvableNeteaseSongIds(failedPreheat, ids, "preheat failure"))
        verify(failedPreheat, times(2)).getSongDetail(ids)
    }

    @Test
    fun `playlist snapshot retry failure remains incomparable while preheat failure still allows recovery`() {
        val failedRetry = loggedInClient()
        `when`(failedRetry.getPlaylistDetail(91L)).thenReturn("{\"code\":301}").thenThrow(IllegalStateException("retry failed"))
        assertFalse(fetchNeteasePlaylistTrackSnapshot(failedRetry, 91L).compareSucceeded)
        verify(failedRetry, times(2)).getPlaylistDetail(91L)
        verify(failedRetry).ensureWeapiSession()

        val failedPreheat = loggedInClient()
        doThrow(IllegalStateException("preheat failed")).`when`(failedPreheat).ensureWeapiSession()
        `when`(failedPreheat.getPlaylistDetail(91L)).thenReturn("{\"code\":301}", "{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}")
        assertTrue(fetchNeteasePlaylistTrackSnapshot(failedPreheat, 91L).compareSucceeded)
        verify(failedPreheat, times(2)).getPlaylistDetail(91L)
    }

    @Test
    fun `candidate validation retains unknown rows but filters confirmed unsupported ids`() {
        val client = mock(NeteaseClient::class.java)
        val songs = listOf(song(2L), song(1L))
        val summary = buildLocalNeteaseCandidates(songs)
        `when`(client.getSongDetail(listOf(2L, 1L))).thenReturn("invalid", "{\"code\":200,\"songs\":[{\"id\":1}]}")

        val unknown = validateNeteaseSyncCandidates(client, summary)
        assertEquals(songs, unknown.candidates.map { it.song })
        assertEquals(0, unknown.skippedUnsupported)

        val confirmed = validateNeteaseSyncCandidates(client, summary)
        assertEquals(listOf(songs.last()), confirmed.candidates.map { it.song })
        assertEquals(1, confirmed.skippedUnsupported)
        assertEquals(1, confirmed.supportedSongs)
    }

    @Test
    fun `paged detail failure retains the successful pages and requested track identities`() {
        val client = mock(NeteaseClient::class.java)
        val firstPage = (1L..300L).toList()
        `when`(client.getSongDetail(firstPage)).thenThrow(IllegalStateException("first page failed"))
        `when`(client.getSongDetail(listOf(301L))).thenReturn("{\"code\":200,\"songs\":[{\"id\":301}]}")

        val summary = fetchNeteaseLikedSongDetailSummaryByPages(client, (1L..301L).toList())

        assertEquals(setOf(301L), summary.ids)
        verify(client).getSongDetail(firstPage)
        verify(client).getSongDetail(listOf(301L))
    }

    @Test
    fun `completed insertion requires no remote reconciliation and preserves its result`() {
        val client = mock(NeteaseClient::class.java)
        val result = NeteasePlaylistBatchAddResult(linkedSetOf(2L, 1L), emptySet())

        val reconciled = reconcileNeteasePlaylistAddResult(client, 91L, result)

        assertEquals(result, reconciled)
        assertEquals(listOf(2L, 1L), reconciled.addedIds.toList())
        verifyNoInteractions(client)
    }

    @Test
    fun `failed reconciliation never conceals failed identities or mutates the insertion result`() {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getPlaylistDetail(91L)).thenReturn("invalid")
        val result = NeteasePlaylistBatchAddResult(linkedSetOf(2L), linkedSetOf(3L, 1L))

        val reconciled = reconcileNeteasePlaylistAddResult(client, 91L, result)

        assertEquals(result, reconciled)
        assertEquals(listOf(3L, 1L), reconciled.failedIds.toList())
        assertEquals(linkedSetOf(3L, 1L), result.failedIds)
    }

    private fun loggedInClient(): NeteaseClient {
        return mock(NeteaseClient::class.java).also { `when`(it.hasLogin()).thenReturn(true) }
    }

    private fun song(id: Long): SongItem {
        return SongItem(id, "song-$id", "artist", "NeteaseAlbum", 7L, 1_000L, null, channelId = "netease", audioId = id.toString())
    }
}
