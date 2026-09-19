package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


class LocalPlaylistRepositoryGroup2Test : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `sync apply succeeds without advancing local mutation epoch`() = runTest {
        val initial = LocalPlaylist(id = 150L, name = "initial")
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "successful_guarded_sync_apply.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(listOf(initial))

        val applied = repository.applySyncedPlaylistsIfUnchanged(
            playlists = listOf(initial.copy(name = "remote")),
            expectedMutationVersion = syncStore.mutationVersion
        )

        assertTrue(applied)
        assertEquals("remote", repository.playlists.value.single().name)
        assertEquals(0L, syncStore.mutationVersion)
        assertEquals(0, autoSyncTriggerCount)
    }

    @Test
    fun `same state restore mutation replays after apply failure`() = runTest {
        val playlist = LocalPlaylist(id = 151L, name = "restored")
        val storage = RecordingStorage(primary = null)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "same_state_restore_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore(failApply = true)
        )
        repository.updatePlaylists(listOf(playlist))

        repository.updatePlaylists(
            playlists = listOf(playlist),
            triggerSync = true,
            restoredPlaylistIds = setOf(playlist.id)
        )
        assertTrue(storage.pendingSyncMutation != null)

        val recoveredStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "same_state_restore_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            storage = storage,
            syncMutationStore = recoveredStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )

        assertEquals(listOf(playlist.id), recoveredStore.applied.single().restoredPlaylistIds)
        assertTrue(storage.pendingSyncMutation == null)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `export allocates one membership token per inserted song`() = runTest {
        val sourceId = 152L
        val targetId = 153L
        val song = remoteNeteaseSong(id = 154L)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "export_token_count.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(id = sourceId, name = "source", songs = mutableListOf(song)),
                LocalPlaylist(id = targetId, name = "target")
            )
        )

        repository.exportSongsToPlaylistByIdentity(sourceId, targetId, listOf(song))

        assertEquals(1, syncStore.allocatedTokenCount)
        assertEquals(1, repository.playlists.value.single { it.id == targetId }.songs.size)
    }

    @Test
    fun `add result only contains songs inserted by this batch`() = runTest {
        val targetId = 155L
        val existingSong = remoteNeteaseSong(id = 156L, name = "existing")
        val newSong = remoteNeteaseSong(id = 157L, name = "new")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "batch_add_result.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = targetId,
                    name = "target",
                    songs = mutableListOf(existingSong),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        val addResult = repository.addSongsToPlaylistWithResult(
            targetId,
            listOf(existingSong, newSong)
        )

        assertEquals(listOf(newSong.id), addResult.addedSongs.map { it.id })

        repository.removeSongsFromPlaylistByIdentity(targetId, addResult.addedSongs)

        val targetSongs = repository.playlists.value.single { it.id == targetId }.songs
        assertEquals(listOf(existingSong.id), targetSongs.map { it.id })
    }

    @Test
    fun `deleted playlists restore at original positions with a newer timestamp`() = runTest {
        val first = LocalPlaylist(id = 201L, name = "first")
        val second = LocalPlaylist(id = 202L, name = "second")
        val third = LocalPlaylist(id = 203L, name = "third")
        val fourth = LocalPlaylist(id = 204L, name = "fourth")
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "delete_restore_playlist.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(listOf(first, second, third, fourth))

        val deleteResults = repository.deletePlaylistsWithResult(listOf(second.id, fourth.id))

        assertEquals(listOf(second.id, fourth.id), deleteResults.map { it.playlist.id })
        assertEquals(listOf(1, 3), deleteResults.map { it.index })
        assertEquals(listOf(first.id, third.id), repository.playlists.value.map { it.id })
        assertEquals(listOf(second.id, fourth.id), syncStore.applied.single().deletedPlaylistIds)

        assertTrue(repository.restoreDeletedPlaylists(deleteResults))

        assertEquals(
            listOf(first.id, second.id, third.id, fourth.id),
            repository.playlists.value.map { it.id }
        )
        assertTrue(
            repository.playlists.value.single { it.id == second.id }.modifiedAt > second.modifiedAt
        )
        assertEquals(
            listOf(second.id, fourth.id),
            syncStore.applied.last().restoredPlaylistIds
        )
    }

    @Test
    fun `removed songs restore at original positions with renewed sync membership`() = runTest {
        val playlistId = 305L
        val first = remoteNeteaseSong(id = 306L, name = "first", addedAt = 4L)
            .copy(syncMembershipTokens = listOf(SyncCausalToken("old", 1L)))
        val second = remoteNeteaseSong(id = 307L, name = "second", addedAt = 3L)
            .copy(syncMembershipTokens = listOf(SyncCausalToken("old", 2L)))
        val third = remoteNeteaseSong(id = 308L, name = "third", addedAt = 2L)
            .copy(syncMembershipTokens = listOf(SyncCausalToken("old", 3L)))
        val fourth = remoteNeteaseSong(id = 309L, name = "fourth", addedAt = 1L)
            .copy(syncMembershipTokens = listOf(SyncCausalToken("old", 4L)))
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "song_delete_restore_playlist.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "target",
                    songs = mutableListOf(first, second, third, fourth),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        val deleteResults = repository.removeSongsFromPlaylistByIdentityWithResult(
            playlistId,
            listOf(second, fourth)
        )

        assertEquals(listOf(second.id, fourth.id), deleteResults.map { it.song.id })
        assertEquals(listOf(1, 3), deleteResults.map { it.index })
        assertEquals(listOf(first.id, third.id), repository.playlists.value.single().songs.map { it.id })
        assertTrue(syncStore.applied.first().addedSongDeletions.isNotEmpty())

        assertTrue(repository.restoreDeletedSongs(deleteResults))

        assertEquals(
            listOf(first.id, second.id, third.id, fourth.id),
            repository.playlists.value.single().songs.map { it.id }
        )
        val restoredSecond = repository.playlists.value.single().songs.single { it.id == second.id }
        val deletion = syncStore.applied.first().addedSongDeletions
            .single { it.songId == second.id }
        assertEquals(listOf(SyncCausalToken("test-device", 1L)), restoredSecond.syncMembershipTokens)
        assertTrue(
            SyncPlaylistDeletionPolicy.applyDeletions(
                playlistId = playlistId,
                songs = listOf(SyncSong.fromSongItem(restoredSecond)),
                deletions = listOf(deletion)
            ).isNotEmpty()
        )
        assertTrue(syncStore.applied.last().removedSongDeletions.isNotEmpty())
    }

    @Test
    fun `cleared songs can be restored in their original order`() = runTest {
        val playlistId = 315L
        val first = remoteNeteaseSong(id = 316L, name = "first", addedAt = 4L)
        val second = remoteNeteaseSong(id = 317L, name = "second", addedAt = 3L)
        val third = remoteNeteaseSong(id = 318L, name = "third", addedAt = 2L)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "song_clear_restore_playlist.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "target",
                    songs = mutableListOf(first, second, third),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        val deleteResults = repository.clearPlaylistSongsWithResult(playlistId)

        assertEquals(listOf(first.id, second.id, third.id), deleteResults.map { it.song.id })
        assertEquals(listOf(0, 1, 2), deleteResults.map { it.index })
        assertTrue(repository.playlists.value.single().songs.isEmpty())
        assertTrue(syncStore.applied.first().addedSongDeletions.isNotEmpty())

        assertTrue(repository.restoreDeletedSongs(deleteResults))

        assertEquals(
            listOf(first.id, second.id, third.id),
            repository.playlists.value.single().songs.map { it.id }
        )
        assertTrue(syncStore.applied.last().removedSongDeletions.isNotEmpty())
    }

    @Test
    fun `removed local files songs can be restored when download deletion fails`() = runTest {
        val first = localSong(index = 701, name = "first")
        val second = localSong(index = 702, name = "second")
        val context = mockContext()
        val repository = LocalPlaylistRepository.createForTest(
            context = context,
            file = File(tempFolder.root, "local_files_song_restore.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )
        assertEquals(
            2,
            repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(first, second))
        )

        val deleteResults = repository.removeSongsFromPlaylistByIdentityWithResult(
            LocalFilesPlaylist.SYSTEM_ID,
            listOf(first)
        )

        assertEquals(1, deleteResults.size)
        assertTrue(repository.restoreDeletedSongs(deleteResults))
        assertEquals(
            listOf(first.id, second.id),
            repository.playlists.value.single().songs.map { it.id }
        )
    }

    @Test
    fun `scanned metadata merge keeps the first matching scanned alias`() = runTest {
        val existing = legacyLocalSong(index = 720, name = "same")
        val firstAlias = legacyLocalSong(index = 721, name = "same").copy(
            customName = "first alias"
        )
        val secondAlias = legacyLocalSong(index = 720, name = "same").copy(
            customName = "identity alias"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "scanned_metadata_alias_order.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )
        assertEquals(
            1,
            repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(existing))
        )

        assertEquals(
            0,
            repository.addScannedSongsToLocalFilesPlaylistAndCount(
                listOf(firstAlias, secondAlias)
            )
        )

        val stored = repository.playlists.value
            .single { it.id == LocalFilesPlaylist.SYSTEM_ID }
            .songs
            .single()
        assertEquals("first alias", stored.customName)
    }

    @Test
    fun `scanned local song keeps source creation time and records membership time`() = runTest {
        val sourceSong = localSong(index = 703, name = "old source").copy(
            addedAt = 1L,
            logicalCreatedAtMs = 1L
        )
        val context = mockContext()
        val repository = LocalPlaylistRepository.createForTest(
            context = context,
            file = File(tempFolder.root, "local_files_membership_time.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )

        assertEquals(
            1,
            repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(sourceSong))
        )

        val stored = repository.playlists.value
            .single { it.id == LocalFilesPlaylist.SYSTEM_ID }
            .songs
            .single()
        assertEquals(1L, stored.addedAt)
        assertTrue((stored.membershipAddedAtMs ?: 0L) > 1L)
    }

    @Test
    fun `scanned timestamp helper falls back to membership for unknown source`() {
        val song = localSong(index = 704)
        assertEquals(
            99L,
            resolvePlaylistSongAddedAt(
                song = song,
                membershipAddedAt = 100L,
                index = 1,
                preserveScannedSourceAddedAt = true
            )
        )
    }

    @Test
    fun `scanned local files sort by creation time within the new batch`() = runTest {
        val older = localSong(index = 705, name = "older").copy(
            addedAt = 10L,
            logicalCreatedAtMs = 10L,
            createdAtConfidence = "EXACT"
        )
        val newer = localSong(index = 706, name = "newer").copy(
            addedAt = 20L,
            logicalCreatedAtMs = 20L,
            createdAtConfidence = "EXACT"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_files_creation_order.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )

        repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(older, newer))

        assertEquals(
            listOf(newer.id, older.id),
            repository.playlists.value.single { it.id == LocalFilesPlaylist.SYSTEM_ID }
                .songs
                .map { it.id }
        )
    }

    @Test
    fun `single local import is placed first even when source file is old`() = runTest {
        val old = localSong(index = 709, name = "old").copy(
            addedAt = 10L,
            logicalCreatedAtMs = 10L,
            createdAtConfidence = "EXACT"
        )
        val newlyImported = localSong(index = 710, name = "new").copy(
            addedAt = 1L,
            logicalCreatedAtMs = 1L,
            createdAtConfidence = "EXACT"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_files_single_import_order.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )

        repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(old))
        repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(newlyImported))

        assertEquals(
            listOf(newlyImported.id, old.id),
            repository.playlists.value.single { it.id == LocalFilesPlaylist.SYSTEM_ID }
                .songs
                .map { it.id }
        )
    }

    @Test
    fun `new scanned batch uses modification order independently above existing songs`() = runTest {
        val older = localSong(index = 711, name = "older").copy(
            addedAt = 10L,
            logicalCreatedAtMs = 30L,
            sourceModifiedAtMs = 10L,
            createdAtConfidence = "EXACT"
        )
        val newer = localSong(index = 712, name = "newer").copy(
            addedAt = 20L,
            logicalCreatedAtMs = 20L,
            sourceModifiedAtMs = 20L,
            createdAtConfidence = "EXACT"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_files_batch_import_order.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )

        repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(older, newer))

        val secondOlder = localSong(index = 713, name = "E").copy(
            addedAt = 1L, logicalCreatedAtMs = 3L, sourceModifiedAtMs = 1L, createdAtConfidence = "EXACT"
        )
        val secondNewer = localSong(index = 714, name = "F").copy(
            addedAt = 2L, logicalCreatedAtMs = 2L, sourceModifiedAtMs = 2L, createdAtConfidence = "EXACT"
        )
        repository.addScannedSongsToLocalFilesPlaylistAndCount(listOf(secondOlder, secondNewer))

        assertEquals(
            listOf(secondNewer.id, secondOlder.id, newer.id, older.id),
            repository.playlists.value.single { it.id == LocalFilesPlaylist.SYSTEM_ID }
                .songs
                .map { it.id }
        )
    }

    @Test
    fun `manual local file adds are sorted by logical creation time`() = runTest {
        val older = localSong(index = 707, name = "older").copy(
            addedAt = 10L,
            logicalCreatedAtMs = 10L,
            createdAtConfidence = "EXACT"
        )
        val newer = localSong(index = 708, name = "newer").copy(
            addedAt = 20L,
            logicalCreatedAtMs = 20L,
            createdAtConfidence = "EXACT"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_files_manual_creation_order.json"),
            normalizePlaylists = { playlists ->
                if (playlists.any { it.id == LocalFilesPlaylist.SYSTEM_ID }) {
                    playlists
                } else {
                    playlists + LocalPlaylist(
                        id = LocalFilesPlaylist.SYSTEM_ID,
                        name = "Local Files"
                    )
                }
            },
            autoSyncEnabled = false
        )

        repository.addSongsToLocalFilesPlaylist(listOf(older, newer))

        assertEquals(
            listOf(newer.id, older.id),
            repository.playlists.value.single { it.id == LocalFilesPlaylist.SYSTEM_ID }
                .songs
                .map { it.id }
        )
    }

    @Test
    fun `restored playlist id is committed before external sync is scheduled`() = runTest {
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "restored_playlist_sync.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )

        repository.updatePlaylists(
            playlists = listOf(LocalPlaylist(id = 147L, name = "restored")),
            triggerSync = true,
            restoredPlaylistIds = setOf(147L)
        )

        assertEquals(listOf(147L), syncStore.applied.single().restoredPlaylistIds)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `startup replay schedules auto sync after mutation is applied`() = runTest {
        val song = remoteNeteaseSong(id = 142L)
        val storage = RecordingStorage(
            primary = playlistJson(
                id = FavoritesPlaylist.SYSTEM_ID,
                name = "favorites",
                songs = listOf(song)
            )
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_schedule.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore(failApply = true)
        )
        repository.removeFromFavorites(song)

        var autoSyncTriggerCount = 0
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_schedule.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore(),
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )

        assertEquals(1, autoSyncTriggerCount)
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `failed pending mutation does not block edits and replays in commit order`() = runTest {
        val playlistId = 151L
        val song = remoteNeteaseSong(id = 152L)
        val storage = RecordingStorage(
            primary = playlistJson(
                id = playlistId,
                name = "before",
                songs = listOf(song)
            )
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_merge.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore(failApply = true)
        )

        repository.removeSongsFromPlaylistByIdentity(playlistId, listOf(song))
        repository.renamePlaylist(playlistId, "after")
        repository.addPreparedSongsToPlaylist(playlistId, listOf(song))

        val editedPlaylist = repository.playlists.value.single()
        assertEquals("after", editedPlaylist.name)
        assertEquals(song.id, editedPlaylist.songs.single().id)
        assertTrue(repository.syncMutationPending.value)

        val recoveredSyncStore = RecordingSyncMutationStore()
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_merge.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertEquals(2, recoveredSyncStore.applied.size)
        assertEquals(1, recoveredSyncStore.applied[0].addedSongDeletions.size)
        assertEquals(1, recoveredSyncStore.applied[1].removedSongDeletions.size)
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `failed later commit keeps earlier committed mutation replayable`() = runTest {
        val playlistId = 161L
        val song = remoteNeteaseSong(id = 162L)
        val storage = RecordingStorage(
            primary = playlistJson(
                id = playlistId,
                name = "before",
                songs = listOf(song)
            )
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_failed_transition.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore(failApply = true)
        )
        repository.removeSongsFromPlaylistByIdentity(playlistId, listOf(song))

        storage.failCommit = true
        val failure = runCatching {
            repository.renamePlaylist(playlistId, "uncommitted")
        }.exceptionOrNull()
        storage.failCommit = false

        assertTrue(failure is IOException)
        val recoveredSyncStore = RecordingSyncMutationStore()
        val recoveredRepository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_failed_transition.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertEquals("before", recoveredRepository.playlists.value.single().name)
        assertEquals(1, recoveredSyncStore.applied.size)
        assertEquals(1, recoveredSyncStore.applied.single().addedSongDeletions.size)
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `safe mutation runner reports io failure without throwing`() = runTest {
        val result = runLocalPlaylistMutationSafely("test") {
            throw IOException("simulated")
        }

        assertTrue(result.exceptionOrNull() is IOException)
    }
}
