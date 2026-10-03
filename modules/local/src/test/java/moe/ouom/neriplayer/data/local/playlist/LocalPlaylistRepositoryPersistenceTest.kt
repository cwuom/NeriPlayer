package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPreviewAuthority
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LocalPlaylistRepositoryPersistenceTest : LocalPlaylistRepositoryTestSupport() {
    @Test
    fun `unreadable Room primary refuses initialization without reading stale legacy or allowing writes`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary temporarily unavailable") }
        val staleText = Gson().toJson(listOf(playlist(1, "stale JSON")))
        val storage = CountingStorage(primary = staleText, backup = staleText)
        var normalizationCalls = 0
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "unreadable_room_primary.json"),
            normalizePlaylists = { normalizationCalls++; it }, storage = storage,
            syncMutationStore = syncStore, roomStore = room
        )

        assertFalse(repository.awaitInitialized())
        assertFalse(repository.initializationReadyFlow.value)
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals(0, repository.playlistCount.value)
        assertEquals(0, normalizationCalls)
        assertEquals(0, storage.primaryReads)
        assertEquals(0, storage.backupReads)
        assertTrue(runCatching { repository.createPlaylist("refused local write") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { repository.applySyncedPlaylistsIfUnchanged(listOf(playlist(2, "refused sync")), 0) }.exceptionOrNull() is IOException)
        assertEquals(0, storage.commitCount)
        assertEquals(staleText, storage.primary)
        assertEquals(staleText, storage.backup)
        assertEquals(0L, syncStore.mutationVersion)
        assertTrue(repository.roomStorageEnabled)
        verify(room, never()).importLegacyAndPromote(anyList(), anyString())
        verify(room, never()).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyString(), anyLong())
    }

    @Test
    fun `unreadable Room after JSON cleanup cannot manufacture and persist system playlists`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary temporarily unavailable") }
        val storage = CountingStorage(primary = null, backup = null)
        var normalizationCalls = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "cleaned_legacy_primary.json"),
            normalizePlaylists = {
                normalizationCalls++
                if (it.isEmpty()) listOf(playlist(-1, "synthetic system playlist")) else it
            }, storage = storage, roomStore = room
        )

        assertFalse(repository.awaitInitialized())
        assertFalse(repository.initializationReadyFlow.value)
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals(0, normalizationCalls)
        assertEquals(0, storage.primaryReads)
        assertEquals(0, storage.backupReads)
        assertEquals(0, storage.commitCount)
        assertTrue(repository.roomStorageEnabled)
        verify(room, never()).importLegacyAndPromote(anyList(), anyString())
        verify(room, never()).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyString(), anyLong())
    }

    @Test
    fun `repaired Room read retries original authority and later local mutation survives repository reconstruction`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        var unavailable = true
        var primary = listOf(playlist(2, "primary"), playlist(3, "other primary"))
        val originalPrimary = primary
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("primary temporarily unavailable")
            primary
        }
        doAnswer {
            assertEquals(primary, it.getArgument<List<LocalPlaylist>>(0))
            primary = it.getArgument(1)
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        val staleText = Gson().toJson(listOf(playlist(1, "stale JSON")))
        val storage = CountingStorage(primary = staleText, backup = staleText)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "recover_primary.json"),
            normalizePlaylists = { it }, storage = storage,
            syncMutationStore = syncStore, roomStore = room
        )

        unavailable = false
        assertTrue(repository.awaitInitialized())
        assertTrue(repository.initializationReadyFlow.value)
        assertEquals(originalPrimary, repository.playlists.value)
        assertEquals(2, repository.playlistCount.value)
        assertTrue(repository.roomStorageEnabled)
        repository.renamePlaylist(2, "recovered")
        assertEquals(setOf(2L, 3L), primary.map { it.id }.toSet())
        assertEquals("recovered", primary.single { it.id == 2L }.name)
        assertEquals(primary, repository.playlists.value)
        assertEquals(0, storage.commitCount)
        assertEquals(staleText, storage.primary)
        val reopened = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "recover_primary.json"),
            normalizePlaylists = { it }, storage = storage,
            syncMutationStore = syncStore, roomStore = room
        )
        assertTrue(reopened.awaitInitialized())
        assertEquals(primary, reopened.playlists.value)
        verify(room, never()).importLegacyAndPromote(anyList(), anyString())
        verify(room, never()).markLegacyJsonPrimary(anyString(), anyLong())
    }

    @Test
    fun `fast preview missing Room primary playlist cannot resurrect stale legacy playlist`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(emptyList())
        `when`(room.readFastPlaylistAuthority(2)).thenReturn(LocalPlaylistPreviewAuthority.RoomPrimary(null))
        val staleText = Gson().toJson(listOf(playlist(2, "deleted playlist")))
        val storage = CountingStorage(primary = staleText, backup = staleText)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "missing_primary_preview.json"),
            normalizePlaylists = { it }, storage = storage,
            syncMutationStore = RecordingSyncMutationStore(), roomStore = room
        )
        val primaryReadsBeforePreview = storage.primaryReads
        val backupReadsBeforePreview = storage.backupReads

        assertNull(repository.readFastPlaylist(2))
        assertEquals(primaryReadsBeforePreview, storage.primaryReads)
        assertEquals(backupReadsBeforePreview, storage.backupReads)
        assertTrue(repository.playlists.value.isEmpty())
        assertTrue(repository.roomStorageEnabled)
    }

    @Test
    fun `fast preview primary I O failure cannot present stale legacy as current playlist`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        val primary = listOf(playlist(2, "actual primary"))
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        `when`(room.readFastPlaylistAuthority(2)).thenAnswer { throw IOException("preview unavailable") }
        val staleText = Gson().toJson(listOf(playlist(2, "stale JSON")))
        val storage = CountingStorage(primary = staleText, backup = staleText)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "fast_primary_preview.json"),
            normalizePlaylists = { it }, storage = storage,
            syncMutationStore = RecordingSyncMutationStore(), roomStore = room
        )
        val primaryReadsBeforePreview = storage.primaryReads
        val backupReadsBeforePreview = storage.backupReads

        assertNull(repository.readFastPlaylist(2))
        assertEquals(primaryReadsBeforePreview, storage.primaryReads)
        assertEquals(backupReadsBeforePreview, storage.backupReads)
        assertEquals(primary, repository.playlists.value)
        assertTrue(repository.roomStorageEnabled)
    }

    @Test
    fun `fast preview cancellation propagates without reading stale legacy`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        val primary = listOf(playlist(2, "actual primary"))
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        val cancellation = CancellationException("preview cancelled")
        `when`(room.readFastPlaylistAuthority(2)).thenAnswer { throw cancellation }
        val staleText = Gson().toJson(listOf(playlist(2, "stale JSON")))
        val storage = CountingStorage(primary = staleText, backup = staleText)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "cancelled_primary_preview.json"),
            normalizePlaylists = { it }, storage = storage,
            syncMutationStore = RecordingSyncMutationStore(), roomStore = room
        )
        val primaryReadsBeforePreview = storage.primaryReads
        val result = runCatching { repository.readFastPlaylist(2) }.exceptionOrNull()

        assertTrue(result is CancellationException)
        assertEquals(cancellation.message, result?.message)
        assertEquals(primaryReadsBeforePreview, storage.primaryReads)
        assertEquals(primary, repository.playlists.value)
        assertTrue(repository.roomStorageEnabled)
    }

    private fun playlist(id: Long, name: String) = LocalPlaylist(
        id = id, name = name, songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )

    private class CountingStorage(
        var primary: String?,
        val backup: String?
    ) : LocalPlaylistStorage {
        var primaryReads = 0
        var backupReads = 0
        var commitCount = 0

        override fun readPrimary(): String? { primaryReads++; return primary }
        override fun readBackup(): String? { backupReads++; return backup }
        override fun commit(text: String, rotateBackup: Boolean, replaceBackupWithCommittedPrimary: Boolean) {
            commitCount++
            primary = text
        }
        override fun quarantinePrimary(): File? = null
    }
}
