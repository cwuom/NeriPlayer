package moe.ouom.neriplayer.util

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import moe.ouom.neriplayer.R
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

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
 * File: moe.ouom.neriplayer.util/ExceptionHandler
 * Created: 2025/1/27
 */

/**
 * 全局异常处理器
 * 使用NPLogger记录异常信息，并在主线程显示错误弹窗
 */
object ExceptionHandler {

    private var applicationRef: WeakReference<Application>? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var crashLogFile: File? = null
    private val crashLogScope = CoroutineScope(Dispatchers.IO)
    private val errorDialogEvents = MutableSharedFlow<ErrorDialogEvent>(extraBufferCapacity = 1)

    data class ErrorDialogEvent(val title: String, val message: String)
    val errorEvents: SharedFlow<ErrorDialogEvent> = errorDialogEvents.asSharedFlow()
    
    /**
     * 初始化异常处理器
     * @param appContext 应用上下文
     */
    fun init(app: Application) {
        if (initialized) return
        applicationRef = WeakReference(app)

        // 设置崩溃日志文件
        setupCrashLogFile(app)

        // 设置全局未捕获异常处理器
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                handleException(thread.name, throwable, isUncaught = true)
            }.onFailure { loggingError ->
                NPLogger.e("ExceptionHandler", "Failed while handling uncaught exception", loggingError)
            }
            delegateOrTerminate(thread, throwable)
        }
        initialized = true

        NPLogger.i("ExceptionHandler", "Global exception handler initialized")
        crashLogFile?.let {
            NPLogger.i("ExceptionHandler", "Crash logs will be saved to: ${it.absolutePath}")
        }
    }

    /**
     * 处理异常
     * @param source 异常来源（线程名或组件名）
     * @param throwable 异常对象
     * @param isUncaught 是否为未捕获异常
     */
    fun handleException(source: String, throwable: Throwable, isUncaught: Boolean = false) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val stackTrace = getStackTraceString(throwable)
        
        // 构建异常信息
        val exceptionInfo = buildString {
            appendLine("=== Exception Report ===")
            appendLine("Time: $timestamp")
            appendLine("Source: $source")
            appendLine("Type: ${if (isUncaught) "Uncaught Exception" else "Handled Exception"}")
            appendLine("Exception: ${throwable.javaClass.simpleName}")
            appendLine("Message: ${throwable.message ?: "No message"}")
            appendLine("Stack Trace:")
            appendLine(stackTrace)
            
            // 添加系统信息
            appendLine("=== System Info ===")
            appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("SDK Level: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("App Version: ${moe.ouom.neriplayer.BuildConfig.VERSION_NAME}")
            appendLine("Build Type: ${moe.ouom.neriplayer.BuildConfig.BUILD_TYPE}")
        }
        
        // 使用NPLogger记录异常
        NPLogger.e("ExceptionHandler", "Exception occurred in $source", throwable)
        NPLogger.e("ExceptionHandler", exceptionInfo)

        // 独立写入崩溃日志文件（不依赖NPLogger的开关）
        if (isUncaught) {
            writeCrashLogSync(exceptionInfo)
        } else {
            writeCrashLogToFile(exceptionInfo)
        }

        // 在主线程显示错误弹窗（仅处理可恢复异常）
        if (!isUncaught) {
            showErrorDialogOnMainThread(source, throwable)
        }
    }
    
    /**
     * 创建协程异常处理器
     * @param source 异常来源标识
     * @return CoroutineExceptionHandler
     */
    fun createCoroutineExceptionHandler(source: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            handleException(source, throwable)
        }
    }
    
    /**
     * 安全执行代码块，捕获异常并处理
     * @param source 异常来源标识
     * @param block 要执行的代码块
     * @return 执行结果，如果发生异常返回null
     */
    inline fun <T> safeExecute(source: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            handleException(source, e)
            null
        }
    }
    
    /**
     * 安全执行挂起函数，捕获异常并处理
     * @param source 异常来源标识
     * @param block 要执行的挂起函数
     * @return 执行结果，如果发生异常返回null
     */
    suspend inline fun <T> safeExecuteSuspend(source: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            handleException(source, e)
            null
        }
    }
    
    /**
     * 获取异常的完整堆栈跟踪信息
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        printWriter.close()
        return stringWriter.toString()
    }
    
    /**
     * 在主线程显示错误弹窗
     */
    private fun showErrorDialogOnMainThread(source: String, throwable: Throwable) {
        mainHandler.post {
            val app = applicationRef?.get() ?: run {
                NPLogger.e("ExceptionHandler", "Application reference is null, cannot show error dialog")
                return@post
            }

            try {
                // Apply language settings to get localized strings
                val localizedContext = LanguageManager.applyLanguage(app)
                val title = localizedContext.getString(R.string.exception_title)
                val message = buildString {
                    appendLine(localizedContext.getString(R.string.exception_occurred))
                    appendLine(localizedContext.getString(R.string.exception_source, source))
                    appendLine(localizedContext.getString(R.string.exception_type, throwable.javaClass.simpleName))
                    appendLine(localizedContext.getString(R.string.exception_message, throwable.message ?: localizedContext.getString(R.string.exception_no_detail)))
                    appendLine()
                    appendLine(localizedContext.getString(R.string.exception_logged))
                    appendLine(localizedContext.getString(R.string.exception_contact))
                }

                NPLogger.d("ExceptionHandler", "Emitting error dialog event")
                errorDialogEvents.tryEmit(ErrorDialogEvent(title, message))
            } catch (e: Exception) {
                // Fallback: use original context if language manager fails
                NPLogger.e("ExceptionHandler", "Failed to apply language settings, using fallback", e)
                try {
                    val title = app.getString(R.string.exception_title)
                    val message = buildString {
                        appendLine(app.getString(R.string.exception_occurred))
                        appendLine(app.getString(R.string.exception_source, source))
                        appendLine(app.getString(R.string.exception_type, throwable.javaClass.simpleName))
                        appendLine(app.getString(R.string.exception_message, throwable.message ?: app.getString(R.string.exception_no_detail)))
                        appendLine()
                        appendLine(app.getString(R.string.exception_logged))
                        appendLine(app.getString(R.string.exception_contact))
                    }

                    NPLogger.d("ExceptionHandler", "Emitting error dialog event (fallback)")
                    errorDialogEvents.tryEmit(ErrorDialogEvent(title, message))
                } catch (fallbackError: Exception) {
                    NPLogger.e("ExceptionHandler", "Fallback also failed", fallbackError)
                }
            }
        }
    }

    /**
     * 尽量保持系统原始崩溃链路；若系统未提供默认处理器，则显式终止进程，
     * 避免异常被吞掉后应用继续运行在损坏状态
     */
    private fun delegateOrTerminate(thread: Thread, throwable: Throwable) {
        val handler = previousHandler
        if (handler != null) {
            runCatching {
                handler.uncaughtException(thread, throwable)
            }.onFailure { delegateError ->
                NPLogger.e("ExceptionHandler", "Previous uncaught exception handler failed", delegateError)
                terminateProcess()
            }
            return
        }

        throwable.printStackTrace()
        terminateProcess()
    }

    private fun terminateProcess() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    /**
     * 设置崩溃日志文件
     */
    private fun setupCrashLogFile(context: Context) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val crashDir = File(baseDir, "crashes")
            if (!crashDir.exists() && !crashDir.mkdirs()) {
                NPLogger.e("ExceptionHandler", "Failed to create crash log directory")
                return
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            crashLogFile = File(crashDir, "crash_$timestamp.txt")
        } catch (e: Exception) {
            NPLogger.e("ExceptionHandler", "Failed to setup crash log file", e)
        }
    }

    /**
     * 写入崩溃日志到文件（独立于NPLogger，始终执行）
     */
    private fun writeCrashLogToFile(exceptionInfo: String) {
        val logFile = crashLogFile ?: return

        crashLogScope.launch {
            try {
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(exceptionInfo.toByteArray())
                    fos.write("\n\n".toByteArray())
                }
            } catch (e: Exception) {
                NPLogger.e("ExceptionHandler", "Failed to write crash log to file", e)
            }
        }
    }

    /**
     * 同步写入崩溃日志（用于未捕获异常，确保落盘）
     */
    private fun writeCrashLogSync(exceptionInfo: String) {
        if (crashLogFile == null) {
            applicationRef?.get()?.let(::setupCrashLogFile)
        }
        val logFile = crashLogFile ?: return
        try {
            FileOutputStream(logFile, true).use { fos ->
                fos.write(exceptionInfo.toByteArray())
                fos.write("\n\n".toByteArray())
            }
        } catch (e: Exception) {
            NPLogger.e("ExceptionHandler", "Failed to write crash log to file (sync)", e)
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        NPLogger.i("ExceptionHandler", "Exception handler cleaned up")
    }
}
