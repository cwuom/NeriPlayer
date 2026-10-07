package moe.ouom.neriplayer.core.player.persistence.stats

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackStatsJournalOwnershipTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `opening a journal creates its missing directory and an empty cursor`() {
        val directory = File(temporary.root, "stats/journal")

        FilePlaybackStatsPendingStore(directory).use { store -> assertNull(store.first()) }

        assertTrue(directory.isDirectory)
        assertTrue(File(directory, "cursor.json").isFile)
    }

    @Test
    fun `a regular file in place of the journal directory is rejected`() {
        val occupied = temporary.newFile("journal")

        FilePlaybackStatsPendingStore(occupied).use { store ->
            val failure = assertThrows(IOException::class.java) { store.first() }
            assertEquals("Playback journal directory unavailable", failure.message)
        }
        assertTrue(occupied.isFile)
    }

    @Test
    fun `opening a journal removes only well formed unpublished frames`() {
        val directory = temporary.newFolder("journal")
        val unpublished = frame(directory, UUID.randomUUID().toString()).apply { writeText("partial") }
        val uppercase = frame(directory, UUID.randomUUID().toString().uppercase()).apply { writeText("partial") }
        val malformed = frame(directory, "not-a-uuid").apply { writeText("partial") }
        val nestedDirectory = frame(directory, UUID.randomUUID().toString()).apply { mkdirs() }

        FilePlaybackStatsPendingStore(directory).use { store -> assertNull(store.first()) }

        assertFalse(unpublished.exists())
        assertTrue(uppercase.isFile)
        assertTrue(malformed.isFile)
        assertTrue(nestedDirectory.isDirectory)
    }

    private fun frame(directory: File, id: String) = File(directory, ".npst-v1-frame-$id.tmp")
}
