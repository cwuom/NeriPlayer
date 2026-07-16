package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException

class LocalPlaylistRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `concurrent prepared adds keep every distinct song`() = runTest {
        val playlistId = 42L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "并发歌单")))

        val songs = (1..40).map(::localSong)
        val addResults = songs.map { song ->
            async(Dispatchers.Default) {
                repository.addPreparedSongsToPlaylistAndCount(playlistId, listOf(song))
            }
        }.awaitAll()

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(songs.size, addResults.sum())
        assertEquals(
            songs.map { it.localFilePath }.toSet(),
            playlist.songs.map { it.localFilePath }.toSet()
        )
    }

    @Test
    fun `scanned adds skip local metadata duplicates in regular playlist`() = runTest {
        val playlistId = 43L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "扫描歌单")))

        val contentAlias = scannedAliasSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100"
        )
        val pathAlias = scannedAliasSong(
            id = 2L,
            mediaUri = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath,
            localFilePath = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath
        )

        val firstAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(contentAlias))
        val secondAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(pathAlias))
        val playlist = repository.playlists.value.single { it.id == playlistId }

        assertEquals(1, firstAdd)
        assertEquals(0, secondAdd)
        assertEquals(1, playlist.songs.size)
        assertEquals(contentAlias.mediaUri, playlist.songs.single().mediaUri)
        assertEquals(contentAlias.localFileName, playlist.songs.single().localFileName)
    }

    @Test
    fun `adding downloaded local copy to favorites keeps original favorite order`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val remoteSong = remoteNeteaseSong(addedAt = 11L)
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(remoteSong),
                    modifiedAt = 10L
                )
            )
        )

        repository.addToFavorites(downloadedLocalCopy(remoteSong))

        val favorites = repository.playlists.value.single()
        assertEquals(1, favorites.songs.size)
        assertEquals(remoteSong, favorites.songs.single())
        assertEquals(11L, favorites.songs.single().addedAt)
    }

    @Test
    fun `legacy playlist migration preserves previous display order and rewrites added time`() = runTest {
        val playlistId = 44L
        val storageFile = File(tempFolder.root, "legacy_local_playlists.json")
        storageFile.writeText(
            """
            [
              {
                "id": $playlistId,
                "name": "旧歌单",
                "songs": [
                  ${songJson(1L, "oldest", 11L)},
                  ${songJson(2L, "middle", 22L)},
                  ${songJson(3L, "newest", 33L)}
                ],
                "modifiedAt": 1000
              }
            ]
            """.trimIndent()
        )

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, playlist.songOrderVersion)
        assertEquals(listOf("newest", "middle", "oldest"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.map { it.addedAt },
            playlist.songs.map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `adding songs to regular playlist places newest songs first`() = runTest {
        val playlistId = 45L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "普通歌单",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(
                localSong(index = 2, name = "new-a"),
                localSong(index = 3, name = "new-b")
            )
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new-a", "new-b", "old"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.take(2).map { it.addedAt },
            playlist.songs.take(2).map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `adding new favorite places it before existing favorites`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addToFavorites(localSong(index = 2, name = "new"))

        val favorites = repository.playlists.value.single()
        assertEquals(listOf("new", "old"), favorites.songs.map { it.name })
    }

    @Test
    fun `manual reorder keeps user order after adding another song`() = runTest {
        val playlistId = 46L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val first = localSong(index = 1, name = "first")
        val second = localSong(index = 2, name = "second")
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "手动排序",
                    songs = mutableListOf(first, second),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.reorderSongs(playlistId, listOf(second.identity(), first.identity()))
        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(localSong(index = 3, name = "new"))
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new", "second", "first"), playlist.songs.map { it.name })
    }

    @Test
    fun `corrupt primary is quarantined without being replaced by empty state`() {
        val storageFile = File(tempFolder.root, "corrupt_local_playlists.json")
        val corruptJson = "{not-valid-json"
        storageFile.writeText(corruptJson)

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        val quarantines = tempFolder.root.listFiles().orEmpty()
            .filter { it.name.startsWith("${storageFile.name}.corrupt-") }
        assertTrue(repository.playlists.value.isEmpty())
        assertFalse(storageFile.exists())
        assertEquals(1, quarantines.size)
        assertEquals(corruptJson, quarantines.single().readText())
    }

    @Test
    fun `valid backup restores corrupt primary before publishing playlists`() {
        val storageFile = File(tempFolder.root, "recover_local_playlists.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        val corruptJson = "[broken"
        val backupJson = playlistJson(id = 71L, name = "备份歌单")
        storageFile.writeText(corruptJson)
        backupFile.writeText(backupJson)

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        assertEquals("备份歌单", repository.playlists.value.single().name)
        assertEquals(backupJson, storageFile.readText())
        assertEquals(backupJson, backupFile.readText())
        assertTrue(
            tempFolder.root.listFiles().orEmpty().any {
                it.name.startsWith("${storageFile.name}.corrupt-") &&
                    it.readText() == corruptJson
            }
        )
    }

    @Test
    fun `write failure propagates without publishing uncommitted playlists`() = runTest {
        val initialJson = playlistJson(id = 81L, name = "已落盘")
        val storage = FailingCommitStorage(initialJson)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "write_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage
        )

        val failure = runCatching {
            repository.updatePlaylists(listOf(LocalPlaylist(id = 82L, name = "未落盘")))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(listOf("已落盘"), repository.playlists.value.map(LocalPlaylist::name))
        assertEquals(1, repository.playlistCount.value)
        assertEquals(initialJson, storage.primary)
    }

    @Test
    fun `successful replacement keeps previous primary as stable backup`() = runTest {
        val storageFile = File(tempFolder.root, "backup_rotation.json")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 91L, name = "first")))
        repository.updatePlaylists(listOf(LocalPlaylist(id = 92L, name = "second")))

        val backupText = File(tempFolder.root, "${storageFile.name}.bak").readText()
        assertTrue(backupText.contains("first"))
        assertFalse(backupText.contains("second"))
        assertTrue(storageFile.readText().contains("second"))
    }

    @Test
    fun `first successful commit seeds a recoverable backup`() = runTest {
        val storageFile = File(tempFolder.root, "first_commit_backup.json")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 101L, name = "first")))

        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        assertTrue(backupFile.exists())
        assertEquals(storageFile.readText(), backupFile.readText())
    }

    @Test
    fun `invalid song entry falls back to valid backup`() {
        val storageFile = File(tempFolder.root, "invalid_song.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText(
            """
            [
              {
                "id": 111,
                "name": "broken",
                "songs": [null],
                "songOrderVersion": 0
              }
            ]
            """.trimIndent()
        )
        backupFile.writeText(playlistJson(id = 112L, name = "backup"))

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        assertEquals("backup", repository.playlists.value.single().name)
        assertTrue(storageFile.readText().contains("backup"))
    }

    @Test
    fun `first commit after both copies are corrupt replaces invalid backup`() = runTest {
        val storageFile = File(tempFolder.root, "replace_invalid_backup.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText("[broken-primary")
        backupFile.writeText("[broken-backup")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 113L, name = "recovered")))

        assertEquals(storageFile.readText(), backupFile.readText())
        assertTrue(backupFile.readText().contains("recovered"))
    }

    @Test
    fun `normalization failure falls back to valid backup`() {
        val storageFile = File(tempFolder.root, "normalization_failure.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText(playlistJson(id = 121L, name = "bad"))
        backupFile.writeText(playlistJson(id = 122L, name = "good"))

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { playlists ->
                check(playlists.none { it.name == "bad" })
                playlists
            },
            autoSyncEnabled = false
        )

        assertEquals("good", repository.playlists.value.single().name)
        assertTrue(storageFile.readText().contains("good"))
    }

    @Test
    fun `failed playlist commit does not apply sync tombstone early`() = runTest {
        val song = remoteNeteaseSong(id = 131L)
        val initialJson = playlistJson(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "favorites",
            songs = listOf(song)
        )
        val storage = RecordingStorage(primary = initialJson, failCommit = true)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "tombstone_commit_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = syncStore
        )

        val failure = runCatching { repository.removeFromFavorites(song) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(syncStore.applied.isEmpty())
        assertEquals(song.id, repository.playlists.value.single().songs.single().id)

        val recoveredSyncStore = RecordingSyncMutationStore()
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "tombstone_commit_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertTrue(recoveredSyncStore.applied.isEmpty())
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `pending sync mutation replays after playlist commit succeeds`() = runTest {
        val song = remoteNeteaseSong(id = 141L)
        val storage = RecordingStorage(
            primary = playlistJson(
                id = FavoritesPlaylist.SYSTEM_ID,
                name = "favorites",
                songs = listOf(song)
            )
        )
        val failingSyncStore = RecordingSyncMutationStore(failApply = true)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = failingSyncStore
        )

        repository.removeFromFavorites(song)
        assertTrue(repository.playlists.value.single().songs.isEmpty())
        assertTrue(repository.syncMutationPending.value)
        assertTrue(storage.pendingSyncMutation != null)

        val recoveredSyncStore = RecordingSyncMutationStore()
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertEquals(1, recoveredSyncStore.applied.size)
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `new membership token is captured by later deletion`() = runTest {
        val playlistId = 145L
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "membership_token_delete.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "tokens")))

        val song = remoteNeteaseSong(id = 146L)
        repository.addPreparedSongsToPlaylist(playlistId, listOf(song))
        val membershipToken = repository.playlists.value
            .single()
            .songs
            .single()
            .syncMembershipTokens
            .orEmpty()
            .single()

        repository.removeSongsFromPlaylistByIdentity(playlistId, listOf(song))

        val deletion = syncStore.applied
            .flatMap(LocalPlaylistSyncMutation::addedSongDeletions)
            .single()
        assertEquals(listOf(membershipToken), deletion.removedMembershipTokens)
    }

    @Test
    fun `metadata update preserves membership token and display order`() = runTest {
        val playlistId = 146L
        val membershipToken = SyncCausalToken(deviceId = "existing", counter = 9L)
        val original = remoteNeteaseSong(id = 147L).copy(
            addedAt = 500L,
            syncMembershipTokens = listOf(membershipToken)
        )
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "metadata_membership.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "metadata",
                    songs = mutableListOf(original),
                    modifiedAt = 10L
                )
            )
        )

        repository.updateSongMetadata(
            originalSong = original,
            newSongInfo = original.copy(
                name = "Hydrated",
                addedAt = 100L,
                syncMembershipTokens = emptyList()
            )
        )

        val updated = repository.playlists.value.single().songs.single()
        assertEquals("Hydrated", updated.name)
        assertEquals(500L, updated.addedAt)
        assertEquals(listOf(membershipToken), updated.syncMembershipTokens)
        assertTrue(repository.playlists.value.single().modifiedAt > 10L)
        assertEquals(1L, syncStore.mutationVersion)
        assertEquals(0, autoSyncTriggerCount)
    }

    @Test
    fun `same playlist state still commits restored playlist mutation`() = runTest {
        val playlist = LocalPlaylist(id = 148L, name = "restored")
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "same_state_restore.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(listOf(playlist))

        repository.updatePlaylists(
            playlists = listOf(playlist),
            triggerSync = true,
            restoredPlaylistIds = setOf(playlist.id)
        )

        assertEquals(listOf(playlist.id), syncStore.applied.single().restoredPlaylistIds)
        assertEquals(1L, syncStore.mutationVersion)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `sync apply is rejected after local mutation epoch changes`() = runTest {
        val initial = LocalPlaylist(id = 149L, name = "initial")
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "guarded_sync_apply.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(listOf(initial))
        val expectedMutationVersion = syncStore.mutationVersion
        repository.updatePlaylists(
            playlists = listOf(initial.copy(name = "local")),
            triggerSync = true
        )

        val applied = repository.applySyncedPlaylistsIfUnchanged(
            playlists = listOf(initial.copy(name = "remote")),
            expectedMutationVersion = expectedMutationVersion
        )

        assertFalse(applied)
        assertEquals("local", repository.playlists.value.single().name)
    }

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

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        `when`(context.getString(R.string.playlist_create)).thenReturn("Playlist")
        return context
    }

    private fun localSong(index: Int, name: String = "song-$index"): SongItem {
        val path = File(tempFolder.root, "song-$index.mp3").absolutePath
        return SongItem(
            id = index.toLong(),
            name = name,
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1000L + index,
            coverUrl = null,
            mediaUri = path,
            localFilePath = path
        )
    }

    private fun scannedAliasSong(
        id: Long,
        mediaUri: String,
        localFilePath: String? = null
    ): SongItem {
        return SongItem(
            id = id,
            name = "晴天",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = "周杰伦 - 晴天.mp3",
            localFilePath = localFilePath,
            channelId = "local",
            audioId = id.toString()
        )
    }

    private fun remoteNeteaseSong(
        id: Long = 42L,
        name: String = "song",
        addedAt: Long = 0L
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString(),
            addedAt = addedAt
        )
    }

    private fun songJson(id: Long, name: String, addedAt: Long): String {
        return """
            {
              "id": $id,
              "name": "$name",
              "artist": "artist",
              "album": "NeteaseAlbum",
              "albumId": 7,
              "durationMs": 1000,
              "coverUrl": null,
              "channelId": "netease",
              "audioId": "$id",
              "addedAt": $addedAt
            }
        """.trimIndent()
    }

    private fun downloadedLocalCopy(source: SongItem): SongItem {
        val path = File(tempFolder.root, "song.mp3").absolutePath
        return SongItem(
            id = 99L,
            name = source.name,
            artist = source.artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = source.durationMs,
            coverUrl = null,
            mediaUri = path,
            localFileName = "song.mp3",
            localFilePath = path,
            channelId = "local",
            audioId = "99",
            sourceStableKey = source.stableKey()
        )
    }

    private fun playlistJson(
        id: Long,
        name: String,
        songs: List<SongItem> = emptyList()
    ): String {
        val songsJson = songs.joinToString(separator = ",") { song ->
            songJson(song.id, song.name, song.addedAt)
        }
        return """
            [
              {
                "id": $id,
                "name": "$name",
                "songs": [$songsJson],
                "modifiedAt": 1000,
                "customCoverUrl": null,
                "songOrderVersion": $DISPLAY_ORDER_SONG_ORDER_VERSION
              }
            ]
        """.trimIndent()
    }

    private class FailingCommitStorage(
        var primary: String?
    ) : LocalPlaylistStorage {
        override fun readPrimary(): String? = primary

        override fun readBackup(): String? = null

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) {
            throw IOException("simulated write failure")
        }

        override fun quarantinePrimary(): File? = null
    }

    private class RecordingStorage(
        var primary: String?,
        private val backup: String? = null,
        var failCommit: Boolean = false
    ) : LocalPlaylistStorage {
        var pendingSyncMutation: String? = null

        override fun readPrimary(): String? = primary

        override fun readBackup(): String? = backup

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) {
            if (failCommit) throw IOException("simulated write failure")
            primary = text
        }

        override fun quarantinePrimary(): File? = null

        override fun readPendingSyncMutation(): String? = pendingSyncMutation

        override fun writePendingSyncMutation(text: String) {
            pendingSyncMutation = text
        }

        override fun clearPendingSyncMutation() {
            pendingSyncMutation = null
        }
    }

    private class RecordingSyncMutationStore(
        private val failApply: Boolean = false
    ) : LocalPlaylistSyncMutationStore {
        val applied = mutableListOf<LocalPlaylistSyncMutation>()
        var allocatedTokenCount = 0
            private set
        var mutationVersion = 0L
            private set
        private var nextCounter = 1L

        override fun getOrCreateDeviceId(): String = "test-device"

        override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
            require(count >= 0)
            allocatedTokenCount += count
            return List(count) {
                SyncCausalToken(
                    deviceId = getOrCreateDeviceId(),
                    counter = nextCounter++
                )
            }
        }

        override fun getSyncMutationVersion(): Long = mutationVersion

        override fun markSyncMutation(): Long {
            mutationVersion += 1L
            return mutationVersion
        }

        override fun apply(mutation: LocalPlaylistSyncMutation) {
            if (failApply) throw IOException("simulated sync mutation failure")
            applied += mutation
        }
    }
}
