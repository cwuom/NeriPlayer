package moe.ouom.neriplayer.util

import android.content.Context
import java.io.File

internal object CrashLogFiles {
    fun createCrashLogFile(
        context: Context,
        prefix: String,
        nowMillis: Long = System.currentTimeMillis(),
        pid: Int = android.os.Process.myPid()
    ): File {
        return moe.ouom.neriplayer.util.crash.CrashLogFiles.createCrashLogFile(
            context = context,
            prefix = prefix,
            nowMillis = nowMillis,
            pid = pid
        )
    }

    fun buildCrashLogFileName(
        prefix: String,
        nowMillis: Long,
        pid: Int
    ): String {
        return moe.ouom.neriplayer.util.crash.CrashLogFiles.buildCrashLogFileName(
            prefix = prefix,
            nowMillis = nowMillis,
            pid = pid
        )
    }
}
