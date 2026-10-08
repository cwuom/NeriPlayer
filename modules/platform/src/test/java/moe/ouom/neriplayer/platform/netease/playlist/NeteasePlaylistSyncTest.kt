package moe.ouom.neriplayer.platform.netease.playlist

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
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
import java.io.IOException

class NeteasePlaylistSyncTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }

    @Test
    fun `plan results request only the message for the actual outcome`() = runTest {
        val messages = mutableListOf<NeteasePlaylistSyncMessage>()
        val sync = NeteasePlaylistSync(noPause) { message ->
            messages += message
            message.name
        }
        val client = mock(NeteaseClient::class.java)

        val invalidTarget = sync.prepareNeteasePlaylistSyncPlan(client, 0L, listOf(song(1L)))
        assertFalse(invalidTarget.compareSucceeded)
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, invalidTarget.message)
        assertTrue(messages.isEmpty())

        val empty = sync.prepareNeteaseLikeSyncPlan(client, emptyList())
        assertEquals("EMPTY_SONGS", empty.message)
        val local = song(1L).copy(channelId = "local", audioId = null, album = "local")
        val unsupported = sync.prepareNeteaseLikeSyncPlan(client, listOf(local))
        assertEquals("NO_SUPPORTED_SONGS", unsupported.message)
        assertEquals(1, unsupported.skippedUnsupported)
        verifyNoInteractions(client)

        val loggedOut = sync.prepareNeteaseLikeSyncPlan(client, listOf(song(1L)))
        assertFalse(loggedOut.compareSucceeded)
        assertEquals("LOGIN_REQUIRED", loggedOut.message)
        assertEquals(
            listOf(NeteasePlaylistSyncMessage.EMPTY_SONGS, NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS, NeteasePlaylistSyncMessage.LOGIN_REQUIRED),
            messages
        )
    }

    @Test
    fun `plan comparison filters remote ids and fingerprints without changing pending order`() {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":99}],\"trackCount\":1}}")
        `when`(client.getSongDetail(listOf(99L))).thenReturn(songDetail(99L, "original"))
        val fingerprintMatch = song(1L, "edited").copy(originalName = "original")
        val idMatch = song(99L, "different title")
        val firstPending = song(3L, "first pending")
        val secondPending = song(2L, "second pending")
        val validated = NeteaseCandidateValidationResult(
            supportedSongs = 4,
            skippedUnsupported = 2,
            skippedExisting = 1,
            candidates = listOf(fingerprintMatch, idMatch, firstPending, secondPending).map {
                NeteaseResolvedCandidate(it, it.id)
            }
        )

        val plan = buildNeteasePlaylistSyncPlan(client, 91L, 7, validated) {
            error("pending candidates must not request an all-synced message")
        }

        assertTrue(plan.compareSucceeded)
        assertEquals(listOf(firstPending, secondPending), plan.toLikeSyncPlan().pendingSongs)
        assertEquals(3, plan.skippedExisting)
        assertEquals(2, plan.skippedUnsupported)
        assertEquals(4, plan.supportedSongs)
        assertEquals(7, plan.totalSongs)
        assertNull(plan.message)
    }

    @Test
    fun `fully synced plan lazily resolves its message and allows a nullable host message`() {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1}],\"trackCount\":1}}")
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        val validated = NeteaseCandidateValidationResult(1, 0, 0, listOf(NeteaseResolvedCandidate(song(1L), 1L)))
        var messageCalls = 0

        val plan = buildNeteasePlaylistSyncPlan(client, 91L, 1, validated) {
            messageCalls += 1
            null
        }

        assertTrue(plan.compareSucceeded)
        assertTrue(plan.candidates.isEmpty())
        assertEquals(1, plan.skippedExisting)
        assertEquals(1, messageCalls)
        assertNull(plan.message)
    }

    @Test
    fun `failed remote comparison prevents uploads and preserves aggregate counts`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getPlaylistDetail(91L)).thenReturn("invalid")
        val remote = song(1L)
        val local = remote.copy(id = 2L, channelId = "local", audioId = null, album = "local")
        val songs = listOf(remote, remote.copy(name = "duplicate"), local)

        val result = NeteasePlaylistSync(noPause) { it.name }.syncSongsToNeteasePlaylist(client, 91L, songs)

        assertEquals(3, result.totalSongs)
        assertEquals(1, result.supportedSongs)
        assertEquals(1, result.skippedExisting)
        assertEquals(1, result.skippedUnsupported)
        assertEquals(0, result.added)
        assertEquals(0, result.failed)
        assertEquals(91L, result.targetPlaylistId)
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, result.message)
        verify(client, never()).addSongsToPlaylist(91L, listOf(1L))
    }

    @Test
    fun `remote membership recovers a failed insertion without marking it unsupported`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getPlaylistDetail(91L)).thenReturn(
            "{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}",
            "{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1}],\"trackCount\":1}}"
        )
        `when`(client.addSongsToPlaylist(91L, listOf(1L))).thenReturn("{\"code\":500}")

        val result = NeteasePlaylistSync(noPause) { it.name }.syncSongsToNeteasePlaylist(client, 91L, listOf(song(1L)))

        assertEquals(1, result.added)
        assertEquals(0, result.failed)
        assertEquals(0, result.skippedUnsupported)
        assertEquals(0, result.skippedExisting)
        verify(client).addSongsToPlaylist(91L, listOf(1L))
    }

    @Test
    fun `an unknown failed insertion remains failed after network errors`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"), "invalid")
        `when`(client.getPlaylistDetail(91L)).thenReturn(
            "{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}"
        ).thenThrow(IllegalStateException("reconciliation failed"))
        `when`(client.addSongsToPlaylist(91L, listOf(1L))).thenThrow(IllegalStateException("insertion failed"))
        val pauses = mutableListOf<Long>()

        val result = NeteasePlaylistSync(pauses::add) { it.name }.syncSongsToNeteasePlaylist(client, 91L, listOf(song(1L)))

        assertEquals(0, result.added)
        assertEquals(1, result.failed)
        assertEquals(0, result.skippedUnsupported)
        assertEquals(1, result.supportedSongs)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        assertEquals(emptyList<Long>(), result.rejectedSongIds)
        assertNull(result.rejectionMessage)
        verify(client, times(4)).addSongsToPlaylist(91L, listOf(1L))
    }

    @Test
    fun `songs rejected by netease are reported with the server reason`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L, 2L))).thenReturn(
            """{"code":200,"songs":[${songJson(1L, "one")},${songJson(2L, "two")}]}"""
        )
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}")
        `when`(client.addSongsToPlaylist(91L, listOf(1L, 2L))).thenReturn("{\"code\":524,\"message\":\"no copyright\"}")

        val result = NeteasePlaylistSync(noPause) { it.name }.syncSongsToNeteasePlaylist(client, 91L, listOf(song(1L), song(2L, "two")))

        assertEquals(0, result.added)
        assertEquals(2, result.failed)
        assertEquals(listOf(1L, 2L), result.rejectedSongIds)
        assertEquals("no copyright", result.rejectionMessage)
        verify(client).addSongsToPlaylist(91L, listOf(1L, 2L))
    }

    @Test
    fun `empty and already synced results retain counts messages and target identity`() = runTest {
        val client = mock(NeteaseClient::class.java)
        val sync = NeteasePlaylistSync(noPause) { it.name }

        val empty = sync.syncSongsToNeteasePlaylist(client, 91L, emptyList())
        assertEquals(0, empty.totalSongs)
        assertEquals(0, empty.supportedSongs)
        assertEquals(0, empty.added)
        assertEquals(0, empty.failed)
        assertEquals("EMPTY_SONGS", empty.message)
        assertEquals(91L, empty.targetPlaylistId)
        val invalidTarget = sync.syncSongsToNeteasePlaylist(client, 0L, emptyList())
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, invalidTarget.message)
        assertNull(invalidTarget.targetPlaylistId)
        verifyNoInteractions(client)

        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1}],\"trackCount\":1}}")
        val synced = sync.syncSongsToNeteasePlaylist(client, 91L, listOf(song(1L)))
        assertEquals(1, synced.totalSongs)
        assertEquals(1, synced.supportedSongs)
        assertEquals(1, synced.skippedExisting)
        assertEquals(0, synced.added)
        assertEquals(0, synced.failed)
        assertEquals("ALL_SYNCED", synced.message)
        verify(client, never()).addSongsToPlaylist(91L, listOf(1L))
    }

    @Test
    fun `remote playlist fetching requires login and retains only owned playlists after failed preheat`() = runTest {
        val sync = NeteasePlaylistSync(noPause) { it.name }
        val loggedOut = mock(NeteaseClient::class.java)
        val error = runCatching { sync.fetchNeteaseRemotePlaylists(loggedOut) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("LOGIN_REQUIRED", error?.message)
        verify(loggedOut, never()).getCurrentUserId()

        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getCurrentUserId()).thenReturn(7L)
        doThrow(IllegalStateException("preheat failed")).`when`(client).ensureWeapiSession()
        `when`(client.getUserPlaylists(7L, 0, 1000)).thenReturn(
            """{"code":200,"playlist":[{"id":91,"name":"owned","creator":{"userId":7}},{"id":92,"name":"subscribed","creator":{"userId":8}}]}"""
        )

        assertEquals(listOf(91L), sync.fetchNeteaseRemotePlaylists(client).map { it.id })
        verify(client).ensureWeapiSession()
        verify(client).getUserPlaylists(7L, 0, 1000)
    }

    @Test
    fun `remote playlist fetching propagates an invalid response`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getCurrentUserId()).thenReturn(7L)
        `when`(client.getUserPlaylists(7L, 0, 1000)).thenReturn("invalid")

        assertTrue(runCatching { NeteasePlaylistSync(noPause) { it.name }.fetchNeteaseRemotePlaylists(client) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `liked sync planning retains pending songs when preheat fails`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        doThrow(IllegalStateException("preheat failed")).`when`(client).ensureWeapiSession()
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getLikedPlaylistId(0L)).thenReturn("{\"code\":200,\"playlistId\":91}")
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}")
        val songs = listOf(song(1L))

        val plan = NeteasePlaylistSync(noPause) { it.name }.prepareNeteaseLikeSyncPlan(client, songs)

        assertTrue(plan.compareSucceeded)
        assertEquals(songs, plan.pendingSongs)
        assertEquals(1, plan.supportedSongs)
        assertNull(plan.message)
        verify(client).getLikedPlaylistId(0L)
    }

    @Test
    fun `invalid liked playlist identity stops comparison after successful candidate validation`() = runTest {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getLikedPlaylistId(0L)).thenReturn("{\"code\":200,\"playlistId\":0}")

        val plan = NeteasePlaylistSync(noPause) { it.name }.prepareNeteaseLikeSyncPlan(client, listOf(song(1L)))

        assertFalse(plan.compareSucceeded)
        assertTrue(plan.pendingSongs.isEmpty())
        assertEquals(1, plan.supportedSongs)
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, plan.message)
    }

    @Test
    fun `both plans retain duplicate counts when every distinct candidate is confirmed unsupported`() = runTest {
        val first = song(1L)
        val local = first.copy(id = 2L, channelId = "local", audioId = null, album = "local")
        val songs = listOf(first, first.copy(name = "duplicate"), local)
        val sync = NeteasePlaylistSync(noPause) { it.name }

        for (liked in listOf(true, false)) {
            val client = mock(NeteaseClient::class.java)
            `when`(client.hasLogin()).thenReturn(true)
            `when`(client.getSongDetail(listOf(1L))).thenReturn("{\"code\":200,\"songs\":[]}")
            val plan = if (liked) {
                sync.prepareNeteaseLikeSyncPlan(client, songs)
            } else {
                sync.prepareNeteasePlaylistSyncPlan(client, 91L, songs)
            }

            assertFalse(plan.compareSucceeded)
            assertEquals(3, plan.totalSongs)
            assertEquals(0, plan.supportedSongs)
            assertEquals(2, plan.skippedUnsupported)
            assertEquals(1, plan.skippedExisting)
            assertEquals("NO_SUPPORTED_SONGS", plan.message)
            verify(client, never()).getLikedPlaylistId(0L)
            verify(client, never()).getPlaylistDetail(91L)
        }
    }

    @Test
    fun `target playlist planning filters unsupported rows before checking login`() = runTest {
        val client = mock(NeteaseClient::class.java)
        val sync = NeteasePlaylistSync(noPause) { it.name }
        val local = song(1L).copy(channelId = "local", audioId = null, album = "local")

        val unsupported = sync.prepareNeteasePlaylistSyncPlan(client, 91L, listOf(local))
        assertEquals("NO_SUPPORTED_SONGS", unsupported.message)
        assertEquals(1, unsupported.skippedUnsupported)
        verifyNoInteractions(client)

        val loggedOut = sync.prepareNeteasePlaylistSyncPlan(client, 91L, listOf(song(1L)))
        assertEquals("LOGIN_REQUIRED", loggedOut.message)
        assertEquals(1, loggedOut.supportedSongs)
        assertFalse(loggedOut.compareSucceeded)
    }

    @Test
    fun `liked synchronization preserves empty missing-target and successful upload results`() = runTest {
        val sync = NeteasePlaylistSync(noPause) { it.name }
        val missingTarget = mock(NeteaseClient::class.java)
        val empty = sync.syncSongsToNeteaseLiked(missingTarget, emptyList())
        assertEquals(0, empty.totalSongs)
        assertEquals("EMPTY_SONGS", empty.message)
        assertNull(empty.targetPlaylistId)
        verifyNoInteractions(missingTarget)

        `when`(missingTarget.getLikedPlaylistId(0L)).thenReturn("{\"code\":200,\"playlistId\":0}")
        val missing = sync.syncSongsToNeteaseLiked(missingTarget, listOf(song(1L)))
        assertEquals(1, missing.totalSongs)
        assertEquals(0, missing.added)
        assertEquals(0, missing.failed)
        assertEquals(NETEASE_COMPARE_FAILED_MESSAGE, missing.message)
        assertNull(missing.targetPlaylistId)

        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getLikedPlaylistId(0L)).thenReturn("{\"code\":200,\"playlistId\":91}")
        `when`(client.getSongDetail(listOf(1L))).thenReturn(songDetail(1L, "song"))
        `when`(client.getPlaylistDetail(91L)).thenReturn("{\"code\":200,\"playlist\":{\"trackIds\":[],\"trackCount\":0}}")
        `when`(client.addSongsToPlaylist(91L, listOf(1L))).thenReturn("{\"code\":200}")
        val added = sync.syncSongsToNeteaseLiked(client, listOf(song(1L)))
        assertEquals(1, added.totalSongs)
        assertEquals(1, added.supportedSongs)
        assertEquals(1, added.added)
        assertEquals(0, added.failed)
        assertEquals(91L, added.targetPlaylistId)
        verify(client).getLikedPlaylistId(0L)
        verify(client).addSongsToPlaylist(91L, listOf(1L))
    }

    private fun song(id: Long, name: String = "song"): SongItem {
        return SongItem(id, name, "artist", "NeteaseAlbum", 7L, 1_000L, null, channelId = "netease", audioId = id.toString())
    }

    private fun songDetail(id: Long, name: String): String {
        return """{"code":200,"songs":[${songJson(id, name)}]}"""
    }

    private fun songJson(id: Long, name: String): String {
        return """{"id":$id,"name":"$name","ar":[{"name":"artist"}],"dt":1000}"""
    }
}
