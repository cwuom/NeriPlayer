package moe.ouom.neriplayer.data.sync.store.state

import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDeletionFileStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `legacy tombstones migrate into durable files without truncation`() {
        val prefs = MemorySyncPreferences()
        prefs.values[KEY_PLAYLIST_SONG_DELETIONS] = """[{"playlistId":7,"songId":1,"album":"netease","deletedAt":10}]"""
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertEquals(1L, store.getPlaylistSongDeletions().single().songId)
        store.addPlaylistSongDeletions((2L..5_001L).map(::deletion))
        val reopened = SecureTokenStorage(prefs.preferences, directory)
        assertEquals(5_001, reopened.getPlaylistSongDeletions().size)
        assertEquals(1L, reopened.getSyncMutationVersion())
        assertTrue((prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String).startsWith("@file-v1:"))
        assertTrue(directory.listFiles().orEmpty().count { it.extension == "json" } >= 2)
    }

    @Test
    fun `corruption and missing generations stop synchronization`() {
        for (remove in listOf(false, true)) {
            val prefs = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val store = SecureTokenStorage(prefs.preferences, directory)
            store.addPlaylistSongDeletions(listOf(deletion(1)))
            val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
            val file = File(directory, marker.split(':')[1])
            if (remove) assertTrue(file.delete()) else file.writeText("[]")
            assertThrows(IllegalStateException::class.java) { store.getPlaylistSongDeletions() }
        }
    }

    @Test
    fun `malformed generation markers cannot be read or replaced`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        val name = marker.split(':')[1]
        val hash = marker.split(':')[2]
        val invalidMarkers = listOf(
            "@file-v1:$name",
            "@file-v1:../$name:$hash",
            "@file-v1:invalid.json:$hash",
            "@file-v1:$name:${hash.dropLast(1)}",
            "@file-v1:$name:${"g".repeat(64)}",
            "$marker:extra"
        )

        invalidMarkers.forEach { invalid ->
            prefs.values[KEY_PLAYLIST_SONG_DELETIONS] = invalid
            assertThrows(IllegalStateException::class.java) { store.getPlaylistSongDeletions() }
            assertThrows(IllegalStateException::class.java) { store.setPlaylistSongDeletions(emptyList()) }
            assertEquals(invalid, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
            assertEquals(1L, store.getSyncMutationVersion())
            assertTrue(File(directory, name).isFile)
        }
    }

    @Test
    fun `new directory and intentional clear preserve the previous complete generation`() {
        val prefs = MemorySyncPreferences()
        val directory = File(temporary.newFolder(), "generations")
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        assertTrue(directory.isDirectory)
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String

        store.setPlaylistSongDeletions(emptyList())

        assertFalse(prefs.values.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
        assertEquals(marker, prefs.values["${KEY_PLAYLIST_SONG_DELETIONS}_previous_generation"])
        assertTrue(File(directory, marker.split(':')[1]).isFile)
        assertTrue(SecureTokenStorage(prefs.preferences, directory).getPlaylistSongDeletions().isEmpty())
        assertEquals(1L, store.getSyncMutationVersion())
    }

    @Test
    fun `directory sync failure cannot publish a renamed generation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val stable = SecureTokenStorage(prefs.preferences, directory)
        stable.addPlaylistSongDeletions(listOf(deletion(1)))
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        val failing = SecureTokenStorage(prefs.preferences, directory) {
            throw IOException("directory sync failed")
        }

        assertThrows(IllegalStateException::class.java) { failing.addPlaylistSongDeletions(listOf(deletion(2))) }

        assertEquals(marker, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
        assertEquals(listOf(1L), SecureTokenStorage(prefs.preferences, directory).getPlaylistSongDeletions().map { it.songId })
        assertEquals(1L, stable.getSyncMutationVersion())
        assertTrue(File(directory, marker.split(':')[1]).isFile)
    }

    @Test
    fun `failed marker commit preserves the previous complete generation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val previous = prefs.values[KEY_PLAYLIST_SONG_DELETIONS]
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.addPlaylistSongDeletions(listOf(deletion(2))) }
        assertEquals(previous, prefs.durableValues[KEY_PLAYLIST_SONG_DELETIONS])
        assertEquals(listOf(2L, 1L), store.getPlaylistSongDeletions().map { it.songId })
        assertEquals(2L, store.getSyncMutationVersion())
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(listOf(1L), restarted.getPlaylistSongDeletions().map { it.songId })
        assertEquals(1L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `unwritable file directory cannot advance mutation epoch`() {
        val prefs = MemorySyncPreferences()
        val path = temporary.newFile()
        val store = SecureTokenStorage(prefs.preferences, path)
        assertThrows(IllegalStateException::class.java) { store.addPlaylistSongDeletions(listOf(deletion(1))) }
        assertFalse(prefs.values.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
        assertEquals(0L, store.getSyncMutationVersion())
    }

    @Test
    fun `successful commits keep the current and previous complete generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val firstMarker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        store.addPlaylistSongDeletions(listOf(deletion(2)))
        val secondMarker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        store.addPlaylistSongDeletions(listOf(deletion(3)))
        val files = directory.listFiles().orEmpty().map(File::getName).toSet()

        assertEquals(2, files.size)
        assertFalse(firstMarker.split(':')[1] in files)
        assertTrue(secondMarker.split(':')[1] in files)
        assertEquals(listOf(3L, 2L, 1L), store.getPlaylistSongDeletions().map { it.songId })
    }

    private fun deletion(id: Long) = SyncPlaylistSongDeletion(playlistId = 7, songId = id, album = "netease", deletedAt = id)
}
