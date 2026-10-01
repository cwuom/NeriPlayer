package moe.ouom.neriplayer.core.logging

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NPLoggerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `clearing external logs preserves unrelated application files`() {
        val external = temporary.newFolder("external")
        val privateFiles = temporary.newFolder("private")
        val logs = File(external, "logs").apply { mkdir() }
        File(logs, "old.txt").writeText("old log")
        File(logs, "nested").apply { mkdir() }.resolve("old.txt").writeText("nested log")
        val sibling = File(external, "playlist.json").apply { writeText("keep") }
        val context = context(external, privateFiles)

        assertTrue(NPLogger.clearLogFiles(context))
        assertTrue(logs.isDirectory)
        assertTrue(logs.listFiles().orEmpty().isEmpty())
        assertTrue(sibling.readText() == "keep")
    }

    @Test
    fun `clearing logs falls back to private files when external storage is unavailable`() {
        val privateFiles = temporary.newFolder("private")
        val logs = File(privateFiles, "logs").apply { mkdir() }
        File(logs, "old.txt").writeText("old log")

        assertTrue(NPLogger.clearLogFiles(context(null, privateFiles)))
        assertTrue(logs.isDirectory)
        assertTrue(logs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `clearing an absent log directory succeeds without creating it`() {
        val privateFiles = temporary.newFolder("private")

        assertTrue(NPLogger.clearLogFiles(context(null, privateFiles)))
        assertFalse(File(privateFiles, "logs").exists())
    }

    private fun context(external: File?, privateFiles: File): Context = mock(Context::class.java).also {
        `when`(it.getExternalFilesDir(null)).thenReturn(external)
        `when`(it.filesDir).thenReturn(privateFiles)
    }
}
