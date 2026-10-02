package moe.ouom.neriplayer.core.player.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.persistence.withPlaybackState
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PlaybackQueueRoomStoreTest {
    @Test
    fun snapshotRoundTripKeepsQueueOrderAndPlaybackFields() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val first = song(1L, "first")
            val second = song(2L, "second")
            val state = PersistedState(
                playlist = listOf(first, second),
                index = 1,
                mediaUrl = "https://example.test/stream",
                positionMs = 4_200L,
                shouldResumePlayback = true,
                repeatMode = 2,
                shuffleEnabled = true,
                shuffleRestorePlaylist = listOf(second, first),
                shuffleRestoreIndex = 0
            )
            val store = PlaybackQueueRoomStore(database)

            store.replaceSnapshot(state, now = 10L)

            assertEquals(state, store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }

    @Test
    fun playbackOnlyUpdatePreservesShuffleRestoreIndex() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = PlaybackQueueRoomStore(database)
            store.replaceSnapshot(
                PersistedState(
                    playlist = listOf(song(1L, "first")),
                    index = 0,
                    shuffleEnabled = true,
                    shuffleRestorePlaylist = listOf(song(1L, "first")),
                    shuffleRestoreIndex = 3
                ),
                now = 10L
            )

            store.updatePlaybackState(
                PersistedPlaybackState(
                    index = 0,
                    positionMs = 7_000L,
                    shouldResumePlayback = true,
                    shuffleEnabled = true
                ),
                now = 20L
            )

            assertEquals(3, database.playbackQueueDao().getState()?.shuffleRestoreIndex)
            assertEquals(7_000L, store.readIfRoomPrimary()?.positionMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun clearRemovesStateButKeepsRoomPrimaryMarker() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = PlaybackQueueRoomStore(database)
            store.replaceSnapshot(
                PersistedState(playlist = listOf(song(1L, "first")), index = 0)
            )
            store.clear()

            assertNull(database.playbackQueueDao().getState())
            assertTrue(
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY
                )?.value == PlaybackQueueRoomStore.ROOM_PRIMARY_STATE
            )
            assertNull(store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }

    @Test
    fun roomSnapshotFailureFallsBackToLegacyJsonAndMarksLegacyPrimary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val dir = File(context.cacheDir, "playback-queue-fallback-${System.nanoTime()}")
        val legacyStore = PlaybackQueueLegacyStore(
            stateFile = File(dir, "last_playlist.json"),
            playbackStateFile = File(dir, "last_playback_state.json"),
            gson = Gson()
        )

        try {
            val state = PersistedState(
                playlist = listOf(song(1L, "first"), song(2L, "second")),
                index = 0,
                mediaUrl = "https://example.test/old",
                positionMs = 1_000L,
                shouldResumePlayback = false,
                repeatMode = 0,
                shuffleEnabled = false
            )
            val playbackState = PersistedPlaybackState(
                index = 1,
                mediaUrl = "https://example.test/current",
                positionMs = 8_000L,
                shouldResumePlayback = true,
                repeatMode = 2,
                shuffleEnabled = true
            )
            val failingStore = FailingPlaybackQueueStateStore(database)

            val target = persistPlaybackQueueWithRoomFallback(
                roomStore = failingStore,
                legacyStore = legacyStore,
                queueState = state,
                playbackState = playbackState,
                shouldWriteQueueState = true,
                shouldWritePlaybackState = true
            )

            assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, target)
            assertEquals(1, failingStore.replaceCalls)
            assertEquals(state.withPlaybackState(playbackState), legacyStore.read())
            assertEquals(
                PlaybackQueueRoomStore.LEGACY_JSON_STATE,
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY
                )?.value
            )
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun markerFailureLeavesJsonReadableAndIdenticalSnapshotRetriesUntilRoomRecovers() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val dir = File(context.cacheDir, "playback-queue-marker-failure-${System.nanoTime()}")
        val legacyStore = PlaybackQueueLegacyStore(
            stateFile = File(dir, "last_playlist.json"),
            playbackStateFile = File(dir, "last_playback_state.json"),
            gson = Gson()
        )

        try {
            val roomStore = PlaybackQueueRoomStore(database)
            val oldState = PersistedState(
                playlist = listOf(song(1L, "old-room")),
                index = 0
            )
            roomStore.replaceSnapshot(oldState, now = 10L)
            val snapshot = PlaybackStatePersistenceSnapshot(
                queue = PlayerQueueSnapshot.from(
                    listOf(SongItem(3L, "marker-failure", "artist", "album", 10L, 180_000L, null)),
                    currentIndex = 0
                ),
                playback = PersistedPlaybackState(index = 0, positionMs = 3_000L),
                shuffleRestorePlaylist = null,
                shuffleRestoreIndex = -1
            )
            val state = snapshot.toPersistedState()
            val failingStore = RecoverablePlaybackQueueStateStore(roomStore)
            val writer = PlaybackStateWriter()

            val failure = runCatching { writer.write(snapshot, failingStore, legacyStore) }
                .exceptionOrNull()

            assertSame(failingStore.markerFailure, failure)
            assertEquals(state, legacyStore.read())
            assertTrue(roomStore.isRoomPrimary())
            assertEquals(oldState, roomStore.readIfRoomPrimary())
            assertEquals(
                state,
                selectRestoredPlaybackState(
                    roomPrimary = roomStore.isRoomPrimary(),
                    roomSnapshot = roomStore.readSnapshotIfRoomPrimary(),
                    legacySnapshot = PlaybackQueueLegacySnapshot(state, legacyStore.lastModified())
                )
            )

            failingStore.failMarker = false
            assertEquals(
                PlaybackQueuePersistTarget.LEGACY_JSON,
                writer.write(snapshot, failingStore, legacyStore)
            )
            assertEquals(state, legacyStore.read())
            assertEquals(
                PlaybackQueueRoomStore.LEGACY_JSON_STATE,
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY
                )?.value
            )

            failingStore.failRoomWrites = false
            assertEquals(
                PlaybackQueuePersistTarget.ROOM,
                writer.write(snapshot, failingStore, legacyStore)
            )
            assertEquals(state, roomStore.readIfRoomPrimary())
            assertNull(legacyStore.read())
            assertEquals(3, failingStore.replaceCalls)
            assertEquals(
                PlaybackQueuePersistTarget.NONE,
                writer.write(snapshot, failingStore, legacyStore)
            )
            assertEquals(3, failingStore.replaceCalls)
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun restoredPlaybackStatePrefersLegacyJsonAtOrAfterRoomTimestampWhenMarkerStayedPrimary() {
        val oldRoomState = PersistedState(
            playlist = listOf(song(1L, "old-room")),
            index = 0,
            positionMs = 1_000L
        )
        val newerLegacyState = PersistedState(
            playlist = listOf(song(2L, "newer-legacy")),
            index = 0,
            positionMs = 2_000L
        )

        for (legacyUpdatedAt in listOf(100L, 200L)) {
            val restored = selectRestoredPlaybackState(
                roomPrimary = true,
                roomSnapshot = PlaybackQueueRoomSnapshot(
                    state = oldRoomState,
                    updatedAt = 100L
                ),
                legacySnapshot = PlaybackQueueLegacySnapshot(
                    state = newerLegacyState,
                    updatedAt = legacyUpdatedAt
                )
            )

            assertEquals("legacy timestamp $legacyUpdatedAt", newerLegacyState, restored)
        }
    }

    @Test
    fun successfulRoomRewriteClearsLegacyJsonAtEqualTimestampAndAfterClockRollback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val dir = File(context.cacheDir, "playback-queue-timestamp-recovery-${System.nanoTime()}")
        val stateFile = File(dir, "last_playlist.json")
        val playbackStateFile = File(dir, "last_playback_state.json")
        val legacyStore = PlaybackQueueLegacyStore(stateFile, playbackStateFile, Gson())

        try {
            val roomStore = PlaybackQueueRoomStore(database)
            val legacyState = PersistedState(
                playlist = listOf(song(1L, "old-legacy")),
                index = 0,
                positionMs = 1_000L
            )
            val rewrittenState = PersistedState(
                playlist = listOf(song(2L, "rewritten-room")),
                index = 0,
                positionMs = 2_000L
            )
            val legacyUpdatedAt = 100_000L

            for (roomUpdatedAt in listOf(legacyUpdatedAt, 50_000L)) {
                legacyStore.write(
                    legacyState,
                    PersistedPlaybackState(index = 0, positionMs = 1_000L)
                )
                assertTrue(stateFile.setLastModified(legacyUpdatedAt))
                assertTrue(playbackStateFile.setLastModified(legacyUpdatedAt))
                assertEquals(legacyUpdatedAt, legacyStore.lastModified())
                val fixedTimestampStore = object : PlaybackQueueStateStore by roomStore {
                    override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
                        roomStore.replaceSnapshot(state, roomUpdatedAt)
                    }
                }

                assertEquals(
                    PlaybackQueuePersistTarget.ROOM,
                    persistPlaybackQueueWithRoomFallback(
                        roomStore = fixedTimestampStore,
                        legacyStore = legacyStore,
                        queueState = rewrittenState,
                        playbackState = PersistedPlaybackState(index = 0, positionMs = 2_000L),
                        shouldWriteQueueState = true,
                        shouldWritePlaybackState = true
                    )
                )

                val roomSnapshot = roomStore.readSnapshotIfRoomPrimary()
                assertEquals(roomUpdatedAt, roomSnapshot?.updatedAt)
                val legacySnapshot = legacyStore.read()?.let {
                    PlaybackQueueLegacySnapshot(it, legacyStore.lastModified())
                }
                assertNull(legacySnapshot)
                assertFalse(stateFile.exists())
                assertFalse(playbackStateFile.exists())
                assertEquals(
                    "Room timestamp $roomUpdatedAt",
                    rewrittenState,
                    selectRestoredPlaybackState(roomStore.isRoomPrimary(), roomSnapshot, legacySnapshot)
                )
            }
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun noOpPersistDoesNotClearNonEmptyQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "playback-queue-noop-${System.nanoTime()}")
        val legacyStore = PlaybackQueueLegacyStore(
            stateFile = File(dir, "last_playlist.json"),
            playbackStateFile = File(dir, "last_playback_state.json"),
            gson = Gson()
        )
        val store = TrackingPlaybackQueueStateStore()

        try {
            val target = persistPlaybackQueueWithRoomFallback(
                roomStore = store,
                legacyStore = legacyStore,
                queueState = PersistedState(
                    playlist = listOf(song(1L, "first")),
                    index = 0
                ),
                playbackState = PersistedPlaybackState(index = 0),
                shouldWriteQueueState = false,
                shouldWritePlaybackState = false
            )

            assertEquals(PlaybackQueuePersistTarget.NONE, target)
            assertEquals(0, store.clearCalls)
            assertEquals(0, store.replaceCalls)
            assertEquals(0, store.updateCalls)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun song(id: Long, name: String): PersistedSongItem {
        return PersistedSongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            albumId = 10L,
            durationMs = 180_000L,
            coverUrl = null,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            channelId = "netease",
            audioId = id.toString()
        )
    }

    private class FailingPlaybackQueueStateStore(
        private val database: NeriUserDataDatabase
    ) : PlaybackQueueStateStore {
        var replaceCalls = 0

        override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
            replaceCalls += 1
            throw IOException("forced Room snapshot failure")
        }

        override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long) {
            throw IOException("forced Room playback failure")
        }

        override suspend fun clear(now: Long) {
            throw IOException("forced Room clear failure")
        }

        override suspend fun markLegacyJsonPrimary(now: Long) {
            database.syncMetadataDao().upsertMigrationMetadata(
                MigrationMetadataEntity(
                    key = PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY,
                    value = PlaybackQueueRoomStore.LEGACY_JSON_STATE,
                    updatedAt = now
                )
            )
        }
    }

    private class TrackingPlaybackQueueStateStore : PlaybackQueueStateStore {
        var replaceCalls = 0
        var updateCalls = 0
        var clearCalls = 0

        override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
            replaceCalls += 1
        }

        override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long) {
            updateCalls += 1
        }

        override suspend fun clear(now: Long) {
            clearCalls += 1
        }

        override suspend fun markLegacyJsonPrimary(now: Long) = Unit
    }

    private class RecoverablePlaybackQueueStateStore(
        private val store: PlaybackQueueRoomStore
    ) : PlaybackQueueStateStore {
        val markerFailure = IOException("forced Room marker failure")
        var failRoomWrites = true
        var failMarker = true
        var replaceCalls = 0

        override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
            replaceCalls += 1
            if (failRoomWrites) throw IOException("forced Room snapshot failure")
            store.replaceSnapshot(state, now)
        }

        override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long) {
            if (failRoomWrites) throw IOException("forced Room playback failure")
            store.updatePlaybackState(state, now)
        }

        override suspend fun clear(now: Long) {
            if (failRoomWrites) throw IOException("forced Room clear failure")
            store.clear(now)
        }

        override suspend fun markLegacyJsonPrimary(now: Long) {
            if (failMarker) throw markerFailure
            store.markLegacyJsonPrimary(now)
        }
    }
}
