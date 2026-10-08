package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaylistFileStorageCommitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rotation keeps the previous primary until the committed primary replaces the backup`() {
        val storage = storage()

        storage.commit("first", rotateBackup = true, replaceBackupWithCommittedPrimary = false)
        storage.commit("second", rotateBackup = true, replaceBackupWithCommittedPrimary = false)
        assertEquals("second", storage.readPrimary())
        assertEquals("first", storage.readBackup())

        storage.commit("third", rotateBackup = false, replaceBackupWithCommittedPrimary = false)
        assertEquals("third", storage.readPrimary())
        assertEquals("first", storage.readBackup())

        storage.commit("fourth", rotateBackup = false, replaceBackupWithCommittedPrimary = true)
        assertEquals("fourth", storage.readPrimary())
        assertEquals("fourth", storage.readBackup())
    }

    @Test
    fun `a backup that cannot be replaced aborts the commit without leaving temp files`() {
        val backupDirectory = File(temporaryFolder.root, "local_playlists.json.bak").apply { mkdirs() }
        File(backupDirectory, "occupied").writeText("x")
        val storage = storage()

        assertThrows(IOException::class.java) {
            storage.commit("[]", rotateBackup = false, replaceBackupWithCommittedPrimary = true)
        }

        assertNull(storage.readPrimary())
        assertTrue(backupDirectory.isDirectory)
        assertEquals(emptyList<String>(), tempFileNames())
    }

    @Test
    fun `an unreadable primary aborts rotation without touching the backup`() {
        val primaryDirectory = File(temporaryFolder.root, "local_playlists.json").apply { mkdirs() }
        File(primaryDirectory, "occupied").writeText("x")
        val storage = LocalPlaylistFileStorage(primaryDirectory, temporaryFolder.root)

        assertThrows(IOException::class.java) {
            storage.commit("[]", rotateBackup = true, replaceBackupWithCommittedPrimary = false)
        }

        assertTrue(primaryDirectory.isDirectory)
        assertNull(storage.readBackup())
        assertEquals(emptyList<String>(), tempFileNames())
    }

    private fun storage() = LocalPlaylistFileStorage(
        File(temporaryFolder.root, "local_playlists.json"),
        fallbackParent = temporaryFolder.root
    )

    private fun tempFileNames(): List<String> =
        temporaryFolder.root.list().orEmpty().filter { it.endsWith(".tmp") }
}
