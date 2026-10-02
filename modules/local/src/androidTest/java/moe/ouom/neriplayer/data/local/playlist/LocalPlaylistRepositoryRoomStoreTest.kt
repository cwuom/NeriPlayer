package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPlaylistRepositoryRoomStoreTest {
    @Test
    fun failedRoomAndFallbackMarkerKeepOldPrimaryAcrossRetriesAndRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val initial = LocalPlaylist(id = 601L, name = "initial")
            val remote = initial.copy(name = "remote")
            val roomStore = LocalPlaylistRoomStore(database)
            roomStore.replacePlaylists(listOf(initial), LocalPlaylistRoomStore.domainDigest(listOf(initial)))
            val storage = RecordingStorage()
            val syncStore = RecordingSyncMutationStore()
            val repository = LocalPlaylistRepository.createForTest(
                context = context, file = File(context.cacheDir, "failed_room_fallback_unused.json"),
                normalizePlaylists = { it }, autoSyncEnabled = false,
                storage = storage, syncMutationStore = syncStore, roomStore = roomStore
            )
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "CREATE TRIGGER reject_playlist_write BEFORE INSERT ON local_playlist " +
                    "BEGIN SELECT RAISE(ABORT, 'injected playlist failure'); END"
            )
            sqlite.execSQL(
                "CREATE TRIGGER reject_playlist_fallback BEFORE INSERT ON migration_metadata " +
                    "WHEN NEW.key = 'local_playlist_cutover_state' AND NEW.value = 'legacy_json' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected marker failure'); END"
            )

            repeat(2) {
                assertNotNull(runCatching {
                    repository.applySyncedPlaylistsIfUnchanged(listOf(remote), 0L)
                }.exceptionOrNull())
                assertEquals(listOf(initial), repository.playlists.value)
                assertEquals(listOf(initial), roomStore.readIfRoomPrimary())
                assertTrue(repository.roomStorageEnabled)
                assertEquals(0L, syncStore.mutationVersion)
                assertTrue(syncStore.applied.isEmpty())
            }
            assertEquals(2, storage.commitCount)
            assertTrue(requireNotNull(storage.primary).contains("remote"))
            val restarted = LocalPlaylistRepository.createForTest(
                context = context, file = File(context.cacheDir, "failed_room_fallback_unused.json"),
                normalizePlaylists = { it }, autoSyncEnabled = false,
                storage = storage, syncMutationStore = syncStore, roomStore = roomStore
            )
            assertEquals(listOf(initial), restarted.playlists.value)

            sqlite.execSQL("DROP TRIGGER reject_playlist_fallback")
            assertTrue(repository.applySyncedPlaylistsIfUnchanged(listOf(remote), 0L))
            assertEquals(listOf(remote), repository.playlists.value)
            assertFalse(roomStore.isRoomPrimary())
            assertEquals(listOf(initial), roomStore.readPlaylists())

            val recovered = LocalPlaylistRepository.createForTest(
                context = context, file = File(context.cacheDir, "failed_room_fallback_unused.json"),
                normalizePlaylists = { it }, autoSyncEnabled = false,
                storage = storage, syncMutationStore = syncStore, roomStore = roomStore
            )
            assertEquals(listOf(remote), recovered.playlists.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomPrimaryOutboxReplaysAndClearsOnRepositoryStartup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val playlists = listOf(LocalPlaylist(id = 401L, name = "restored"))
            val committedDigest = LocalPlaylistRoomStore.domainDigest(playlists)
            val roomStore = LocalPlaylistRoomStore(database)
            val pendingMutation = LocalPlaylistSyncMutation(
                expectedPrimaryDigest = committedDigest,
                restoredPlaylistIds = listOf(401L)
            )
            roomStore.replacePlaylists(playlists, committedDigest)
            roomStore.writePendingSyncMutationOutbox(
                LocalPlaylistSyncMutationOutbox(mutations = listOf(pendingMutation))
            )
            val syncStore = RecordingSyncMutationStore()
            val storage = EmptyStorage()

            val repository = LocalPlaylistRepository.createForTest(
                context = context,
                file = File(context.cacheDir, "room_primary_outbox_unused.json"),
                normalizePlaylists = { it },
                autoSyncEnabled = false,
                storage = storage,
                syncMutationStore = syncStore,
                roomStore = roomStore
            )

            assertEquals(playlists, repository.playlists.value)
            assertEquals(listOf(pendingMutation), syncStore.applied)
            assertEquals(1L, syncStore.mutationVersion)
            assertNull(roomStore.readPendingSyncMutationOutbox())
            assertTrue(storage.pendingCleared)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomPrimaryNormalizesLegacyLocalFilesCoverBeforePublishing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val legacyLocalFiles = LocalPlaylist(
                id = LocalFilesPlaylist.SYSTEM_ID,
                name = LocalFilesPlaylist.currentName(context),
                songs = mutableListOf(
                    SongItem(
                        id = 501L,
                        name = "local",
                        artist = "artist",
                        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                        albumId = 0L,
                        durationMs = 1_000L,
                        coverUrl = "content://covers/current.jpg",
                        mediaUri = "content://media/external/audio/media/501",
                        channelId = "local",
                        audioId = "501"
                    )
                ),
                customCoverUrl = "file:///covers/stale-local.jpg"
            )
            val roomStore = LocalPlaylistRoomStore(database)
            roomStore.replacePlaylists(
                playlists = listOf(legacyLocalFiles),
                sourceDigest = LocalPlaylistRoomStore.domainDigest(listOf(legacyLocalFiles))
            )

            val repository = LocalPlaylistRepository.createForTest(
                context = context,
                file = File(context.cacheDir, "room_primary_normalized_cover_unused.json"),
                normalizePlaylists = { it },
                autoSyncEnabled = false,
                storage = EmptyStorage(),
                roomStore = roomStore
            )

            assertNull(
                LocalFilesPlaylist.firstOrNull(repository.playlists.value, context)?.customCoverUrl
            )
            assertNull(
                LocalFilesPlaylist.firstOrNull(roomStore.readPlaylists(), context)?.customCoverUrl
            )
        } finally {
            database.close()
        }
    }

    private class EmptyStorage : LocalPlaylistStorage {
        var pendingCleared = false

        override fun readPrimary(): String? = null

        override fun readBackup(): String? = null

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) = Unit

        override fun quarantinePrimary(): File? = null

        override fun clearPendingSyncMutation() {
            pendingCleared = true
        }
    }

    private class RecordingStorage : LocalPlaylistStorage {
        var primary: String? = null
        var commitCount = 0

        override fun readPrimary(): String? = primary

        override fun readBackup(): String? = null

        override fun commit(text: String, rotateBackup: Boolean, replaceBackupWithCommittedPrimary: Boolean) {
            primary = text
            commitCount++
        }

        override fun quarantinePrimary(): File? = null
    }

    private class RecordingSyncMutationStore : LocalPlaylistSyncMutationStore {
        val applied = mutableListOf<LocalPlaylistSyncMutation>()
        var mutationVersion = 0L
            private set
        private var nextCounter = 1L

        override fun getOrCreateDeviceId(): String = "room-replay-device"

        override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
            require(count >= 0)
            return List(count) {
                SyncCausalToken(getOrCreateDeviceId(), nextCounter++)
            }
        }

        override fun getSyncMutationVersion(): Long = mutationVersion

        override fun markSyncMutation(): Long {
            mutationVersion += 1L
            return mutationVersion
        }

        override fun apply(mutation: LocalPlaylistSyncMutation) {
            applied += mutation
        }
    }
}
