package moe.ouom.neriplayer.util

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CrashLogFiles {

    private val timestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    fun createCrashLogFile(
        context: Context,
        prefix: String,
        nowMillis: Long = System.currentTimeMillis(),
        pid: Int = Process.myPid()
    ): File {
        val crashDir = ExceptionHandler.resolveCrashDirectory(context)
            ?: error("Crash directory is not available")
        return File(crashDir, buildCrashLogFileName(prefix, nowMillis, pid))
    }

    fun buildCrashLogFileName(
        prefix: String,
        nowMillis: Long,
        pid: Int
    ): String {
        val safePrefix = prefix.trim().ifEmpty { "crash" }
        val timestamp = timestampFormatter.get().format(Date(nowMillis))
        return "${safePrefix}_${timestamp}_p${pid}.txt"
    }
}
