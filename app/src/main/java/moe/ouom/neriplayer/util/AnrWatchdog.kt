package moe.ouom.neriplayer.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

internal object AnrWatchdog {
    private const val TAG = "AnrWatchdog"
    private const val STATE_PREFS = "anr_report_state"
    private const val LAST_CAPTURED_EXIT_KEY = "last_captured_exit"
    private const val TEST_ANR_BLOCK_MS = 16_000L
    private const val MAX_EXIT_TRACE_BYTES = 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())

    fun capturePreviousAnrIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        if (CrashReportStore.hasPendingCrashReport(context.applicationContext)) {
            return
        }

        runCatching {
            capturePreviousAnrOnApi30(context.applicationContext)
        }.onFailure { error ->
            NPLogger.e(TAG, "Failed to inspect historical ANR exit info", error)
        }
    }

    fun triggerTestAnr(context: Context) {
        if (!BuildConfig.DEBUG) {
            ExceptionHandler.handleException(
                source = TAG,
                throwable = IllegalStateException(context.getString(R.string.debug_anr_test_unavailable))
            )
            return
        }

        // 只制造阻塞，不主动写安全模式标记，避免测试入口绕过系统 ANR 判定
        mainHandler.post {
            Thread.sleep(TEST_ANR_BLOCK_MS)
        }
    }

    @SuppressLint("NewApi")
    private fun capturePreviousAnrOnApi30(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exitInfo = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR }
            ?: return

        val captureKey = exitInfo.captureKey()
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_CAPTURED_EXIT_KEY, null) == captureKey) {
            return
        }

        val logFile = writeHistoricalExitReport(context, exitInfo)
        CrashReportStore.markPendingCrash(
            context = context,
            logFile = logFile,
            origin = CrashReportStore.CrashOrigin.Anr
        )
        prefs.edit().putString(LAST_CAPTURED_EXIT_KEY, captureKey).apply()
    }

    @SuppressLint("NewApi")
    private fun writeHistoricalExitReport(
        context: Context,
        exitInfo: ApplicationExitInfo
    ): File {
        val logFile = CrashLogFiles.createCrashLogFile(context, prefix = "system_anr")
        FileOutputStream(logFile, false).use { output ->
            val header = buildString {
                appendLine("=== ANR Report ===")
                appendCommonHeader(
                    source = "Android ApplicationExitInfo",
                    type = "Historical Application Not Responding",
                    context = context
                )
                appendLine("Exit Timestamp: ${formatTimestamp(exitInfo.timestamp)}")
                appendLine("Exit Process: ${exitInfo.processName}")
                appendLine("Exit PID: ${exitInfo.pid}")
                appendLine("Exit Reason: ${exitInfo.reason}")
                appendLine("Exit Status: ${exitInfo.status}")
                appendLine("Exit Importance: ${exitInfo.importance}")
                appendLine("Exit PSS: ${exitInfo.pss} KB")
                appendLine("Exit RSS: ${exitInfo.rss} KB")
                appendLine("Exit Description: ${exitInfo.description.orEmpty()}")
                appendLine()
                appendLine("=== System ANR Trace ===")
            }
            output.write(header.toByteArray(Charsets.UTF_8))

            val traceCopied = exitInfo.traceInputStream?.use { traceInput ->
                copyLimited(traceInput, output, MAX_EXIT_TRACE_BYTES)
            }
            if (traceCopied == null) {
                output.write("No trace stream is available\n".toByteArray(Charsets.UTF_8))
            } else if (traceCopied) {
                output.write("\n\n[Trace truncated at ${MAX_EXIT_TRACE_BYTES} bytes]\n".toByteArray(Charsets.UTF_8))
            }
            output.fd.sync()
        }
        return logFile
    }

    private fun StringBuilder.appendCommonHeader(
        source: String,
        type: String,
        context: Context
    ) {
        appendLine("Time: ${formatTimestamp(System.currentTimeMillis())}")
        appendLine("Source: $source")
        appendLine("Type: $type")
        appendLine("Process: ${context.applicationInfo?.processName ?: "unknown"}")
        appendLine("Package: ${context.packageName}")
        appendLine("PID: ${Process.myPid()}")
        appendLine("Android Version: ${Build.VERSION.RELEASE}")
        appendLine("SDK Level: ${Build.VERSION.SDK_INT}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Device Code: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("App Version: ${BuildConfig.VERSION_NAME}")
        appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Build UUID: ${BuildConfig.BUILD_UUID}")
    }

    private fun copyLimited(
        input: InputStream,
        output: FileOutputStream,
        byteLimit: Int
    ): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = byteLimit
        while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size, remaining))
            if (read == -1) {
                return false
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        return input.read() != -1
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMs))
    }

    @SuppressLint("NewApi")
    private fun ApplicationExitInfo.captureKey(): String {
        return "$timestamp:$pid:$reason:$status"
    }
}
