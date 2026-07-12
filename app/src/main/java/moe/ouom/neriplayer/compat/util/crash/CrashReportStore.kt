package moe.ouom.neriplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

internal object CrashReportStore {
    enum class CrashOrigin {
        Jvm,
        Native,
        Anr,
        Unknown
    }

    data class PendingCrashReport(
        val origin: CrashOrigin,
        val file: File,
        val previewContent: String,
        val previewTruncated: Boolean
    )

    fun markPendingCrash(context: Context, logFile: File, origin: CrashOrigin) {
        moe.ouom.neriplayer.util.crash.CrashReportStore.markPendingCrash(
            context = context,
            logFile = logFile,
            origin = origin.toCrashOrigin()
        )
    }

    fun readPendingCrashReport(context: Context): PendingCrashReport? {
        return moe.ouom.neriplayer.util.crash.CrashReportStore
            .readPendingCrashReport(context)
            ?.toRootReport()
    }

    fun hasPendingCrashReport(context: Context): Boolean {
        return moe.ouom.neriplayer.util.crash.CrashReportStore.hasPendingCrashReport(context)
    }

    fun readFullCrashReport(reportFile: File): String? {
        return moe.ouom.neriplayer.util.crash.CrashReportStore.readFullCrashReport(reportFile)
    }

    fun exportCrashReport(context: Context, reportFile: File, destination: Uri) {
        moe.ouom.neriplayer.util.crash.CrashReportStore.exportCrashReport(
            context = context,
            reportFile = reportFile,
            destination = destination
        )
    }

    fun clearPendingCrashReport(context: Context) {
        moe.ouom.neriplayer.util.crash.CrashReportStore.clearPendingCrashReport(context)
    }

    private fun CrashOrigin.toCrashOrigin(): moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin {
        return when (this) {
            CrashOrigin.Jvm -> moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Jvm
            CrashOrigin.Native -> moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Native
            CrashOrigin.Anr -> moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Anr
            CrashOrigin.Unknown -> moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Unknown
        }
    }

    private fun moe.ouom.neriplayer.util.crash.CrashReportStore.PendingCrashReport.toRootReport(): PendingCrashReport {
        return PendingCrashReport(
            origin = origin.toRootOrigin(),
            file = file,
            previewContent = previewContent,
            previewTruncated = previewTruncated
        )
    }

    private fun moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.toRootOrigin(): CrashOrigin {
        return when (this) {
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Jvm -> CrashOrigin.Jvm
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Native -> CrashOrigin.Native
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Anr -> CrashOrigin.Anr
            moe.ouom.neriplayer.util.crash.CrashReportStore.CrashOrigin.Unknown -> CrashOrigin.Unknown
        }
    }
}
