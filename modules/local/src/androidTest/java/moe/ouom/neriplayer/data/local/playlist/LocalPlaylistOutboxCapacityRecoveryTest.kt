package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistOutboxCapacityRecoveryTest {
    @Test fun roomReadPreservesTheCompleteOrderedChainPastTheFirstPage() = runTest {
        open().use { fixture ->
            fixture.writeChain()

            val recovered = checkNotNull(fixture.room.readPendingSyncMutationOutbox())

            assertEquals(257, recovered.mutations.size)
            assertEquals((1L..257L).toList(), recovered.mutations.map { it.addedSongDeletions.single().songId })
            assertEquals(fixture.digest, recovered.mutations.last().expectedPrimaryDigest)
            assertEquals(257L, fixture.pendingCount())
        }
    }

    @Test fun repositoryStartupReplaysTheTailTombstoneAndPreventsOldProviderReturn() = runTest {
        open().use { fixture ->
            fixture.writeChain()

            val repository = fixture.restart()

            assertTrue(repository.awaitInitialized())
            assertEquals((1L..257L).toSet(), fixture.mutations.deletions().map { it.songId }.toSet())
            assertEquals(0L, fixture.pendingCount())
            assertFalse(repository.syncMutationPending.value)
            assertTailCannotReturn(fixture.mutations.deletions())
            val appliedCount = fixture.mutations.applied.size
            assertTrue(fixture.restart().awaitInitialized())
            assertEquals(appliedCount, fixture.mutations.applied.size)
        }
    }

    @Test fun replayFailureOnTheTailKeepsItsDurableRecordForTheNextStartup() = runTest {
        open().use { fixture ->
            fixture.writeChain()
            fixture.mutations.rejectTail = true

            val interrupted = fixture.restart()

            assertFalse(interrupted.awaitInitialized())
            assertTrue(interrupted.syncMutationPending.value)
            assertTrue(fixture.pendingCount() > 0L)
            assertTrue(fixture.hasPendingTail())
            assertFalse(fixture.mutations.deletions().any { it.songId == 257L })
            fixture.mutations.rejectTail = false
            val recovered = fixture.restart()
            assertTrue(recovered.awaitInitialized())
            assertEquals((1L..257L).toSet(), fixture.mutations.deletions().map { it.songId }.toSet())
            assertEquals(0L, fixture.pendingCount())
            assertFalse(recovered.syncMutationPending.value)
            assertTailCannotReturn(fixture.mutations.deletions())
        }
    }

    @Test fun invalidFirstPagePayloadCannotBeSilentlyDroppedFromADurableChain() = runTest {
        open().use { fixture ->
            fixture.writeChain()
            fixture.corruptPayload(last = false)

            val failure = runCatching { fixture.room.readPendingSyncMutationOutbox() }.exceptionOrNull()

            assertNotNull("Unreadable durable mutations must fail the read", failure)
            assertEquals(257L, fixture.pendingCount())
            assertTrue(fixture.hasPendingTail())
        }
    }

    @Test fun unreadableTailRecoveryCannotAcknowledgeAnEmptyOutboxOrClearTheChain() = runTest {
        open().use { fixture ->
            fixture.writeChain()
            fixture.corruptPayload(last = true)
            val previousLegacyClears = fixture.storage.pendingClears
            val repository = fixture.restart()

            assertFalse("Unknown pending chain must not become sync ready", repository.awaitInitialized())
            assertEquals(257L, fixture.pendingCount())
            assertEquals(previousLegacyClears, fixture.storage.pendingClears)
            assertTrue(fixture.mutations.applied.isEmpty())
            val recoveryFailure = runCatching {
                repository.recoverPendingSyncMutation(fixture.digest)
            }.exceptionOrNull()
            assertTrue("Unknown durable recovery must propagate its failure", recoveryFailure is IOException)
            assertEquals(257L, fixture.pendingCount())
            assertEquals(previousLegacyClears, fixture.storage.pendingClears)
            fixture.restorePayload()
            assertTrue(repository.awaitInitialized())
            assertEquals((1L..257L).toSet(), fixture.mutations.deletions().map { it.songId }.toSet())
            assertEquals(0L, fixture.pendingCount())
            assertTailCannotReturn(fixture.mutations.deletions())
        }
    }

    private suspend fun open(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        val preferenceName = "outbox-capacity-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, preferenceName)
        try {
            check(directory.mkdirs())
            val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            val constructor = SecureTokenStorage::class.java.declaredConstructors.single { candidate ->
                candidate.parameterTypes.size == 3 && candidate.parameterTypes.first() == SharedPreferences::class.java
            }.also { it.isAccessible = true }
            val syncDirectory: (File) -> Unit = { path ->
                val descriptor = Os.open(path.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            }
            val tokens = checkNotNull(SecureTokenStorage::class.java.cast(constructor.newInstance(preferences, directory, syncDirectory)))
            val playlists = listOf(LocalPlaylist(id = 7L, name = "committed", modifiedAt = 300L))
            val room = LocalPlaylistRoomStore(database)
            val digest = LocalPlaylistRoomStore.domainDigest(playlists)
            room.replacePlaylists(playlists, digest)
            return Fixture(context, database, room, digest, tokens, preferenceName, directory)
        } catch (failure: Throwable) {
            try { database.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            context.deleteSharedPreferences(preferenceName)
            directory.deleteRecursively()
            throw failure
        }
    }

    private class Fixture(val context: Context, val database: NeriUserDataDatabase,
        val room: LocalPlaylistRoomStore, val digest: String, tokens: SecureTokenStorage,
        private val preferenceName: String, private val directory: File) : Closeable {
        val storage = EmptyLegacyStorage()
        val mutations = RecordingMutationStore(tokens)
        private val file = File(context.cacheDir, "outbox-capacity-${UUID.randomUUID()}.json")
        private var corrupted: Pair<Long, String>? = null

        suspend fun writeChain() {
            room.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox((1L..257L).map { id ->
                LocalPlaylistSyncMutation(
                    expectedPrimaryDigest = if (id == 257L) digest else LocalPlaylistRoomStore.domainDigest(
                        listOf(LocalPlaylist(id = 7L, name = "previous-$id", modifiedAt = id))
                    ),
                    addedSongDeletions = listOf(SyncPlaylistSongDeletion(
                        playlistId = 7L, songId = id, album = "netease", deletedAt = 200L,
                        deviceId = "offline-device", removedMembershipTokens = listOf(SyncCausalToken("offline-device", id))
                    ))
                )
            }))
        }

        fun restart(): LocalPlaylistRepository = LocalPlaylistRepository.createForTest(
            context = context, file = file, normalizePlaylists = { it }, autoSyncEnabled = false,
            storage = storage, syncMutationStore = mutations, roomStore = room
        )

        fun pendingCount(): Long = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_outbox WHERE status = 'pending'"
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

        fun hasPendingTail(): Boolean = database.openHelper.readableDatabase.query(
            "SELECT mutation_payload_json FROM sync_outbox WHERE status = 'pending' ORDER BY sequence DESC LIMIT 1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) false else Gson().fromJson(cursor.getString(0), LocalPlaylistSyncMutation::class.java)
                .addedSongDeletions.any { it.songId == 257L }
        }

        fun corruptPayload(last: Boolean) {
            val order = if (last) "DESC" else "ASC"
            corrupted = database.openHelper.readableDatabase.query(
                "SELECT sequence, mutation_payload_json FROM sync_outbox WHERE status = 'pending' ORDER BY sequence $order LIMIT 1"
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) to cursor.getString(1) }
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_outbox SET mutation_payload_json = '{' WHERE sequence = ?",
                arrayOf(checkNotNull(corrupted).first)
            )
        }

        fun restorePayload() {
            val (sequence, payload) = checkNotNull(corrupted)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_outbox SET mutation_payload_json = ? WHERE sequence = ?", arrayOf<Any>(payload, sequence)
            )
        }

        override fun close() {
            try { database.close() } finally {
                context.deleteSharedPreferences(preferenceName)
                check(directory.deleteRecursively())
            }
        }
    }

    private class EmptyLegacyStorage : LocalPlaylistStorage {
        var pendingClears = 0
        override fun readPrimary(): String? = null
        override fun readBackup(): String? = null
        override fun commit(text: String, rotateBackup: Boolean, replaceBackupWithCommittedPrimary: Boolean) =
            error("Room authority must not fall back to an unrelated JSON snapshot")
        override fun quarantinePrimary(): File? = null
        override fun clearPendingSyncMutation() { pendingClears++ }
    }

    private class RecordingMutationStore(private val tokens: SecureTokenStorage) : LocalPlaylistSyncMutationStore {
        private val delegate = SecureLocalPlaylistSyncMutationStore(tokens)
        val applied = mutableListOf<LocalPlaylistSyncMutation>()
        var rejectTail = false
        override fun getOrCreateDeviceId(): String = delegate.getOrCreateDeviceId()
        override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> = delegate.nextSyncCausalTokens(count)
        override fun getSyncMutationVersion(): Long = delegate.getSyncMutationVersion()
        override fun markSyncMutation(): Long = delegate.markSyncMutation()
        override fun apply(mutation: LocalPlaylistSyncMutation) {
            rejectIfNecessary(mutation)
            delegate.apply(mutation)
            applied += mutation
        }
        override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
            rejectIfNecessary(mutation)
            val version = delegate.applyAndMarkMutation(mutation)
            applied += mutation
            return version
        }
        private fun rejectIfNecessary(mutation: LocalPlaylistSyncMutation) {
            if (rejectTail && mutation.addedSongDeletions.any { it.songId == 257L }) {
                throw IOException("Injected durable tombstone commit failure")
            }
        }
        fun deletions(): List<SyncPlaylistSongDeletion> = tokens.getPlaylistSongDeletions()
    }

    private fun assertTailCannotReturn(deletions: List<SyncPlaylistSongDeletion>) {
        val host = object : SyncMergeHost {
            override val favoritesPlaylistId = -1L
            override val mergeSuccessMessage = "merged"
            override val initialUploadMessage = "initial"
            override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = null
            override fun localRenameMessage(name: String): String = name
            override fun remoteRenameMessage(name: String): String = name
        }
        val local = SyncData(playlists = listOf(SyncPlaylist(id = 7L, name = "committed", modifiedAt = 300L)),
            playlistSongDeletions = deletions)
        val oldProvider = SyncData(playlists = listOf(SyncPlaylist(id = 7L, name = "committed", modifiedAt = 100L,
            songs = listOf(SyncSong(id = 257L, name = "removed tail", album = "netease", addedAt = 100L,
                syncMembershipTokens = listOf(SyncCausalToken("offline-device", 257L)))))))
        val merger = SyncDataMerger(host) { 400L }
        val merged = merger.merge(local, oldProvider, 50L).mergedData
        assertTrue(merged.playlists.single().songs.isEmpty())
        assertTrue(merged.playlistSongDeletions.any { it.songId == 257L })
        assertTrue(merger.merge(merged, oldProvider, 50L).mergedData.playlists.single().songs.isEmpty())
    }
}
