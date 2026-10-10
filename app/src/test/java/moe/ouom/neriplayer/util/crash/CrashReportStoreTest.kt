package moe.ouom.neriplayer.util.crash

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin
import moe.ouom.neriplayer.util.crash.CrashReportStore.PendingCrashReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CrashReportStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pending crash marker exposes the origin and log preview`() {
        val baseDir = temporaryFolder.newFolder("external")
        val context = context(baseDir)
        val log = File(crashDirectory(baseDir), "crash_1.log").apply { writeText("boom") }

        CrashReportStore.markPendingCrash(context, log, CrashOrigin.Native)

        assertEquals("native\ncrash_1.log", File(crashDirectory(baseDir), FLAG_FILE).readText())
        assertTrue(CrashReportStore.hasPendingCrashReport(context))
        assertEquals(
            PendingCrashReport(
                origin = CrashOrigin.Native,
                file = log,
                previewContent = "boom",
                previewTruncated = false
            ),
            CrashReportStore.readPendingCrashReport(context)
        )
    }

    @Test
    fun `large crash logs are previewed up to 64 KiB`() {
        val baseDir = temporaryFolder.newFolder("external")
        val context = context(baseDir)
        val log = File(crashDirectory(baseDir), "anr_1.log").apply { writeText("x".repeat(70_000)) }

        CrashReportStore.markPendingCrash(context, log, CrashOrigin.Anr)
        val report = CrashReportStore.readPendingCrashReport(context)!!

        assertEquals(CrashOrigin.Anr, report.origin)
        assertEquals(64 * 1024, report.previewContent.length)
        assertTrue(report.previewTruncated)
    }

    @Test
    fun `clearing the pending crash removes only the marker`() {
        val baseDir = temporaryFolder.newFolder("external")
        val context = context(baseDir)
        val log = File(crashDirectory(baseDir), "crash_2.log").apply { writeText("trace") }
        CrashReportStore.markPendingCrash(context, log, CrashOrigin.Jvm)

        CrashReportStore.clearPendingCrashReport(context)
        CrashReportStore.clearPendingCrashReport(context)

        assertFalse(File(crashDirectory(baseDir), FLAG_FILE).exists())
        assertTrue(log.exists())
        assertFalse(CrashReportStore.hasPendingCrashReport(context))
        assertNull(CrashReportStore.readPendingCrashReport(context))
    }

    @Test
    fun `full crash report is read only from existing files`() {
        val directory = temporaryFolder.newFolder("reports")
        val report = File(directory, "crash_3.log").apply { writeText("stack trace") }

        assertEquals("stack trace", CrashReportStore.readFullCrashReport(report))
        assertNull(CrashReportStore.readFullCrashReport(File(directory, "missing.log")))
        assertNull(CrashReportStore.readFullCrashReport(directory))
    }

    @Test
    fun `crash markers are skipped when the crash directory cannot be created`() {
        val blockedBase = temporaryFolder.newFile("blocked")
        val context = context(blockedBase)

        CrashReportStore.markPendingCrash(context, File(blockedBase, "crash.log"), CrashOrigin.Jvm)
        CrashReportStore.clearPendingCrashReport(context)

        assertFalse(CrashReportStore.hasPendingCrashReport(context))
        assertNull(CrashReportStore.readPendingCrashReport(context))
        assertTrue(blockedBase.isFile)
    }

    private fun context(externalFilesDir: File): Context {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(externalFilesDir)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }

    private fun crashDirectory(baseDir: File): File = File(baseDir, "crashes").apply { mkdirs() }

    private companion object {
        const val FLAG_FILE = "pending_startup_crash.flag"
    }
}
