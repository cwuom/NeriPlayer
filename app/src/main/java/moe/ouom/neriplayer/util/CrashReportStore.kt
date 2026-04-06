package moe.ouom.neriplayer.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

internal object CrashReportStore {

    private const val PENDING_STARTUP_CRASH_FLAG = "pending_startup_crash.flag"
    private const val PREVIEW_MAX_BYTES = 64 * 1024

    enum class CrashOrigin {
        Jvm,
        Native,
        Unknown
    }

    data class PendingCrashReport(
        val origin: CrashOrigin,
        val file: File,
        val previewContent: String,
        val previewTruncated: Boolean
    )

    fun markPendingCrash(context: Context, logFile: File, origin: CrashOrigin) {
        runCatching {
            val crashDir = ExceptionHandler.resolveCrashDirectory(context) ?: return
            val flagFile = File(crashDir, PENDING_STARTUP_CRASH_FLAG)
            FileOutputStream(flagFile, false).use { outputStream ->
                outputStream.write("${origin.name.lowercase()}\n${logFile.name}".toByteArray(Charsets.UTF_8))
                outputStream.fd.sync()
            }
        }.onFailure { error ->
            NPLogger.e("CrashReportStore", "Failed to mark pending crash report", error)
        }
    }

    fun readPendingCrashReport(context: Context): PendingCrashReport? {
        val crashDir = ExceptionHandler.resolveCrashDirectory(context) ?: return null
        val flagFile = File(crashDir, PENDING_STARTUP_CRASH_FLAG)
        if (!flagFile.exists() || !flagFile.isFile()) {
            return null
        }

        return runCatching {
            val lines = flagFile.readLines()
            if (lines.size < 2) {
                clearPendingCrashReport(context)
                return null
            }

            val origin = when (lines.firstOrNull()?.trim()?.lowercase()) {
                "jvm" -> CrashOrigin.Jvm
                "native" -> CrashOrigin.Native
                else -> CrashOrigin.Unknown
            }
            val logFileName = lines.getOrNull(1)?.trim().orEmpty()
            if (logFileName.isEmpty()) {
                clearPendingCrashReport(context)
                return null
            }

            val logFile = File(crashDir, logFileName)
            if (!logFile.exists() || !logFile.isFile()) {
                clearPendingCrashReport(context)
                return null
            }

            val fileBytes = logFile.readBytes()
            val previewBytes = if (fileBytes.size > PREVIEW_MAX_BYTES) {
                fileBytes.copyOf(PREVIEW_MAX_BYTES)
            } else {
                fileBytes
            }

            PendingCrashReport(
                origin = origin,
                file = logFile,
                previewContent = previewBytes.toString(Charsets.UTF_8),
                previewTruncated = fileBytes.size > PREVIEW_MAX_BYTES
            )
        }.onFailure { error ->
            NPLogger.e("CrashReportStore", "Failed to read pending crash report", error)
        }.getOrNull()
    }

    fun readFullCrashReport(reportFile: File): String? {
        return runCatching {
            if (!reportFile.exists() || !reportFile.isFile()) {
                return null
            }
            reportFile.readText(Charsets.UTF_8)
        }.onFailure { error ->
            NPLogger.e("CrashReportStore", "Failed to read full crash report", error)
        }.getOrNull()
    }

    fun exportCrashReport(context: Context, reportFile: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { outputStream ->
            reportFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("Cannot open SAF output stream")
    }

    fun clearPendingCrashReport(context: Context) {
        runCatching {
            val crashDir = ExceptionHandler.resolveCrashDirectory(context) ?: return
            val flagFile = File(crashDir, PENDING_STARTUP_CRASH_FLAG)
            if (flagFile.exists()) {
                flagFile.delete()
            }
        }.onFailure { error ->
            NPLogger.e("CrashReportStore", "Failed to clear pending crash report", error)
        }
    }
}
