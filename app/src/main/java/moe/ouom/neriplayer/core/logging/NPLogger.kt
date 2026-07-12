package moe.ouom.neriplayer.core.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private data class LogFileEntry(
        val file: File,
        val level: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable?
    )

    private var appTag: String = BuildConfig.TAG
    private var isFileLoggingEnabled = false
    private var logFile: File? = null
    private var initialized = false

    private val logScope = CoroutineScope(Dispatchers.IO)
    private val fileLogChannel = Channel<LogFileEntry>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val fileLogWriterLock = Any()
    private var fileLogWriterJob: Job? = null

    /**
     * Initializes the logger. Prefer calling once early (e.g., Application.onCreate).
     * Safe to call repeatedly; it is idempotent except when toggling file logging.
     *
     * @param context The application context, used for resolving log directory paths.
     * @param defaultTag Optional global log TAG; defaults to BuildConfig.TAG.
     * @param enableFileLogging Whether to enable file logging at init time (can be changed later).
     */
    fun init(context: Context, defaultTag: String? = null, enableFileLogging: Boolean = false) {
        defaultTag?.let { this.appTag = it }

        if (!initialized) {
            initialized = true
            if (enableFileLogging) {
                this.isFileLoggingEnabled = true
                setupLogFile(context)
            }
            i("Logger initialized (fileLogging=$isFileLoggingEnabled)")
            return
        }

        // Already initialized: only process file logging toggle
        setFileLoggingEnabled(context, enableFileLogging)
    }

    private fun setupLogFile(context: Context) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val logDir = File(baseDir, "logs")
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(appTag, "Failed to create log directory")
                isFileLoggingEnabled = false
                return
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            logFile = File(logDir, "log_$timestamp.txt")
            ensureFileLogWriterStarted()
            Log.i(appTag, "File logging enabled. Logs will be saved to: ${logFile?.absolutePath}")
        } catch (t: Throwable) {
            Log.e(appTag, "Failed to setup log file", t)
            isFileLoggingEnabled = false
        }
    }

    /**
     * Dynamically toggles file logging. Creates a new log file when enabling.
     */
    fun setFileLoggingEnabled(context: Context, enabled: Boolean) {
        if (enabled == isFileLoggingEnabled && (enabled && logFile != null)) return
        isFileLoggingEnabled = enabled
        if (enabled) setupLogFile(context)
    }

    private fun log(level: Int, tag: String?, message: Any?, tr: Throwable? = null) {
        // Release 构建中不输出 DEBUG/VERBOSE 级别日志到 logcat，防止敏感信息泄露
        if (!BuildConfig.DEBUG && (level == Log.DEBUG || level == Log.VERBOSE)) {
            if (isFileLoggingEnabled && level == Log.DEBUG) {
                val finalTag = if (tag != null && tag != appTag) "$appTag: $tag" else appTag
                writeToFile(level, finalTag, formatMessage(message), tr)
            }
            return
        }

        val finalTag = if (tag != null && tag != appTag) "$appTag: $tag" else appTag
        val safeTag = ensureLogTag(finalTag)
        val finalMessage = formatMessage(message)

        when (level) {
            Log.DEBUG -> Log.d(safeTag, finalMessage, tr)
            Log.INFO -> Log.i(safeTag, finalMessage, tr)
            Log.WARN -> Log.w(safeTag, finalMessage, tr)
            Log.ERROR -> Log.e(safeTag, finalMessage, tr)
            Log.VERBOSE -> Log.v(safeTag, finalMessage, tr)
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
        ensureFileLogWriterStarted()
        fileLogChannel.trySend(
            LogFileEntry(
                file = currentLogFile,
                level = level,
                tag = tag,
                message = message,
                throwable = tr
            )
        )
    }

    private fun ensureFileLogWriterStarted() {
        synchronized(fileLogWriterLock) {
            if (fileLogWriterJob?.isActive == true) return
            fileLogWriterJob = logScope.launch {
                for (entry in fileLogChannel) {
                    writeLogEntryNow(entry)
                }
            }
        }
    }

    private fun writeLogEntryNow(entry: LogFileEntry) {
        try {
            FileOutputStream(entry.file, true).use { fos ->
                fos.write(formatFileLogEntry(entry).toByteArray())
            }
        } catch (e: IOException) {
            Log.e(appTag, "Failed to write to log file", e)
        }
    }

    private fun formatFileLogEntry(entry: LogFileEntry): String {
        val levelChar = when (entry.level) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "U"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        return buildString {
            append(timestamp)
            append(' ')
            append(levelChar)
            append('/')
            append(entry.tag)
            append(": ")
            append(entry.message)
            append('\n')
            entry.throwable?.let {
                append(Log.getStackTraceString(it))
                append('\n')
            }
        }
    }

    /** Ensures the log tag length is valid on all Android versions. */
    private fun ensureLogTag(tag: String): String {
        return tag
    }

    /**
     * Returns the directory where log files are stored.
     * @param context The application context.
     * @return The log directory File object, or null if not available.
     */
    fun getLogDirectory(context: Context): File? {
        if (!isFileLoggingEnabled) return null

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = baseDir?.let { File(it, "logs") } ?: return null

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }
}
