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
    fun uncommittedLyricOnlyRestoreKeepsDeletionOnRepositoryStartup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = digestPlaylist()
        val song = original.songs.single()
        val uncommittedSongs = listOf(
            song.copy(lyricSyncRevision = 7L),
            song.copy(lyricSyncEdited = false),
            song.copy(lyricSyncEdited = true)
        )
        for (uncommittedSong in uncommittedSongs) {
            val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val uncommitted = original.copy(songs = mutableListOf(uncommittedSong))
                val roomStore = LocalPlaylistRoomStore(database)
                roomStore.replacePlaylists(
                    listOf(original), LocalPlaylistRoomStore.domainDigest(listOf(original))
                )
                // 模拟 outbox 已落盘、歌单事务尚未提交时退出进程
                roomStore.writePendingSyncMutationOutbox(
                    LocalPlaylistSyncMutationOutbox(
                        mutations = listOf(
                            LocalPlaylistSyncMutation(
                                expectedPrimaryDigest = LocalPlaylistRoomStore.domainDigest(listOf(uncommitted)),
                                restoredPlaylistIds = listOf(original.id)
                            )
                        )
                    )
                )
                val syncStore = RecordingSyncMutationStore(setOf(original.id))
                val storage = EmptyStorage()

                val repository = LocalPlaylistRepository.createForTest(
                    context = context,
                    file = File(context.cacheDir, "uncommitted_lyric_restore_unused.json"),
                    normalizePlaylists = { it }, autoSyncEnabled = false,
                    storage = storage, syncMutationStore = syncStore, roomStore = roomStore
                )

                assertEquals(listOf(original), repository.playlists.value)
                assertEquals(listOf(original), roomStore.readPlaylists())
                assertTrue(syncStore.applied.isEmpty())
                assertEquals(0L, syncStore.mutationVersion)
                assertEquals(setOf(original.id), syncStore.deletedPlaylistIds)
                assertNull(roomStore.readPendingSyncMutationOutbox())
                assertTrue(storage.pendingCleared)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun committedLyricRestoreReplaysAndKeepsPersistedSyncFields() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val original = digestPlaylist()
            val committed = original.copy(
                songs = mutableListOf(original.songs.single().copy(lyricSyncRevision = 7L, lyricSyncEdited = true))
            )
            val digest = LocalPlaylistRoomStore.domainDigest(listOf(committed))
            val roomStore = LocalPlaylistRoomStore(database)
            roomStore.replacePlaylists(listOf(committed), digest)
            val mutation = LocalPlaylistSyncMutation(
                expectedPrimaryDigest = digest, restoredPlaylistIds = listOf(committed.id)
            )
            roomStore.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox(listOf(mutation)))
            val syncStore = RecordingSyncMutationStore(setOf(committed.id))

            val repository = LocalPlaylistRepository.createForTest(
                context = context,
                file = File(context.cacheDir, "committed_lyric_restore_unused.json"),
                normalizePlaylists = { it }, autoSyncEnabled = false,
                storage = EmptyStorage(), syncMutationStore = syncStore, roomStore = roomStore
            )

            assertEquals(listOf(committed), repository.playlists.value)
            assertEquals(listOf(committed), roomStore.readPlaylists())
            assertEquals(listOf(mutation), syncStore.applied)
            assertEquals(1L, syncStore.mutationVersion)
            assertTrue(syncStore.deletedPlaylistIds.isEmpty())
            assertNull(roomStore.readPendingSyncMutationOutbox())
        } finally {
            database.close()
        }
    }

    @Test
    fun committedLegacyDefaultDigestStillReplaysRestore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val committed = digestPlaylist()
            val legacyDigest = "aeb25c7c366ccec9db03a8b2f4aa2d66b0fa3e1ea60245ab5eac33a77c9d20d0"
            val roomStore = LocalPlaylistRoomStore(database)
            roomStore.replacePlaylists(listOf(committed), legacyDigest)
            val mutation = LocalPlaylistSyncMutation(
                expectedPrimaryDigest = legacyDigest, restoredPlaylistIds = listOf(committed.id)
            )
            roomStore.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox(listOf(mutation)))
            val syncStore = RecordingSyncMutationStore(setOf(committed.id))

            val repository = LocalPlaylistRepository.createForTest(
                context = context,
                file = File(context.cacheDir, "legacy_digest_restore_unused.json"),
                normalizePlaylists = { it }, autoSyncEnabled = false,
                storage = EmptyStorage(), syncMutationStore = syncStore, roomStore = roomStore
            )

            assertEquals(listOf(committed), repository.playlists.value)
            assertEquals(listOf(mutation), syncStore.applied)
            assertEquals(1L, syncStore.mutationVersion)
            assertTrue(syncStore.deletedPlaylistIds.isEmpty())
            assertNull(roomStore.readPendingSyncMutationOutbox())
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

    private fun digestPlaylist() = LocalPlaylist(
        701L, "restore",
        mutableListOf(SongItem(17L, "song", "artist", "netease", 0L, 180_000L, null)),
        modifiedAt = 4_000L
    )

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

    private class RecordingSyncMutationStore(
        initialDeletedPlaylistIds: Set<Long> = emptySet()
    ) : LocalPlaylistSyncMutationStore {
        val applied = mutableListOf<LocalPlaylistSyncMutation>()
        val deletedPlaylistIds = initialDeletedPlaylistIds.toMutableSet()
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
            deletedPlaylistIds.removeAll(mutation.restoredPlaylistIds.orEmpty().toSet())
        }
    }
}
