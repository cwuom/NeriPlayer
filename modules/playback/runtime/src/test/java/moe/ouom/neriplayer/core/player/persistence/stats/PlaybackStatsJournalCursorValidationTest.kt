package moe.ouom.neriplayer.core.player.persistence.stats

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackStatsJournalCursorValidationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `frames without a cursor are not trusted`() {
        val directory = temporary.newFolder("journal")
        File(directory, "0.delta").writeText("frame")

        assertEquals("Playback journal frames have no trusted cursor", firstFailure(directory))
    }

    @Test
    fun `oversized cursor is rejected before parsing`() {
        val directory = openedJournal()
        cursorFile(directory).writeText(" ".repeat(4097))

        assertEquals("Playback journal cursor exceeds its budget", firstFailure(directory))
    }

    @Test
    fun `unsupported cursor version is rejected`() {
        val directory = openedJournal()
        rewriteCursor(directory) { addProperty("version", "2") }

        assertEquals("Unsupported playback journal cursor version", firstFailure(directory))
    }

    @Test
    fun `cursor positions must stay ordered`() {
        val orders = listOf(
            Triple(0L, 0L, -1L),
            Triple(0L, 1L, 1L),
            Triple(2L, 1L, 0L)
        )
        for ((head, tail, cleaned) in orders) {
            val directory = openedJournal()
            rewriteCursor(directory) {
                addProperty("head", head)
                addProperty("tail", tail)
                addProperty("cleaned", cleaned)
            }

            assertEquals("Invalid playback journal cursor order", firstFailure(directory))
        }
    }

    @Test
    fun `non textual cursor fields are reported as a malformed cursor`() {
        val directory = openedJournal()
        rewriteCursor(directory) { add("head", JsonObject()) }

        assertEquals("Malformed playback journal cursor", firstFailure(directory))
    }

    @Test
    fun `trusted cursor reopens an empty journal`() {
        val directory = openedJournal()

        FilePlaybackStatsPendingStore(directory).use { store -> assertNull(store.first()) }
    }

    private fun openedJournal(): File {
        val directory = temporary.newFolder()
        FilePlaybackStatsPendingStore(directory).use { store -> assertNull(store.first()) }
        return directory
    }

    private fun cursorFile(directory: File) = File(directory, "cursor.json")

    private fun rewriteCursor(directory: File, edit: JsonObject.() -> Unit) {
        val file = cursorFile(directory)
        val value = JsonParser.parseString(file.readText()).asJsonObject.apply(edit)
        file.writeText(value.toString())
    }

    private fun firstFailure(directory: File): String? =
        FilePlaybackStatsPendingStore(directory).use { store ->
            assertThrows(IOException::class.java) { store.first() }.message
        }
}
