package moe.ouom.neriplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

internal object SafeModeManager {
    fun shouldEnterSafeMode(context: Context): Boolean {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.shouldEnterSafeMode(context)
    }

    suspend fun readPendingCrashReport(context: Context): CrashReportStore.PendingCrashReport? {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
            .readPendingCrashReport(context)
            ?.toRootReport()
    }

    suspend fun readFullCrashReport(reportFile: File): String? {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.readFullCrashReport(reportFile)
    }

    suspend fun exportCrashReport(
        context: Context,
        reportFile: File,
        destination: Uri
    ) {
        moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.exportCrashReport(
            context = context,
            reportFile = reportFile,
            destination = destination
        )
    }

    suspend fun exportConfigBackup(context: Context, destination: Uri): Result<String> {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
            .exportConfigBackup(context, destination)
    }

    suspend fun exportPlaylistBackup(context: Context, destination: Uri): Result<String> {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
            .exportPlaylistBackup(context, destination)
    }

    fun generateConfigBackupFileName(context: Context): String {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
            .generateConfigBackupFileName(context)
    }

    fun generatePlaylistBackupFileName(context: Context): String {
        return moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
            .generatePlaylistBackupFileName(context)
    }

    suspend fun clearAllCookiesAndLoginOptions(context: Context) {
        moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.clearAllCookiesAndLoginOptions(context)
    }

    suspend fun resetAppSettings(context: Context) {
        moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.resetAppSettings(context)
    }

    fun restoreNormalStartup(context: Context) {
        moe.ouom.neriplayer.core.startup.safemode.SafeModeManager.restoreNormalStartup(context)
    }

    private fun moe.ouom.neriplayer.util.crash.CrashReportStore.PendingCrashReport.toRootReport(): CrashReportStore.PendingCrashReport {
        return CrashReportStore.PendingCrashReport(
            origin = origin.toRootOrigin(),
            file = file,
            previewContent = previewContent,
            previewTruncated = previewTruncated
        )
    }

    private fun moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.toRootOrigin(): CrashReportStore.CrashOrigin {
        return when (this) {
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Jvm -> CrashReportStore.CrashOrigin.Jvm
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Native -> CrashReportStore.CrashOrigin.Native
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Anr -> CrashReportStore.CrashOrigin.Anr
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Unknown -> CrashReportStore.CrashOrigin.Unknown
        }
    }
}
