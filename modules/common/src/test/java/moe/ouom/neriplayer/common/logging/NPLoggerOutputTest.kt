package moe.ouom.neriplayer.common.logging

import android.content.Context
import android.util.Log
import java.io.File
import moe.ouom.neriplayer.common.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class NPLoggerOutputTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @After
    fun restoreLogger() {
        NPLogger.init(context(null, temporary.root), defaultTag = BuildConfig.TAG, enableFileLogging = false)
    }

    @Test
    fun `messages are formatted by type under the application tag`() {
        NPLogger.init(context(null, temporary.newFolder("private")), defaultTag = "Neri", enableFileLogging = false)
        val failure = IllegalStateException("boom")

        mockStatic(Log::class.java).use { log ->
            NPLogger.d(tag = "Net", message = null)
            NPLogger.i("Net", "plain text")
            NPLogger.w("Net", JSONObject().put("id", 1))
            NPLogger.e("Net", JSONArray().put(1).put("two"))
            NPLogger.v("Net", listOf(1, 2))
            NPLogger.i("Neri", arrayOf("a", "b"))
            NPLogger.e(StringBuilder("built"), failure)

            log.verify { Log.d("Neri: Net", "null", null) }
            log.verify { Log.i("Neri: Net", "plain text", null) }
            log.verify { Log.w("Neri: Net", "{\"id\": 1}", null) }
            log.verify { Log.e("Neri: Net", "[\n    1,\n    \"two\"\n]", null) }
            log.verify { Log.v("Neri: Net", "[1, 2]", null) }
            log.verify { Log.i("Neri", "[a, b]", null) }
            log.verify { Log.e("Neri", "built", failure) }
        }
    }

    @Test
    fun `file logging resolves the logs directory under external or private storage`() {
        val external = temporary.newFolder("external")
        val privateFiles = temporary.newFolder("private")
        val externalContext = context(external, privateFiles)
        val privateContext = context(null, privateFiles)
        val externalLogs = File(external, "logs")

        NPLogger.init(externalContext, enableFileLogging = true)
        assertEquals(externalLogs, NPLogger.getLogDirectory(externalContext))
        externalLogs.deleteRecursively()
        assertEquals(externalLogs, NPLogger.getLogDirectory(externalContext))
        assertTrue(externalLogs.isDirectory)

        NPLogger.setFileLoggingEnabled(privateContext, false)
        assertNull(NPLogger.getLogDirectory(privateContext))
        NPLogger.setFileLoggingEnabled(privateContext, true)
        assertEquals(File(privateFiles, "logs"), NPLogger.getLogDirectory(privateContext))
        assertTrue(File(privateFiles, "logs").isDirectory)
    }

    @Test
    fun `enabling file logging twice keeps the current log file`() {
        val privateFiles = temporary.newFolder("private")
        File(privateFiles, "logs").mkdir()
        val context = context(null, privateFiles)

        NPLogger.setFileLoggingEnabled(context, false)
        NPLogger.setFileLoggingEnabled(context, true)
        NPLogger.setFileLoggingEnabled(context, true)

        verify(context, times(1)).getExternalFilesDir(null)
    }

    @Test
    fun `unusable log storage disables file logging`() {
        val blocked = context(temporary.newFile("not-a-directory"), temporary.newFolder("private"))
        val failing = mock(Context::class.java)
        `when`(failing.getExternalFilesDir(null)).thenThrow(IllegalStateException("storage unavailable"))

        NPLogger.setFileLoggingEnabled(blocked, false)
        NPLogger.setFileLoggingEnabled(blocked, true)
        assertNull(NPLogger.getLogDirectory(blocked))

        NPLogger.setFileLoggingEnabled(failing, true)
        assertNull(NPLogger.getLogDirectory(failing))
    }

    private fun context(external: File?, privateFiles: File): Context = mock(Context::class.java).also {
        `when`(it.getExternalFilesDir(null)).thenReturn(external)
        `when`(it.filesDir).thenReturn(privateFiles)
    }
}
