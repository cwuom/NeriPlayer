package moe.ouom.neriplayer.core.crash

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExceptionHandlerCrashDirectoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `crash directory prefers external app storage`() {
        val external = temporaryFolder.newFolder("external")
        val internal = temporaryFolder.newFolder("internal")

        val crashDirectory = ExceptionHandler.resolveCrashDirectory(context(external, internal))

        assertEquals(File(external, "crashes"), crashDirectory)
        assertTrue(crashDirectory!!.isDirectory)
        assertFalse(File(internal, "crashes").exists())
    }

    @Test
    fun `crash directory falls back to internal files`() {
        val internal = temporaryFolder.newFolder("internal")
        File(internal, "crashes").mkdirs()

        assertEquals(
            File(internal, "crashes"),
            ExceptionHandler.resolveCrashDirectory(context(externalFilesDir = null, filesDir = internal))
        )
    }

    @Test
    fun `crash directory is unavailable when it cannot be created`() {
        val blocked = temporaryFolder.newFile("blocked")

        assertNull(ExceptionHandler.resolveCrashDirectory(context(blocked, blocked)))
    }

    @Test
    fun `clearing crash logs deletes logs dumps and the pending marker`() {
        val external = temporaryFolder.newFolder("external")
        val crashDirectory = File(external, "crashes").apply { mkdirs() }
        File(crashDirectory, "crash_1.log").writeText("trace")
        File(crashDirectory, "native").mkdirs()
        File(crashDirectory, "native/tombstone.txt").writeText("dump")
        File(crashDirectory, "pending_startup_crash.flag").writeText("jvm\ncrash_1.log")

        assertTrue(ExceptionHandler.clearCrashLogs(context(external, filesDir = null)))
        assertEquals(emptyList<String>(), crashDirectory.list()!!.toList())
    }

    @Test
    fun `clearing crash logs fails without a crash directory`() {
        val blocked = temporaryFolder.newFile("blocked")

        assertFalse(ExceptionHandler.clearCrashLogs(context(blocked, blocked)))
    }

    private fun context(externalFilesDir: File?, filesDir: File?): Context {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(externalFilesDir)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }
}
