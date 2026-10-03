package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistFastPreviewAuthorityTest {
    @Test
    fun previewSeparatesUnmigratedAndMissingRoomPrimary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val storage = LocalPlaylistRoomStore(database)
            assertEquals(LocalPlaylistPreviewAuthority.Unmigrated, storage.readFastPlaylistAuthority(2))
            storage.replacePlaylists(emptyList())
            assertEquals(LocalPlaylistPreviewAuthority.RoomPrimary(null), storage.readFastPlaylistAuthority(2))
            storage.markLegacyJsonPrimary("legacy")
            assertEquals(LocalPlaylistPreviewAuthority.Unmigrated, storage.readFastPlaylistAuthority(2))
        } finally {
            database.close()
        }
    }

    @Test
    fun previewReturnsOnlyTheRequestedPlaylistFromRoomPrimary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val storage = LocalPlaylistRoomStore(database)
            val requested = LocalPlaylist(id = 2, name = "primary", songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION, modifiedAt = 100)
            val other = requested.copy(id = 3, name = "other")
            storage.replacePlaylists(listOf(requested, other))
            assertEquals(LocalPlaylistPreviewAuthority.RoomPrimary(requested), storage.readFastPlaylistAuthority(2))
            assertEquals(LocalPlaylistPreviewAuthority.RoomPrimary(null), storage.readFastPlaylistAuthority(4))
        } finally {
            database.close()
        }
    }
}
