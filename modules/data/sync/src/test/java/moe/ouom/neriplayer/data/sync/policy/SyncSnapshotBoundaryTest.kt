package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.playlist.normalizedForDisplayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSnapshotBoundaryTest {
    @Test
    fun `minimal identity accepts audio or media without a numeric id`() {
        for (text in listOf(null, "", " ")) {
            assertFalse(SyncSong(audioId = text, mediaUri = text).hasResolvableSyncIdentity())
            assertFalse(SyncRecentPlayDeletion(mediaUri = text).hasResolvableSyncIdentity())
            assertFalse(SyncPlaylistSongDeletion(mediaUri = text).hasResolvableSyncIdentity())
        }
        assertTrue(SyncSong(id = 1).hasResolvableSyncIdentity())
        assertTrue(SyncSong(audioId = "audio").hasResolvableSyncIdentity())
        assertTrue(SyncSong(mediaUri = "remote").hasResolvableSyncIdentity())
        assertTrue(SyncRecentPlayDeletion(songId = 1).hasResolvableSyncIdentity())
        assertTrue(SyncRecentPlayDeletion(mediaUri = "remote").hasResolvableSyncIdentity())
        assertTrue(SyncPlaylistSongDeletion(songId = 1).hasResolvableSyncIdentity())
        assertTrue(SyncPlaylistSongDeletion(mediaUri = "remote").hasResolvableSyncIdentity())
    }

    @Test
    fun `local URI schemes remain excluded from sync covers`() {
        for (value in listOf("/storage/a", "FILE:/tmp/a", "CONTENT://media/a", "ANDROID.RESOURCE://app/a")) {
            assertTrue(isLocalMediaUri(value))
            assertEquals(null, sanitizeCoverUrlForSync(value))
        }
        for (value in listOf(null, "", " ", "https://example.test/cover", "opaque")) assertFalse(isLocalMediaUri(value))
    }

    @Test
    fun `legacy order migration uses snapshot time and preserves deletion timestamps`() {
        val songs = listOf(SyncSong(id = 1, addedAt = 0, legacyAddedAt = 7), SyncSong(id = 2, addedAt = 0))
        val playlist = SyncPlaylist(id = 7, songs = songs, modifiedAt = 0, songOrderVersion = 0)
        val migrated = playlist.normalizedForDisplayOrder()
        assertEquals(listOf(2L, 1L), migrated.songs.map { it.id })
        assertEquals(listOf(1L, 1L), migrated.songs.map { it.addedAt })
        assertEquals(listOf(0L, 7L), migrated.songs.map { it.legacyAddedAt })
        assertTrue(playlist.copy(songs = emptyList()).normalizedForDisplayOrder().songs.isEmpty())
        val anchored = playlist.copy(modifiedAt = 100, songs = songs.map { it.copy(addedAt = 50) }).normalizedForDisplayOrder()
        assertEquals(listOf(100L, 99L), anchored.songs.map { it.addedAt })
    }
}
