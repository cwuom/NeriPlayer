package moe.ouom.neriplayer.util

import android.content.Context
import java.io.File

object NPLogger {
    fun init(context: Context, defaultTag: String? = null, enableFileLogging: Boolean = false) {
        moe.ouom.neriplayer.core.logging.NPLogger.init(
            context = context,
            defaultTag = defaultTag,
            enableFileLogging = enableFileLogging
        )
    }

    fun setFileLoggingEnabled(context: Context, enabled: Boolean) {
        moe.ouom.neriplayer.core.logging.NPLogger.setFileLoggingEnabled(context, enabled)
    }

    fun d(tag: String, message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.d(tag, message, tr)

    fun d(message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.d(message, tr)

    fun i(tag: String, message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.i(tag, message, tr)

    fun i(message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.i(message, tr)

    fun w(tag: String, message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.w(tag, message, tr)

    fun w(message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.w(message, tr)

    fun e(tag: String, message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.e(tag, message, tr)

    fun e(message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.e(message, tr)

    fun v(tag: String, message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.v(tag, message, tr)

    fun v(message: Any?, tr: Throwable? = null) =
        moe.ouom.neriplayer.core.logging.NPLogger.v(message, tr)

    fun getLogDirectory(context: Context): File? {
        return moe.ouom.neriplayer.core.logging.NPLogger.getLogDirectory(context)
    }
}
