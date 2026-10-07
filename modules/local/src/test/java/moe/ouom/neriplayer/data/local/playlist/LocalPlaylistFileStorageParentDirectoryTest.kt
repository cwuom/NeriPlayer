package moe.ouom.neriplayer.data.local.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalPlaylistFileStorageParentDirectoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `first commit creates the missing storage directory and seeds the backup`() {
        val file = File(temporaryFolder.root, "nested/playlists/local_playlists.json")
        val storage = LocalPlaylistFileStorage(file, fallbackParent = temporaryFolder.root)

        storage.commit("[]", rotateBackup = true, replaceBackupWithCommittedPrimary = false)

        assertEquals("[]", storage.readPrimary())
        assertEquals("[]", storage.readBackup())
    }

    @Test
    fun `commits fail when the storage directory cannot be created`() {
        val blocker = temporaryFolder.newFile("blocker")
        val parent = File(blocker, "playlists")
        val storage = LocalPlaylistFileStorage(File(parent, "local_playlists.json"), temporaryFolder.root)

        val error = assertThrows(IOException::class.java) {
            storage.commit("[]", rotateBackup = true, replaceBackupWithCommittedPrimary = false)
        }

        assertEquals("Failed to create playlist storage directory: ${parent.absolutePath}", error.message)
        assertNull(storage.readPrimary())
    }

    @Test
    fun `pending sync mutations fail when the storage parent is a file`() {
        val parentFile = temporaryFolder.newFile("playlists")
        val storage = LocalPlaylistFileStorage(File(parentFile, "local_playlists.json"), temporaryFolder.root)

        val error = assertThrows(IOException::class.java) { storage.writePendingSyncMutation("{}") }

        assertEquals("Playlist storage parent is not a directory: ${parentFile.absolutePath}", error.message)
        assertNull(storage.readPendingSyncMutation())
    }
}
