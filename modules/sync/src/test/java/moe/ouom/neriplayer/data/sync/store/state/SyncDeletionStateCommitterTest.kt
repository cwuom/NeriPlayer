package moe.ouom.neriplayer.data.sync.store.state

import java.io.File
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDeletionStateCommitterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `equal nonempty tombstones confirm durability without replacing either generation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
        val valuesBefore = prefs.values.toMap()
        val filesBefore = files(directory)

        repeat(3) {
            assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))

            assertEquals(emptySet<String>(), prefs.commits.last())
            assertEquals(valuesBefore, prefs.values.toMap())
            assertEquals(valuesBefore, prefs.durableValues.toMap())
            assertFilesUnchanged(directory, filesBefore)
        }
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(listOf(recent()), restarted.getRecentPlayDeletions())
        assertEquals(listOf(song()), restarted.getPlaylistSongDeletions())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `each changed section publishes durably while retaining the equal section generation`() {
        for (changeRecent in listOf(true, false)) {
            val prefs = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val store = SecureTokenStorage(prefs.preferences, directory)
            assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
            val recentMarker = prefs.values[KEY_RECENT_PLAY_DELETIONS]
            val songMarker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS]
            val nextRecent = if (changeRecent) listOf(recent(30)) else listOf(recent())
            val nextSongs = if (changeRecent) listOf(song()) else listOf(song(30))

            assertTrue(store.setDeletionStateIfMutationVersion(0, nextRecent, nextSongs))

            val changedKey = if (changeRecent) KEY_RECENT_PLAY_DELETIONS else KEY_PLAYLIST_SONG_DELETIONS
            assertEquals(setOf(changedKey, "${changedKey}_previous_generation"), prefs.commits.last())
            if (changeRecent) {
                assertNotEquals(recentMarker, prefs.values[KEY_RECENT_PLAY_DELETIONS])
                assertEquals(songMarker, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
            } else {
                assertEquals(recentMarker, prefs.values[KEY_RECENT_PLAY_DELETIONS])
                assertNotEquals(songMarker, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
            }
            val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
            assertEquals(nextRecent, restarted.getRecentPlayDeletions())
            assertEquals(nextSongs, restarted.getPlaylistSongDeletions())
            assertEquals(0L, restarted.getSyncMutationVersion())
        }
    }

    @Test
    fun `equal parsed tombstones cannot acknowledge a damaged generation checksum`() {
        for (key in listOf(KEY_RECENT_PLAY_DELETIONS, KEY_PLAYLIST_SONG_DELETIONS)) {
            val prefs = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val store = SecureTokenStorage(prefs.preferences, directory)
            assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
            val marker = prefs.values[key] as String
            val generation = File(directory, marker.split(':')[1])
            generation.appendText(" ")
            val valuesBefore = prefs.values.toMap()
            val filesBefore = files(directory)
            val commitCount = prefs.commits.size

            assertThrows(IllegalStateException::class.java) {
                store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song()))
            }

            assertEquals(commitCount, prefs.commits.size)
            assertEquals(valuesBefore, prefs.values.toMap())
            assertEquals(valuesBefore, prefs.durableValues.toMap())
            assertFilesUnchanged(directory, filesBefore)
        }
    }

    @Test
    fun `equal tombstones still fail when the durability confirmation fails`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
        val valuesBefore = prefs.values.toMap()
        val filesBefore = files(directory)
        val commitCount = prefs.commits.size
        prefs.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song()))
        }

        assertEquals(commitCount + 1, prefs.commits.size)
        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(valuesBefore, prefs.values.toMap())
        assertEquals(valuesBefore, prefs.durableValues.toMap())
        assertFilesUnchanged(directory, filesBefore)
    }

    @Test
    fun `failed changed tombstones retry the pending generations with an empty durable commit`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
        val nextRecent = listOf(recent(30))
        val nextSongs = listOf(song(30))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            store.setDeletionStateIfMutationVersion(0, nextRecent, nextSongs)
        }
        assertEquals(nextRecent, store.getRecentPlayDeletions())
        assertEquals(nextSongs, store.getPlaylistSongDeletions())
        val restartedBeforeRetry = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(listOf(recent()), restartedBeforeRetry.getRecentPlayDeletions())
        assertEquals(listOf(song()), restartedBeforeRetry.getPlaylistSongDeletions())
        val pendingValues = prefs.values.toMap()
        val pendingFiles = files(directory)
        prefs.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.setDeletionStateIfMutationVersion(0, nextRecent, nextSongs)
        }

        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(pendingValues, prefs.values.toMap())
        assertFilesUnchanged(directory, pendingFiles)
        assertEquals(listOf(recent()), SecureTokenStorage(prefs.restart().preferences, directory).getRecentPlayDeletions())
        assertEquals(listOf(song()), SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions())

        assertTrue(store.setDeletionStateIfMutationVersion(0, nextRecent, nextSongs))

        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(pendingValues, prefs.durableValues.toMap())
        assertFilesUnchanged(directory, pendingFiles)
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(nextRecent, restarted.getRecentPlayDeletions())
        assertEquals(nextSongs, restarted.getPlaylistSongDeletions())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `failed tombstone clear retries empty memory state without dropping retained generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            store.setDeletionStateIfMutationVersion(0, emptyList(), emptyList())
        }
        assertTrue(store.getRecentPlayDeletions().isEmpty())
        assertTrue(store.getPlaylistSongDeletions().isEmpty())
        val pendingValues = prefs.values.toMap()
        val pendingFiles = files(directory)
        assertEquals(listOf(recent()), SecureTokenStorage(prefs.restart().preferences, directory).getRecentPlayDeletions())
        assertEquals(listOf(song()), SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions())

        assertTrue(store.setDeletionStateIfMutationVersion(0, emptyList(), emptyList()))

        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(pendingValues, prefs.durableValues.toMap())
        assertFilesUnchanged(directory, pendingFiles)
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertTrue(restarted.getRecentPlayDeletions().isEmpty())
        assertTrue(restarted.getPlaylistSongDeletions().isEmpty())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `stale mutation rejects both sections without writes or durability confirmation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))
        assertEquals(1L, store.markSyncMutation())
        val valuesBefore = prefs.values.toMap()
        val filesBefore = files(directory)
        val commitCount = prefs.commits.size

        assertFalse(store.setDeletionStateIfMutationVersion(0, listOf(recent(30)), listOf(song(30))))

        assertEquals(commitCount, prefs.commits.size)
        assertEquals(valuesBefore, prefs.values.toMap())
        assertEquals(valuesBefore, prefs.durableValues.toMap())
        assertFilesUnchanged(directory, filesBefore)
        assertEquals(listOf(recent()), store.getRecentPlayDeletions())
        assertEquals(listOf(song()), store.getPlaylistSongDeletions())
        assertEquals(1L, store.getSyncMutationVersion())
    }

    @Test
    fun `equal legacy inline tombstones confirm without migrating or rewriting the documents`() {
        val inline = mapOf(
            KEY_RECENT_PLAY_DELETIONS to """[{"songId":7,"album":"Netease","deletedAt":20,"deviceId":"device"}]""",
            KEY_PLAYLIST_SONG_DELETIONS to """[{"playlistId":8,"songId":7,"album":"netease","deletedAt":20,"deviceId":"device","removedMembershipTokens":[]}]"""
        )
        val prefs = MemorySyncPreferences(inline)
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)

        assertTrue(store.setDeletionStateIfMutationVersion(0, listOf(recent()), listOf(song())))

        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(inline, prefs.values.toMap())
        assertEquals(inline, prefs.durableValues.toMap())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(listOf(recent()), restarted.getRecentPlayDeletions())
        assertEquals(listOf(song()), restarted.getPlaylistSongDeletions())
    }

    private fun files(directory: File): Map<String, ByteArray> =
        directory.listFiles().orEmpty().associate { it.name to it.readBytes() }

    private fun assertFilesUnchanged(directory: File, expected: Map<String, ByteArray>) {
        assertEquals(expected.keys, directory.listFiles().orEmpty().map(File::getName).toSet())
        expected.forEach { (name, bytes) -> assertTrue(File(directory, name).readBytes().contentEquals(bytes)) }
    }

    private fun recent(time: Long = 20) =
        SyncRecentPlayDeletion(songId = 7, album = "netease", deletedAt = time, deviceId = "device")

    private fun song(time: Long = 20) = SyncPlaylistSongDeletion(
        playlistId = 8, songId = 7, album = "netease", deletedAt = time, deviceId = "device",
        removedMembershipTokens = emptyList()
    )
}
