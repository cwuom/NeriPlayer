package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutation
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutationOutbox
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistRoomStoreTest {
    @Test
    fun incrementalWriteRemovesMembershipWithoutDeletingSharedTrack() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val song = SongItem(
                id = 101L,
                name = "shared",
                artist = "artist",
                album = "album",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = null
            )
            val initial = listOf(
                LocalPlaylist(
                    id = 1L,
                    name = "first",
                    songs = mutableListOf(song)
                ),
                LocalPlaylist(
                    id = 2L,
                    name = "second",
                    songs = mutableListOf(song)
                )
            )
            val store = LocalPlaylistRoomStore(database)
            store.replacePlaylists(initial, LocalPlaylistRoomStore.domainDigest(initial))

            val next = listOf(initial[1])
            store.writeIncremental(
                previous = initial,
                next = next,
                sourceDigest = LocalPlaylistRoomStore.domainDigest(next)
            )

            assertEquals(next, store.readIfRoomPrimary())
            assertEquals(1, database.localPlaylistDao().getTracks().size)
            assertEquals(1, database.localPlaylistDao().getMembers().size)
            assertNotNull(
                database.syncMetadataDao().getMigrationMetadata(
                    LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingSyncOutboxRoundTripsInRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = LocalPlaylistRoomStore(database)
            val outbox = LocalPlaylistSyncMutationOutbox(
                mutations = listOf(
                    LocalPlaylistSyncMutation(
                        expectedPrimaryDigest = "digest"
                    )
                )
            )
            store.writePendingSyncMutationOutbox(outbox)

            assertEquals(outbox, store.readPendingSyncMutationOutbox())

            store.clearPendingSyncMutationOutbox()
            assertEquals(null, store.readPendingSyncMutationOutbox())
        } finally {
            database.close()
        }
    }
}
