package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.mapping.fromLocalPlaylist
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.merge.song.SyncPlaylistSongMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMatchedFavoriteMetadataTest : LocalPlaylistRepositoryTestSupport() {
    private var fixtureIndex = 0
    private val originalToken = SyncCausalToken("other-device", 1L)
    private val staleDestinationToken = SyncCausalToken("other-device", 2L)

    @Test
    fun `rematching a favorite deletes the old remote member and renews the new membership`() = runTest {
        val fixture = fixture(matchedLocalSong())
        val original = fixture.song()
        val staleRemote = fixture.snapshot()

        fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync = true)

        val changes = fixture.store.applied
        val deletions = changes.flatMap(LocalPlaylistSyncMutation::addedSongDeletions)
        assertEquals(1, deletions.size)
        assertEquals(SongIdentity(42L, "netease", null), deletions.single().identity())
        assertEquals(listOf(originalToken), deletions.single().removedMembershipTokens)
        assertEquals(
            listOf(SongIdentity(43L, "netease", null)),
            changes.flatMap(LocalPlaylistSyncMutation::removedSongDeletions).single().identities
        )
        val updated = fixture.song()
        assertEquals(original.identity(), updated.identity())
        assertEquals(original.localFilePath, updated.localFilePath)
        assertNull(updated.sourceStableKey)
        assertEquals(original.addedAt, updated.addedAt)
        assertEquals(1, updated.syncMembershipTokens.orEmpty().size)
        assertNotEquals(original.syncMembershipTokens, updated.syncMembershipTokens)
        assertEquals(listOf(43L), mergeWithStaleRemote(fixture, staleRemote).map { it.id })
    }

    @Test
    fun `clearing or losing a supported match removes the old synchronized favorite`() = runTest {
        val replacements = listOf(
            matchedLocalSong().copy(matchedSongId = null, matchedLyricSource = null),
            matchedLocalSong().copy(matchedSongId = "77", matchedLyricSource = MusicPlatform.QQ_MUSIC),
            matchedLocalSong().copy(matchedSongId = "invalid")
        )
        for (replacement in replacements) {
            val fixture = fixture(matchedLocalSong())
            val original = fixture.song()
            val staleRemote = fixture.snapshot()

            fixture.repository.updateSongMetadata(original, replacement, triggerSync = true)

            val deletions = fixture.store.applied.flatMap(LocalPlaylistSyncMutation::addedSongDeletions)
            assertEquals(1, deletions.size)
            assertEquals(SongIdentity(42L, "netease", null), deletions.single().identity())
            assertEquals(listOf(originalToken), deletions.single().removedMembershipTokens)
            assertTrue(fixture.snapshot().songs.isEmpty())
            assertTrue(mergeWithStaleRemote(fixture, staleRemote).isEmpty())
            assertEquals(original.identity(), fixture.song().identity())
        }
    }

    @Test
    fun `automatic hydration preserves an established favorite association while updating other metadata`() = runTest {
        for (newMatch in listOf(null, "43")) {
            var scheduled = 0
            val fixture = fixture(matchedLocalSong(), autoSyncTrigger = { scheduled++ })
            val original = fixture.song()
            val initialFavorite = fixture.playlist()
            fixture.repository.updatePlaylists(listOf(
                initialFavorite, initialFavorite.copy(id = 7L, name = "Mix")
            ))
            val mutationVersion = fixture.store.mutationVersion
            val candidate = original.copy(
                matchedLyricSource = if (newMatch == null) null else MusicPlatform.CLOUD_MUSIC,
                matchedSongId = newMatch,
                customName = "Hydrated title"
            )

            fixture.repository.updateSongMetadata(original, candidate, triggerSync = false)

            val favorites = fixture.repository.playlists.value.single { it.id == FavoritesPlaylist.SYSTEM_ID }
            val ordinary = fixture.repository.playlists.value.single { it.id == 7L }
            assertEquals("42", favorites.songs.single().matchedSongId)
            assertEquals(MusicPlatform.CLOUD_MUSIC, favorites.songs.single().matchedLyricSource)
            assertEquals("Hydrated title", favorites.songs.single().customName)
            assertEquals(newMatch, ordinary.songs.single().matchedSongId)
            assertEquals(candidate.matchedLyricSource, ordinary.songs.single().matchedLyricSource)
            assertEquals(listOf(42L), SyncPlaylist.fromLocalPlaylist(favorites).songs.map { it.id })
            assertEquals(original.syncMembershipTokens, favorites.songs.single().syncMembershipTokens)
            assertTrue(fixture.store.applied.isEmpty())
            assertEquals(0, fixture.store.allocatedTokenCount)
            assertEquals(mutationVersion, fixture.store.mutationVersion)
            assertEquals(initialFavorite.modifiedAt, favorites.modifiedAt)
            assertEquals(0, scheduled)
        }
    }

    @Test
    fun `batch hydration preserves the favorite association from its current playlist entry`() = runTest {
        val fixture = fixture(matchedLocalSong())
        val favorite = fixture.playlist()
        val queueSong = fixture.song().copy(matchedSongId = "43")
        fixture.repository.updatePlaylists(listOf(
            favorite,
            favorite.copy(id = 7L, name = "Mix", songs = mutableListOf(queueSong))
        ))
        val mutationVersion = fixture.store.mutationVersion
        val candidate = queueSong.copy(
            matchedLyricSource = null, matchedSongId = null, customName = "Scanned title"
        )

        fixture.repository.applySongMetadataUpdates(listOf(
            LocalPlaylistRepository.SongMetadataUpdate(queueSong, candidate)
        ))

        val favorites = fixture.repository.playlists.value.single { it.id == FavoritesPlaylist.SYSTEM_ID }
        val ordinary = fixture.repository.playlists.value.single { it.id == 7L }
        assertEquals("42", favorites.songs.single().matchedSongId)
        assertEquals(MusicPlatform.CLOUD_MUSIC, favorites.songs.single().matchedLyricSource)
        assertEquals("Scanned title", favorites.songs.single().customName)
        assertEquals(favorite.modifiedAt, favorites.modifiedAt)
        assertEquals(favorite.songs.single().syncMembershipTokens, favorites.songs.single().syncMembershipTokens)
        assertNull(ordinary.songs.single().matchedSongId)
        assertNull(ordinary.songs.single().matchedLyricSource)
        assertEquals("Scanned title", ordinary.songs.single().customName)
        assertTrue(fixture.store.applied.isEmpty())
        assertEquals(0, fixture.store.allocatedTokenCount)
        assertEquals(mutationVersion, fixture.store.mutationVersion)
    }

    @Test
    fun `scanning an existing favorite preserves its established remote association`() = runTest {
        val candidates = listOf(
            localSong(99).copy(customName = "Raw scan"),
            matchedLocalSong().copy(matchedLyricSource = null, matchedSongId = null, customName = "Cleared scan"),
            matchedLocalSong().copy(matchedSongId = "43", customName = "Other match scan")
        )
        for (candidate in candidates) {
            val fixture = fixture(matchedLocalSong())
            val original = fixture.song()

            val result = fixture.repository.addScannedSongsToPlaylistWithResult(
                FavoritesPlaylist.SYSTEM_ID, listOf(candidate)
            )

            assertEquals(0, result.addedCount)
            val updated = fixture.song()
            assertEquals("42", updated.matchedSongId)
            assertEquals(MusicPlatform.CLOUD_MUSIC, updated.matchedLyricSource)
            assertEquals(candidate.customName, updated.customName)
            assertEquals(original.identity(), updated.identity())
            assertEquals(original.localFilePath, updated.localFilePath)
            assertEquals(original.addedAt, updated.addedAt)
            assertEquals(original.syncMembershipTokens, updated.syncMembershipTokens)
            assertNull(updated.sourceStableKey)
            assertEquals(listOf(42L), fixture.snapshot().songs.map { it.id })
            assertTrue(fixture.store.applied.isEmpty())
            assertEquals(0, fixture.store.allocatedTokenCount)
        }
    }

    @Test
    fun `scanning an existing ordinary playlist member still applies the scanned match`() = runTest {
        for (newMatch in listOf(null, "43")) {
            val fixture = fixture(matchedLocalSong(), playlistId = 7L)
            val original = fixture.song()
            val candidate = original.copy(
                matchedLyricSource = if (newMatch == null) null else MusicPlatform.CLOUD_MUSIC,
                matchedSongId = newMatch, customName = "Scanned title"
            )

            val result = fixture.repository.addScannedSongsToPlaylistWithResult(7L, listOf(candidate))

            assertEquals(0, result.addedCount)
            assertEquals(candidate.matchedLyricSource, fixture.song().matchedLyricSource)
            assertEquals(newMatch, fixture.song().matchedSongId)
            assertEquals("Scanned title", fixture.song().customName)
            assertEquals(original.identity(), fixture.song().identity())
            assertEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
            assertTrue(fixture.store.applied.isEmpty())
            assertEquals(0, fixture.store.allocatedTokenCount)
        }
    }

    @Test
    fun `scanning the first match into an existing favorite renews remote membership`() = runTest {
        val fixture = fixture(matchedLocalSong().copy(matchedLyricSource = null, matchedSongId = null))
        val original = fixture.song()

        val result = fixture.repository.addScannedSongsToPlaylistWithResult(
            FavoritesPlaylist.SYSTEM_ID,
            listOf(original.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "43"))
        )

        assertEquals(0, result.addedCount)
        assertEquals("43", fixture.song().matchedSongId)
        assertEquals(original.identity(), fixture.song().identity())
        assertEquals(original.addedAt, fixture.song().addedAt)
        assertNotEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
        assertEquals(1, fixture.store.allocatedTokenCount)
        assertTrue(appliedDeletions(fixture.store, listOf(deletion(43L))).isEmpty())
        assertEquals(listOf(43L), fixture.snapshot().songs.map { it.id })
        assertTrue(fixture.playlist().modifiedAt > 10L)
    }

    @Test
    fun `automatic hydration preserves the current favorite match when the queue has another match`() = runTest {
        val fixture = fixture(matchedLocalSong())
        val queueSong = fixture.song().copy(matchedSongId = "43")

        fixture.repository.updateSongMetadata(
            queueSong, queueSong.copy(matchedSongId = "44", customName = "Hydrated title")
        )

        assertEquals("42", fixture.song().matchedSongId)
        assertEquals("Hydrated title", fixture.song().customName)
        assertTrue(fixture.store.applied.isEmpty())
        assertEquals(0, fixture.store.allocatedTokenCount)
    }

    @Test
    fun `rematching into an existing favorite retains the local copy and both destination memberships`() = runTest {
        val store = RecordingSyncMutationStore()
        val repository = repository(store, normalizePlaylists = { playlists ->
            playlists.map { playlist ->
                if (playlist.id == FavoritesPlaylist.SYSTEM_ID) {
                    playlist.copy(songs = FavoritesPlaylist.mergedSongs(listOf(playlist)))
                } else playlist
            }
        })
        val destination = remoteNeteaseSong(43L).copy(syncMembershipTokens = listOf(staleDestinationToken))
        repository.updatePlaylists(listOf(LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID, name = "Favorites",
            songs = mutableListOf(matchedLocalSong(), destination), modifiedAt = 10L
        )))
        val original = repository.playlists.value.single().songs.single { it.matchedSongId == "42" }

        repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync = true)

        val merged = repository.playlists.value.single().songs.single()
        assertEquals(original.identity(), merged.identity())
        assertEquals(original.localFilePath, merged.localFilePath)
        assertEquals("43", merged.matchedSongId)
        assertEquals(2, merged.syncMembershipTokens.orEmpty().size)
        assertTrue(staleDestinationToken in merged.syncMembershipTokens.orEmpty())
        assertFalse(originalToken in merged.syncMembershipTokens.orEmpty())
        val deletion = store.applied.flatMap(LocalPlaylistSyncMutation::addedSongDeletions).single()
        assertEquals(42L, deletion.songId)
        assertEquals(listOf(originalToken), deletion.removedMembershipTokens)
        assertEquals(1, store.allocatedTokenCount)
    }

    @Test
    fun `legacy favorite rematching still removes its previous remote projection`() = runTest {
        val fixture = fixture(matchedLocalSong().copy(syncMembershipTokens = null))
        val original = fixture.song()
        val staleRemote = fixture.snapshot()

        fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync = true)

        val deletion = fixture.store.applied.flatMap(LocalPlaylistSyncMutation::addedSongDeletions).single()
        assertEquals(42L, deletion.songId)
        assertTrue(deletion.removedMembershipTokens.isEmpty())
        assertEquals(listOf(43L), mergeWithStaleRemote(fixture, staleRemote).map { it.id })
    }

    @Test
    fun `ordinary metadata edits preserve membership tokens and the requested sync behavior`() = runTest {
        for (triggerSync in listOf(false, true)) {
            var scheduled = 0
            val fixture = fixture(matchedLocalSong(), autoSyncTrigger = { scheduled++ })
            val original = fixture.song()
            val allocatedTokens = fixture.store.allocatedTokenCount
            val mutationVersion = fixture.store.mutationVersion
            val modifiedAt = fixture.playlist().modifiedAt

            fixture.repository.updateSongMetadata(
                original, original.copy(customName = "Updated title"), triggerSync = triggerSync
            )

            assertEquals("Updated title", fixture.song().customName)
            assertEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
            assertEquals(allocatedTokens, fixture.store.allocatedTokenCount)
            assertTrue(fixture.store.applied.isEmpty())
            assertEquals(if (triggerSync) 1 else 0, scheduled)
            assertEquals(if (triggerSync) mutationVersion + 1L else mutationVersion, fixture.store.mutationVersion)
            if (!triggerSync) assertEquals(modifiedAt, fixture.playlist().modifiedAt)
        }
    }

    @Test
    fun `ordinary playlists never migrate remote membership for a local lyric rematch`() = runTest {
        for (triggerSync in listOf(false, true)) {
            val fixture = fixture(matchedLocalSong(), playlistId = 7L)
            val original = fixture.song()

            fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync)

            assertEquals("43", fixture.song().matchedSongId)
            assertEquals(original.identity(), fixture.song().identity())
            assertEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
            assertEquals(0, fixture.store.allocatedTokenCount)
            assertTrue(fixture.store.applied.isEmpty())
            assertTrue(fixture.snapshot().songs.isEmpty())
        }
    }

    @Test
    fun `existing remote provenance and unsupported matches do not migrate favorite membership`() = runTest {
        val local = matchedLocalSong()
        val originals = listOf(
            local.copy(sourceStableKey = "77|netease|", explicitLocalSourceReference = local.localFilePath),
            local.copy(sourceStableKey = "77|bilibili|", explicitLocalSourceReference = local.localFilePath),
            local.copy(channelId = "netease", audioId = "77", sourceStableKey = null),
            local.copy(channelId = "bilibili", audioId = "77", sourceStableKey = null),
            remoteNeteaseSong(77L).copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42", syncMembershipTokens = listOf(originalToken)),
            remoteNeteaseSong(77L).copy(channelId = "bilibili", audioId = "77", matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42", syncMembershipTokens = listOf(originalToken)),
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            local.copy(matchedSongId = "invalid")
        )
        for (candidate in originals) {
            val fixture = fixture(candidate)
            val original = fixture.song()
            val nextMatch = if (original.matchedSongId == "invalid") "0" else "43"

            fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = nextMatch), triggerSync = true)

            assertEquals(original.identity(), fixture.song().identity())
            assertEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
            assertEquals(0, fixture.store.allocatedTokenCount)
            assertTrue(fixture.store.applied.isEmpty())
        }
    }

    @Test
    fun `a newly matched favorite clears destination legacy deletion and survives causal removals`() = runTest {
        val fixture = fixture(matchedLocalSong().copy(matchedSongId = null, matchedLyricSource = null))
        val original = fixture.song()
        val staleRemote = SyncPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "Favorites",
            songs = listOf(SyncSong(id = 43L, album = "netease", channelId = "netease", audioId = "43", syncMembershipTokens = listOf(staleDestinationToken)))
        )
        val destinationLegacy = deletion(43L)
        val destinationCausal = destinationLegacy.copy(removedMembershipTokens = listOf(staleDestinationToken))
        val priorAliasCausal = deletion(42L).copy(removedMembershipTokens = listOf(originalToken))
        val priorDeletions = listOf(destinationLegacy, destinationCausal, priorAliasCausal)

        fixture.repository.updateSongMetadata(
            original, original.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "43"), triggerSync = true
        )

        val deletions = appliedDeletions(fixture.store, priorDeletions)
        assertFalse(deletions.any { it.songId == 43L && it.removedMembershipTokens.isEmpty() })
        assertTrue(deletions.any { it.songId == 43L && it.removedMembershipTokens == listOf(staleDestinationToken) })
        assertEquals(1, fixture.store.allocatedTokenCount)
        assertNotEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
        val merged = mergeWithStaleRemote(fixture, staleRemote, priorDeletions)
        assertEquals(listOf(43L), merged.map { it.id })
        assertEquals(fixture.song().syncMembershipTokens, merged.single().syncMembershipTokens)
    }

    @Test
    fun `an automatic first match renews remote membership and clears the destination legacy deletion`() = runTest {
        var scheduled = 0
        val fixture = fixture(
            matchedLocalSong().copy(matchedSongId = null, matchedLyricSource = null),
            autoSyncTrigger = { scheduled++ }
        )
        val original = fixture.song()
        val mutationVersion = fixture.store.mutationVersion

        fixture.repository.updateSongMetadata(
            original, original.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "43"), triggerSync = false
        )

        assertEquals(1, fixture.store.allocatedTokenCount)
        assertNotEquals(original.syncMembershipTokens, fixture.song().syncMembershipTokens)
        assertTrue(appliedDeletions(fixture.store, listOf(deletion(43L))).isEmpty())
        assertEquals(listOf(43L), fixture.snapshot().songs.map { it.id })
        assertTrue(fixture.store.mutationVersion > mutationVersion)
        assertEquals(1, scheduled)
    }

    @Test
    fun `a metadata membership migration replays its complete outbox after apply failure`() = runTest {
        val storage = RecordingStorage(primary = null)
        val failingStore = RecordingSyncMutationStore(failApply = true)
        val fixture = fixture(matchedLocalSong(), store = failingStore, storage = storage)
        val original = fixture.song()

        fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync = true)

        assertTrue(fixture.repository.syncMutationPending.value)
        assertTrue(storage.pendingSyncMutation != null)
        val tokens = fixture.song().syncMembershipTokens
        val recoveredStore = RecordingSyncMutationStore()
        val recovered = repository(recoveredStore, storage)
        assertEquals("43", recovered.playlists.value.single().songs.single().matchedSongId)
        assertEquals(tokens, recovered.playlists.value.single().songs.single().syncMembershipTokens)
        assertEquals(0, recoveredStore.allocatedTokenCount)
        val mutation = recoveredStore.applied.single()
        assertEquals(listOf(42L), mutation.addedSongDeletions.map { it.songId })
        assertEquals(listOf(SongIdentity(43L, "netease", null)), mutation.removedSongDeletions.single().identities)
        assertNull(storage.pendingSyncMutation)
        assertFalse(recovered.syncMutationPending.value)
    }

    @Test
    fun `failed metadata persistence cannot publish migration tombstones or replace the favorite`() = runTest {
        val storage = RecordingStorage(primary = null)
        val fixture = fixture(matchedLocalSong(), storage = storage)
        val original = fixture.song()
        storage.failCommit = true

        val failure = runCatching {
            fixture.repository.updateSongMetadata(original, original.copy(matchedSongId = "43"), triggerSync = true)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(original, fixture.song())
        assertTrue(fixture.store.applied.isEmpty())
        storage.failCommit = false
        val recoveredStore = RecordingSyncMutationStore()
        val recovered = repository(recoveredStore, storage)
        assertEquals("42", recovered.playlists.value.single().songs.single().matchedSongId)
        assertTrue(recoveredStore.applied.isEmpty())
    }

    private fun deletion(songId: Long) = SyncPlaylistSongDeletion(
        playlistId = FavoritesPlaylist.SYSTEM_ID, songId = songId, album = "netease",
        deletedAt = 1_000L, deviceId = "other-device"
    )

    private fun appliedDeletions(
        store: RecordingSyncMutationStore,
        initial: List<SyncPlaylistSongDeletion> = emptyList()
    ): List<SyncPlaylistSongDeletion> = store.applied.fold(initial) { current, mutation ->
        var deletions = SyncPlaylistDeletionPolicy.mergeDeletions(current, mutation.addedSongDeletions)
        mutation.removedSongDeletions.forEach { removal ->
            deletions = SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(
                deletions, removal.playlistId, removal.identities
            )
        }
        deletions
    }

    private fun mergeWithStaleRemote(
        fixture: Fixture,
        staleRemote: SyncPlaylist,
        priorDeletions: List<SyncPlaylistSongDeletion> = emptyList()
    ): List<SyncSong> {
        val current = fixture.snapshot()
        val merged = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = current.songs,
            remoteSongs = staleRemote.songs,
            localModifiedAt = current.modifiedAt,
            remoteModifiedAt = staleRemote.modifiedAt,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 0L,
            isFavorites = true
        )
        return SyncPlaylistDeletionPolicy.applyDeletions(
            FavoritesPlaylist.SYSTEM_ID, merged.songs, appliedDeletions(fixture.store, priorDeletions)
        )
    }

    private suspend fun fixture(
        song: SongItem,
        playlistId: Long = FavoritesPlaylist.SYSTEM_ID,
        store: RecordingSyncMutationStore = RecordingSyncMutationStore(),
        storage: RecordingStorage? = null,
        autoSyncTrigger: (() -> Unit)? = null
    ): Fixture {
        val repository = repository(store, storage, autoSyncTrigger)
        repository.updatePlaylists(listOf(LocalPlaylist(
            id = playlistId, name = "Favorites", songs = mutableListOf(song), modifiedAt = 10L
        )))
        return Fixture(repository, store)
    }

    private fun repository(
        store: RecordingSyncMutationStore,
        storage: RecordingStorage? = null,
        autoSyncTrigger: (() -> Unit)? = null,
        normalizePlaylists: (List<LocalPlaylist>) -> List<LocalPlaylist> = { it }
    ): LocalPlaylistRepository {
        val file = File(tempFolder.root, "matched_metadata-${fixtureIndex++}.json")
        return LocalPlaylistRepository.createForTest(
            context = mockContext(), file = file, normalizePlaylists = normalizePlaylists,
            autoSyncEnabled = autoSyncTrigger != null,
            storage = storage ?: LocalPlaylistFileStorage(file, tempFolder.root),
            syncMutationStore = store, autoSyncTrigger = autoSyncTrigger
        )
    }

    private fun matchedLocalSong() = localSong(99).copy(
        channelId = "local", audioId = "99", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42", addedAt = 10L, syncMembershipTokens = listOf(originalToken)
    )

    private class Fixture(
        val repository: LocalPlaylistRepository,
        val store: RecordingSyncMutationStore
    ) {
        fun playlist(): LocalPlaylist = repository.playlists.value.single()
        fun song(): SongItem = playlist().songs.single()
        fun snapshot(): SyncPlaylist = SyncPlaylist.fromLocalPlaylist(playlist())
    }
}
