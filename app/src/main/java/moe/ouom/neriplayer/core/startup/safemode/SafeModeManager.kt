package moe.ouom.neriplayer.core.startup.safemode

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.util.crash.CrashReportStore
import java.io.File

internal object SafeModeManager {

    fun shouldEnterSafeMode(context: Context): Boolean {
        return SafeModeCrashReports(context).hasPendingReport()
    }

    suspend fun readPendingCrashReport(context: Context): CrashReportStore.PendingCrashReport? {
        return SafeModeCrashReports(context).readPendingReport()
    }

    suspend fun readFullCrashReport(reportFile: File): String? {
        return SafeModeCrashReports.readFullReport(reportFile)
    }

    suspend fun exportCrashReport(
        context: Context,
        reportFile: File,
        destination: Uri
    ) {
        SafeModeCrashReports(context).exportReport(
            reportFile = reportFile,
            destination = destination
        )
    }

    suspend fun exportConfigBackup(context: Context, destination: Uri): Result<String> {
        return SafeModeDataExports(context).exportConfigBackup(destination)
    }

    suspend fun exportPlaylistBackup(context: Context, destination: Uri): Result<String> {
        return SafeModeDataExports(context).exportPlaylistBackup(destination)
    }

    fun generateConfigBackupFileName(context: Context): String {
        return SafeModeDataExports(context).generateConfigBackupFileName()
    }

    fun generatePlaylistBackupFileName(context: Context): String {
        return SafeModeDataExports(context).generatePlaylistBackupFileName()
    }

    suspend fun clearAllCookiesAndLoginOptions(context: Context) {
        SafeModeResetActions(context).clearAllCookiesAndLoginOptions()
    }

    suspend fun resetAppSettings(context: Context) {
        SafeModeResetActions(context).resetAppSettings()
    }

    fun restoreNormalStartup(context: Context) {
        SafeModeCrashReports(context).clearPendingReport()
    }
}
