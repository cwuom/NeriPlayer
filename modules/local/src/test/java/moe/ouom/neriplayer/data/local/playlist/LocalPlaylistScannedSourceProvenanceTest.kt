package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.google.gson.Gson
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.identity.toSyncableRemoteSongOrNull
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomMapper
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPreviewAuthority
import moe.ouom.neriplayer.data.local.database.store.mockLocalPlaylistRoomStore
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.mapping.SyncLocalRestoreMapping
import moe.ouom.neriplayer.data.sync.mapping.fromLocalPlaylist
import moe.ouom.neriplayer.data.sync.mapping.fromSongItemOrNull
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.anyList
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`

class LocalPlaylistScannedSourceProvenanceTest : LocalPlaylistRepositoryTestSupport() {
    private val reference = "content://provider/tree/scanned/document/Imported.flac"

    @Test
    fun `creating an ordinary playlist from scans persists the exact selected source`() = runTest {
        val storage = storage()
        val repository = repository(storage)

        val created = repository.createPlaylistWithScannedSongs("Scanned", listOf(scannedSong()))

        assertSelectedSource(created.songs.single())
        assertSelectedSource(requireNotNull(repository.readFastPlaylist(created.id)).songs.single())
        assertEquals(true, repository.containsExplicitLocalFileReferenceCached(reference))
        val reopened = repository(storage)
        assertSelectedSource(reopened.playlists.value.single { it.id == created.id }.songs.single())
        assertEquals(true, reopened.containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `adding scans to an ordinary playlist preserves source evidence after reopening`() = runTest {
        val storage = storage(playlist(12L))
        val repository = repository(storage)

        val added = repository.addScannedSongsToPlaylistWithResult(12L, listOf(scannedSong()))

        assertEquals(1, added.addedCount)
        assertSelectedSource(added.addedSongs.single())
        assertSelectedSource(requireNotNull(repository.readFastPlaylist(12L)).songs.single())
        assertEquals(true, repository(storage).containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `explicit rescanning replaces an existing remote source without adding a duplicate`() = runTest {
        val oldRemote = scannedSong().copy(mediaUri = null, localFileName = null)
        val storage = storage(playlist(12L, oldRemote))
        val repository = repository(storage)
        val before = repository.playlists.value.single { it.id == 12L }.songs.single()

        val added = repository.addScannedSongsToPlaylistWithResult(12L, listOf(scannedSong()))

        assertEquals(0, added.addedCount)
        val restored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertSelectedSource(restored)
        assertEquals(before.addedAt, restored.addedAt)
        assertEquals(before.syncMembershipTokens, restored.syncMembershipTokens)
        assertEquals(true, repository(storage).containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `explicit rescanning replaces an unmarked legacy local source for the same remote identity`() = runTest {
        val previous = legacyLocalSongWithRemoteIdentity()
        val repository = roomRepository(playlist(12L, previous))
        assertEquals(previous.mediaUri, repository.playlists.value.single { it.id == 12L }.songs.single().mediaUri)

        val added = repository.addScannedSongsToPlaylistWithResult(12L, listOf(scannedSong()))

        assertEquals(0, added.addedCount)
        val restored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertSelectedSource(restored)
        assertNull(restored.localFilePath)
        assertEquals(previous.addedAt, restored.addedAt)
        assertEquals(previous.membershipAddedAtMs, restored.membershipAddedAtMs)
        assertEquals(previous.syncMembershipTokens, restored.syncMembershipTokens)
        assertEquals(true, repository.containsExplicitLocalFileReferenceCached(reference))
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(requireNotNull(previous.mediaUri)))
    }

    @Test
    fun `explicit rescanning replaces a legacy local source whose saved marker is invalid`() = runTest {
        val previous = legacyLocalSongWithRemoteIdentity().copy(explicitLocalSourceReference = reference)
        val repository = roomRepository(playlist(12L, previous))
        assertEquals(previous.mediaUri, repository.playlists.value.single { it.id == 12L }.songs.single().mediaUri)
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference))

        val added = repository.addScannedSongsToPlaylistWithResult(12L, listOf(scannedSong()))

        assertEquals(0, added.addedCount)
        val restored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertSelectedSource(restored)
        assertNull(restored.localFilePath)
        assertEquals(previous.addedAt, restored.addedAt)
        assertEquals(previous.membershipAddedAtMs, restored.membershipAddedAtMs)
        assertEquals(previous.syncMembershipTokens, restored.syncMembershipTokens)
        assertEquals(true, repository.containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `prepared imports cannot replace an unmarked legacy local source using a selected file marker`() = runTest {
        val previous = legacyLocalSongWithRemoteIdentity()
        val repository = roomRepository(playlist(12L, previous))

        val added = repository.addPreparedSongsToPlaylistWithResult(
            12L, listOf(scannedSong().copy(explicitLocalSourceReference = reference))
        )

        assertEquals(0, added.addedCount)
        val restored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertNull(restored.mediaUri)
        assertNull(restored.localFilePath)
        assertNull(restored.explicitLocalSourceReference)
        assertEquals(previous.addedAt, restored.addedAt)
        assertEquals(previous.membershipAddedAtMs, restored.membershipAddedAtMs)
        assertEquals(previous.syncMembershipTokens, restored.syncMembershipTokens)
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `rescanning another local file keeps the previously selected source and its evidence`() = runTest {
        val storage = storage()
        val repository = repository(storage)
        val created = repository.createPlaylistWithScannedSongs("Selected", listOf(scannedSong()))
        val before = requireNotNull(repository.readFastPlaylist(created.id)).songs.single()
        val replacementReference = "content://provider/tree/other/document/Replacement.flac"

        val added = repository.addScannedSongsToPlaylistWithResult(
            created.id,
            listOf(scannedSong().copy(mediaUri = replacementReference, localFileName = "Replacement.flac"))
        )

        assertEquals(0, added.addedCount)
        val restored = requireNotNull(repository.readFastPlaylist(created.id)).songs.single()
        assertSelectedSource(restored)
        assertEquals(before.addedAt, restored.addedAt)
        assertEquals(before.membershipAddedAtMs, restored.membershipAddedAtMs)
        assertEquals(before.syncMembershipTokens, restored.syncMembershipTokens)
        val reopened = repository(storage)
        assertSelectedSource(reopened.playlists.value.single { it.id == created.id }.songs.single())
        assertEquals(true, reopened.containsExplicitLocalFileReferenceCached(reference))
        assertEquals(false, reopened.containsExplicitLocalFileReferenceCached(replacementReference))
    }

    @Test
    fun `ordinary metadata refresh retains evidence for the same selected file`() {
        val repository = repository(storage())
        val selected = scannedSong().copy(explicitLocalSourceReference = reference)

        val refreshed = repository.mergeSongMetadataForPersistence(
            selected,
            selected.copy(name = "Updated title", explicitLocalSourceReference = null)
        )

        assertEquals("Updated title", refreshed.name)
        assertSelectedSource(refreshed)
    }

    @Test
    fun `same remote identity cannot transfer selected source evidence to another local file`() {
        val repository = repository(storage())
        val managedReference = "content://provider/tree/old-managed/document/Downloaded.flac"
        val managed = scannedSong().copy(mediaUri = managedReference)
        val selected = scannedSong().copy(explicitLocalSourceReference = reference)

        val merged = repository.mergeSongMetadataForPersistence(managed, selected)

        assertEquals(managedReference, merged.mediaUri)
        assertNull(merged.explicitLocalSourceReference)
    }

    @Test
    fun `generic metadata updates cannot replace a remote source using another saved local marker`() {
        val repository = repository(storage())
        val remote = scannedSong().copy(mediaUri = null, localFileName = null)
        val selected = scannedSong().copy(explicitLocalSourceReference = reference)

        val merged = repository.mergeSongMetadataForPersistence(remote, selected)

        assertNull(merged.mediaUri)
        assertNull(merged.localFilePath)
        assertNull(merged.explicitLocalSourceReference)
    }

    @Test
    fun `generic imports cannot convert remote playlist members into explicit local sources`() = runTest {
        val oldRemote = scannedSong().copy(mediaUri = null, localFileName = null)
        val repository = repository(storage(playlist(12L, oldRemote)))

        repository.addPreparedSongsToPlaylistWithResult(12L, listOf(scannedSong()))

        val stored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertNull(stored.mediaUri)
        assertNull(stored.explicitLocalSourceReference)
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `ordinary unmarked stale and network references never establish scan evidence`() {
        val oldReference = "content://provider/tree/old-managed/document/Old.flac"
        val network = "https://example.test/audio.flac"
        val cases = listOf(
            scannedSong(),
            scannedSong().copy(channelId = "local", sourceStableKey = null),
            scannedSong().copy(explicitLocalSourceReference = oldReference),
            scannedSong().copy(mediaUri = network, explicitLocalSourceReference = network)
        )
        cases.forEach { candidate ->
            val repository = repository(storage(playlist(12L, candidate)))
            assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference))
            assertEquals(false, repository.containsExplicitLocalFileReferenceCached(oldReference))
            assertEquals(false, repository.containsExplicitLocalFileReferenceCached(network))
        }
    }

    @Test
    fun `room member and track payloads preserve selected sources without new columns`() {
        val mapper = LocalPlaylistRoomMapper()
        val selected = scannedSong().copy(explicitLocalSourceReference = reference)
        val snapshot = mapper.toSnapshot(listOf(playlist(12L, selected)))

        val fromMember = mapper.toDomain(snapshot.playlists, emptyList(), snapshot.members, snapshot.memberTokens)
        val fromTrack = mapper.toDomain(
            snapshot.playlists, snapshot.tracks,
            snapshot.members.map { it.copy(memberPayloadJson = "null") }, snapshot.memberTokens
        )

        assertSelectedSource(fromMember.single().songs.single())
        assertSelectedSource(fromTrack.single().songs.single())
        val legacy = mapper.toSnapshot(listOf(playlist(12L, scannedSong())))
        assertNull(mapper.toDomain(legacy.playlists, legacy.tracks, legacy.members, legacy.memberTokens)
            .single().songs.single().explicitLocalSourceReference)
    }

    @Test
    fun `remote synchronization discards file provenance along with the local reference`() {
        val selected = scannedSong().copy(explicitLocalSourceReference = reference)

        val projected = requireNotNull(selected.toSyncableRemoteSongOrNull())

        assertNull(projected.mediaUri)
        assertNull(projected.localFilePath)
        assertNull(projected.explicitLocalSourceReference)
        assertEquals("netease", projected.channelId)
        assertNull(projected.copy(explicitLocalSourceReference = reference)
            .toSyncableRemoteSongOrNull()?.explicitLocalSourceReference)
    }

    @Test
    fun `serialized sync songs exclude scan provenance and every local source address`() {
        val localPath = "/storage/emulated/0/Imported/selected.flac"
        val selected = scannedSong().copy(
            localFilePath = localPath,
            explicitLocalSourceReference = reference
        )

        val exported = requireNotNull(SyncSong.fromSongItemOrNull(selected))
        val serialized = Gson().toJson(exported)

        assertEquals(859470L, exported.id)
        assertEquals("netease", exported.channelId)
        assertNull(exported.mediaUri)
        assertFalse(serialized.contains("explicitLocalSourceReference"))
        assertFalse(serialized.contains(reference))
        assertFalse(serialized.contains(localPath))
    }

    @Test
    fun `sync roundtrip retains a scanned source for the same ordinary playlist member`() = runTest {
        val storage = storage()
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(storage, syncStore)
        val localPath = "/storage/emulated/0/Imported/selected.flac"
        val created = repository.createPlaylistWithScannedSongs(
            "Selected", listOf(scannedSong().copy(localFilePath = localPath))
        )
        val exported = SyncPlaylist.fromLocalPlaylist(created, modifiedAt = 2_000L)
        val remote = exported.copy(songs = exported.songs.map {
            it.copy(customName = "Remote edit", userLyricOffsetMs = 81L)
        })

        applySyncRoundTrip(repository, syncStore, remote)

        val stored = requireNotNull(repository.readFastPlaylist(created.id)).songs.single()
        assertSelectedSource(stored)
        assertEquals(localPath, stored.localFilePath)
        assertEquals("Imported.flac", stored.localFileName)
        assertEquals("Remote edit", stored.customName)
        assertEquals(81L, stored.userLyricOffsetMs)
        assertEquals(remote.songs.single().syncMembershipTokens, stored.syncMembershipTokens)
        val reopened = repository(storage)
        assertSelectedSource(requireNotNull(reopened.readFastPlaylist(created.id)).songs.single())
        assertEquals(true, reopened.containsExplicitLocalFileReferenceCached(reference))
        val reexported = Gson().toJson(SyncPlaylist.fromLocalPlaylist(reopened.playlists.value.single {
            it.id == created.id
        }))
        assertFalse(reexported.contains("explicitLocalSourceReference"))
        assertFalse(reexported.contains(reference))
        assertFalse(reexported.contains(localPath))
    }

    @Test
    fun `sync member deletion removes a scanned remote member while retaining local only songs`() = runTest {
        val storage = storage()
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(storage, syncStore)
        val localOnlyReference = "content://provider/tree/scanned/document/LocalOnly.flac"
        val localOnly = scannedSong().copy(
            id = 17L,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            mediaUri = localOnlyReference,
            channelId = "local",
            audioId = "17",
            sourceStableKey = null
        )
        val created = repository.createPlaylistWithScannedSongs("Selected", listOf(scannedSong(), localOnly))
        val exported = SyncPlaylist.fromLocalPlaylist(created, modifiedAt = 2_000L)
        assertEquals(1, exported.songs.size)

        applySyncRoundTrip(repository, syncStore, exported.copy(songs = emptyList()))

        val stored = requireNotNull(repository.readFastPlaylist(created.id)).songs.single()
        assertEquals(17L, stored.id)
        assertEquals(localOnlyReference, stored.mediaUri)
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference))
        assertEquals(true, repository.containsExplicitLocalFileReferenceCached(localOnlyReference))
        assertEquals(listOf(17L), requireNotNull(repository(storage).readFastPlaylist(created.id)).songs.map { it.id })
    }

    @Test
    fun `sync playlist deletion cannot lend a scanned source to another playlist`() = runTest {
        val storage = storage()
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(storage, syncStore)
        val created = repository.createPlaylistWithScannedSongs("Selected", listOf(scannedSong()))
        val exported = SyncPlaylist.fromLocalPlaylist(created, modifiedAt = 2_000L)
        val otherPlaylistId = created.id + 1L

        applySyncRoundTrip(
            repository, syncStore,
            exported.copy(isDeleted = true),
            exported.copy(id = otherPlaylistId, name = "Other")
        )

        assertNull(repository.readFastPlaylist(created.id))
        val other = requireNotNull(repository.readFastPlaylist(otherPlaylistId)).songs.single()
        assertNull(other.mediaUri)
        assertNull(other.localFilePath)
        assertNull(other.explicitLocalSourceReference)
        val reopened = repository(storage)
        assertNull(reopened.readFastPlaylist(created.id))
        assertEquals(false, reopened.containsExplicitLocalFileReferenceCached(reference))
    }

    @Test
    fun `sync roundtrip leaves unmarked managed members as remote projections`() = runTest {
        val storage = storage(playlist(12L, scannedSong()))
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(storage, syncStore)
        val existing = requireNotNull(repository.readFastPlaylist(12L))
        val exported = SyncPlaylist.fromLocalPlaylist(existing, modifiedAt = 2_000L)

        applySyncRoundTrip(repository, syncStore, exported)

        val stored = requireNotNull(repository.readFastPlaylist(12L)).songs.single()
        assertEquals(859470L, stored.id)
        assertEquals("netease", stored.channelId)
        assertNull(stored.mediaUri)
        assertNull(stored.localFilePath)
        assertNull(stored.explicitLocalSourceReference)
        assertEquals(false, repository(storage).containsExplicitLocalFileReferenceCached(reference))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun applySyncRoundTrip(
        repository: LocalPlaylistRepository,
        syncStore: RecordingSyncMutationStore,
        vararg playlists: SyncPlaylist
    ) {
        val data = ProtoBuf.decodeFromByteArray<SyncData>(
            ProtoBuf.encodeToByteArray(SyncData(playlists = playlists.toList()))
        )
        val originalLocale = Locale.getDefault()
        try {
            val restored = SyncLocalRestoreMapping(syncMappingContext()).playlists(data, repository.playlists.value)
            assertTrue(repository.applySyncedPlaylistsIfUnchanged(restored, syncStore.mutationVersion))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private fun syncMappingContext(): Context {
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales.size()).thenReturn(1)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        return mockContext().apply {
            `when`(applicationContext).thenReturn(mock(Context::class.java))
            `when`(getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
            `when`(this.resources).thenReturn(resources)
            `when`(createConfigurationContext(any(Configuration::class.java))).thenReturn(this)
        }
    }

    private fun assertSelectedSource(song: SongItem) {
        assertEquals(reference, song.mediaUri)
        assertEquals(reference, song.explicitLocalSourceReference)
        assertEquals("netease", song.channelId)
        assertEquals("859470|netease|", song.sourceStableKey)
    }

    private fun scannedSong() = SongItem(
        id = 859470L,
        name = "Imported title",
        artist = "Imported artist",
        album = "Imported album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = reference,
        localFileName = "Imported.flac",
        channelId = "netease",
        audioId = "859470",
        sourceStableKey = "859470|netease|",
        addedAt = 1_000L
    )

    private fun legacyLocalSongWithRemoteIdentity() = scannedSong().copy(
        mediaUri = "content://provider/tree/old-managed/document/Legacy.flac",
        localFileName = "Legacy.flac",
        localFilePath = "/storage/emulated/0/OldManaged/Legacy.flac",
        addedAt = 123L,
        membershipAddedAtMs = 456L,
        syncMembershipTokens = listOf(SyncCausalToken(deviceId = "legacy-device", counter = 42L))
    )

    private suspend fun roomRepository(initial: LocalPlaylist): LocalPlaylistRepository {
        val room = mockLocalPlaylistRoomStore()
        var persisted = listOf(playlist(LocalFilesPlaylist.SYSTEM_ID), initial)
        `when`(room.readIfRoomPrimary()).thenAnswer { persisted }
        `when`(room.readFastPlaylistAuthority(anyLong())).thenAnswer { invocation ->
            LocalPlaylistPreviewAuthority.RoomPrimary(persisted.firstOrNull {
                it.id == invocation.getArgument<Long>(0)
            })
        }
        doAnswer { invocation ->
            persisted = invocation.getArgument(1)
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        return LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "legacy_scanned_source.json"),
            storage = RecordingStorage(primary = null),
            syncMutationStore = RecordingSyncMutationStore(),
            roomStore = room
        )
    }

    private fun playlist(id: Long, vararg songs: SongItem) = LocalPlaylist(
        id = id,
        name = if (id == LocalFilesPlaylist.SYSTEM_ID) "Local Files" else "Other",
        songs = songs.toMutableList(),
        modifiedAt = 1_000L,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )

    private fun storage(vararg playlists: LocalPlaylist) = RecordingStorage(
        primary = Gson().toJson(listOf(playlist(LocalFilesPlaylist.SYSTEM_ID)) + playlists)
    )

    private fun repository(
        storage: RecordingStorage,
        syncStore: RecordingSyncMutationStore = RecordingSyncMutationStore()
    ) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "scanned_source.json"),
        storage = storage,
        syncMutationStore = syncStore
    )
}
