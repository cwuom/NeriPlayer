package moe.ouom.neriplayer.common.logging

import android.content.Context
import android.util.Log
import java.io.File
import moe.ouom.neriplayer.common.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`

class NPLoggerBuildLevelTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun restoreLogger() {
        NPLogger.init(context(temporary.root), defaultTag = BuildConfig.TAG, enableFileLogging = false)
    }

    @Test fun `release builds keep debug and verbose messages out of logcat`() {
        for (fileLogging in listOf(false, true)) {
            NPLogger.init(context(temporary.newFolder()), defaultTag = "Neri", enableFileLogging = false)
            NPLogger.setFileLoggingEnabled(context(temporary.newFolder()), fileLogging)
            mockStatic(Log::class.java).use { log ->
                NPLogger.log(Log.DEBUG, "Net", "secret", null, debugBuild = false)
                NPLogger.log(Log.VERBOSE, "Net", "secret", null, debugBuild = false)
                NPLogger.log(Log.INFO, "Net", "visible", null, debugBuild = false)

                log.verify({ Log.d(anyString(), anyString(), any()) }, never())
                log.verify({ Log.v(anyString(), anyString(), any()) }, never())
                log.verify { Log.i("Neri: Net", "visible", null) }
            }
        }
    }

    @Test fun `debug builds send every level to logcat with or without file logging`() {
        for (fileLogging in listOf(false, true)) {
            NPLogger.init(context(temporary.newFolder()), defaultTag = "Neri", enableFileLogging = false)
            NPLogger.setFileLoggingEnabled(context(temporary.newFolder()), fileLogging)
            mockStatic(Log::class.java).use { log ->
                NPLogger.log(Log.VERBOSE, "Neri", "trace", null, debugBuild = true)
                NPLogger.log(Log.WARN, null, "careful", null, debugBuild = true)

                log.verify { Log.v("Neri", "trace", null) }
                log.verify { Log.w("Neri", "careful", null) }
            }
        }
    }

    @Test fun `file entries carry level letter tag message and stack trace`() {
        val failure = IllegalStateException("boom")
        val file = temporary.newFile("log.txt")
        val levels = mapOf(Log.DEBUG to "D", Log.INFO to "I", Log.WARN to "W", Log.ERROR to "E", Log.VERBOSE to "U")

        mockStatic(Log::class.java).use { log ->
            log.`when`<String> { Log.getStackTraceString(failure) }.thenReturn("trace-lines")
            for ((level, letter) in levels) {
                val line = NPLogger.formatFileLogEntry(NPLogger.LogFileEntry(file, 0L, level, "Neri: Net", "hello", null))
                assertTrue(line, line.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} $letter/Neri: Net: hello\n""")))
            }
            val withFailure = NPLogger.formatFileLogEntry(NPLogger.LogFileEntry(file, 0L, Log.ERROR, "Neri", "failed", failure))
            assertTrue(withFailure, withFailure.endsWith(" E/Neri: failed\ntrace-lines\n"))
        }
    }

    @Test fun `clearing logs while file logging is active removes older log files`() {
        val privateFiles = temporary.newFolder("private")
        val context = context(privateFiles)
        NPLogger.init(context, enableFileLogging = false)
        NPLogger.setFileLoggingEnabled(context, true)
        val logs = File(privateFiles, "logs")
        val old = File(logs, "log_old.txt").apply { writeText("old entries") }

        assertTrue(NPLogger.clearLogFiles(context))

        assertFalse(old.exists())
        assertTrue(logs.isDirectory)
        assertEquals(logs, NPLogger.getLogDirectory(context))
    }

    private fun context(privateFiles: File): Context = mock(Context::class.java).also {
        `when`(it.getExternalFilesDir(null)).thenReturn(null)
        `when`(it.filesDir).thenReturn(privateFiles)
    }
}
