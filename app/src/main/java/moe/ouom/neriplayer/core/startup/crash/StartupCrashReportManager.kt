package moe.ouom.neriplayer.core.startup.crash

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.util.crash.CrashReportStore
import java.io.File

internal class StartupCrashReportManager(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun readPendingReport(): CrashReportStore.PendingCrashReport? {
        return withContext(Dispatchers.IO) {
            CrashReportStore.readPendingCrashReport(appContext)
        }
    }

    suspend fun readFullReport(reportFile: File): String? {
        return withContext(Dispatchers.IO) {
            CrashReportStore.readFullCrashReport(reportFile)
        }
    }

    suspend fun exportReport(reportFile: File, destination: Uri) {
        withContext(Dispatchers.IO) {
            CrashReportStore.exportCrashReport(
                context = appContext,
                reportFile = reportFile,
                destination = destination
            )
        }
    }

    fun clearPendingReport() {
        CrashReportStore.clearPendingCrashReport(appContext)
    }
}
