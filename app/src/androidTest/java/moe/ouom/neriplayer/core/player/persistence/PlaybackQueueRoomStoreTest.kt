package moe.ouom.neriplayer.core.player.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedSongItem
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
                shuffleRestoreIndex = 0,
                sourceRoute = "local_playlist_detail/42"
            )
            val store = PlaybackQueueRoomStore(database)

            store.replaceSnapshot(state, now = 10L)
            store.importLegacyAndPromote(
                PersistedState(playlist = emptyList(), index = 0),
                now = 20L
            )

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
            assertNull(
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.SOURCE_ROUTE_METADATA_KEY
                )
            )
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
    fun sourceRouteSurvivesDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playback_queue_room_store_${System.nanoTime()}.db"
        val firstDatabase = Room.databaseBuilder(
            context,
            NeriUserDataDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()

        try {
            PlaybackQueueRoomStore(firstDatabase).replaceSnapshot(
                PersistedState(
                    playlist = listOf(song(1L, "first")),
                    index = 0,
                    sourceRoute = "local_playlist_detail/42"
                ),
                now = 10L
            )
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(
            context,
            NeriUserDataDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        try {
            assertEquals(
                "local_playlist_detail/42",
                PlaybackQueueRoomStore(reopenedDatabase)
                    .readIfRoomPrimary()
                    ?.sourceRoute
            )
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun oversizedSourceRouteClearsPreviousValue() = runTest {
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
                    sourceRoute = "local_playlist_detail/42"
                ),
                now = 10L
            )
            store.updateSourceRoute("x".repeat(16 * 1024 + 1), now = 20L)

            assertNull(store.readIfRoomPrimary()?.sourceRoute)
            assertNull(
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.SOURCE_ROUTE_METADATA_KEY
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun routeOnlyUpdateDoesNotPromoteRoomBeforeQueueCutover() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = PlaybackQueueRoomStore(database)
            store.updateSourceRoute("local_playlist_detail/42", now = 10L)

            assertFalse(store.isRoomPrimary())
            assertNull(store.readIfRoomPrimary())
            assertEquals(
                "local_playlist_detail/42",
                database.syncMetadataDao().getMigrationMetadata(
                    PlaybackQueueRoomStore.SOURCE_ROUTE_METADATA_KEY
                )?.value
            )
        } finally {
            database.close()
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
}
