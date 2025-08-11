package moe.ouom.neriplayer.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by

 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.util/NPLogger
 * Created: 2025/8/11
 */

object NPLogger {

    private var appTag: String = BuildConfig.TAG
    private var isFileLoggingEnabled = false
    private var logFile: File? = null

    private val logScope = CoroutineScope(Dispatchers.IO)

    /**
     * Initializes the logger. Must be called before any use, preferably in Application.onCreate().
     *
     * @param context The application context, used for getting file paths.
     * @param defaultTag The global log TAG for the application. If null, uses BuildConfig.TAG.
     * @param enableFileLogging Whether to enable file logging.
     */
    fun init(context: Context, defaultTag: String? = null, enableFileLogging: Boolean) {
        defaultTag?.let { this.appTag = it }
        this.isFileLoggingEnabled = enableFileLogging

        if (enableFileLogging) {
            setupLogFile(context)
        }

        i("Logger has been initialized.")
    }

    private fun setupLogFile(context: Context) {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists() && !logDir.mkdirs()) {
            Log.e(appTag, "Failed to create log directory")
            isFileLoggingEnabled = false
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(logDir, "log_$timestamp.txt")
        Log.i(appTag, "File logging enabled. Logs will be saved to: ${logFile?.absolutePath}")
    }

    private fun log(level: Int, tag: String?, message: Any?, tr: Throwable? = null) {
        val finalTag = if (tag != null && tag != appTag) {
            "$appTag: $tag"
        } else {
            appTag
        }
        val finalMessage = formatMessage(message)

        when (level) {
            Log.DEBUG -> Log.d(finalTag, finalMessage, tr)
            Log.INFO -> Log.i(finalTag, finalMessage, tr)
            Log.WARN -> Log.w(finalTag, finalMessage, tr)
            Log.ERROR -> Log.e(finalTag, finalMessage, tr)
            Log.VERBOSE -> Log.v(finalTag, finalMessage, tr)
        }

        if (isFileLoggingEnabled && level != Log.VERBOSE) {
            writeToFile(level, finalTag, finalMessage, tr)
        }
    }

    /**
     * Formats messages of various types into a readable string.
     */
    private fun formatMessage(message: Any?): String {
        return when (message) {
            null -> "null"
            is String -> message
            is JSONObject -> message.toString(4)
            is JSONArray -> message.toString(4)
            is Collection<*> -> message.joinToString(prefix = "[", postfix = "]")
            is Array<*> -> message.joinToString(prefix = "[", postfix = "]")
            else -> message.toString()
        }
    }

    fun d(tag: String, message: Any?, tr: Throwable? = null) = log(Log.DEBUG, tag, message, tr)
    fun d(message: Any?, tr: Throwable? = null) = log(Log.DEBUG, null, message, tr)

    fun i(tag: String, message: Any?, tr: Throwable? = null) = log(Log.INFO, tag, message, tr)
    fun i(message: Any?, tr: Throwable? = null) = log(Log.INFO, null, message, tr)

    fun w(tag: String, message: Any?, tr: Throwable? = null) = log(Log.WARN, tag, message, tr)
    fun w(message: Any?, tr: Throwable? = null) = log(Log.WARN, null, message, tr)

    fun e(tag: String, message: Any?, tr: Throwable? = null) = log(Log.ERROR, tag, message, tr)
    fun e(message: Any?, tr: Throwable? = null) = log(Log.ERROR, null, message, tr)

    fun v(tag: String, message: Any?, tr: Throwable? = null) = log(Log.VERBOSE, tag, message, tr)
    fun v(message: Any?, tr: Throwable? = null) = log(Log.VERBOSE, null, message, tr)

    private fun writeToFile(level: Int, tag: String, message: String, tr: Throwable?) {
        val currentLogFile = logFile ?: return

        logScope.launch {
            try {
                FileOutputStream(currentLogFile, true).use { fos ->
                    val levelChar = when (level) {
                        Log.DEBUG -> "D"
                        Log.INFO -> "I"
                        Log.WARN -> "W"
                        Log.ERROR -> "E"
                        else -> "U" // Unknownfeat(util): Refactor logger to be self-contained and modern
                    }
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

                    var logEntry = "$timestamp $levelChar/$tag: $message\n"
                    tr?.let {
                        logEntry += "${Log.getStackTraceString(it)}\n"
                    }

                    fos.write(logEntry.toByteArray())
                }
            } catch (e: IOException) {
                Log.e(appTag, "Failed to write to log file", e)
            }
        }
    }
}
