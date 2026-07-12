package moe.ouom.neriplayer.util

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object ExceptionHandler {
    data class ErrorDialogEvent(val title: String, val message: String)

    val errorEvents: Flow<ErrorDialogEvent> =
        moe.ouom.neriplayer.core.crash.ExceptionHandler.errorEvents.map { event ->
            ErrorDialogEvent(
                title = event.title,
                message = event.message
            )
        }

    fun init(
        app: Application,
        installNativeCrashHandler: Boolean = true
    ) {
        moe.ouom.neriplayer.core.crash.ExceptionHandler.init(
            app = app,
            installNativeCrashHandler = installNativeCrashHandler
        )
    }

    fun handleException(source: String, throwable: Throwable, isUncaught: Boolean = false) {
        moe.ouom.neriplayer.core.crash.ExceptionHandler.handleException(
            source = source,
            throwable = throwable,
            isUncaught = isUncaught
        )
    }

    inline fun <T> safeExecute(source: String, block: () -> T): T? {
        return try {
            block()
        } catch (error: Exception) {
            handleException(source, error)
            null
        }
    }

    fun resolveCrashDirectory(context: Context): File? {
        return moe.ouom.neriplayer.core.crash.ExceptionHandler.resolveCrashDirectory(context)
    }

    fun cleanup() {
        moe.ouom.neriplayer.core.crash.ExceptionHandler.cleanup()
    }
}
