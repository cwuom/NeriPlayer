package moe.ouom.neriplayer.util

import android.content.Context
import android.os.Build
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.BuildConfig

object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"
    private const val LIBRARY_NAME = "_neri"

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var installed = false

    enum class TestCrashType(val nativeValue: Int) {
        SigSegv(1),
        SigAbrt(2),
    }

    fun init(context: Context) {
        if (installed) return
        if (!ensureLibraryLoaded()) return

        val crashDirectory = runCatching {
            ExceptionHandler.resolveCrashDirectory(context)?.absolutePath
        }.getOrElse { error ->
            NPLogger.e(TAG, "Failed to resolve native crash directory", error)
            return
        } ?: return

        val installSucceeded = runCatching {
            val deviceInfo = buildString {
                append(Build.BRAND)
                append('/')
                append(Build.MANUFACTURER)
                append(' ')
                append(Build.MODEL)
                append(" (")
                append(Build.DEVICE)
                append(", ")
                append(Build.PRODUCT)
                append(')')
            }
            nativeInstall(
                crashDirectory,
                BuildConfig.VERSION_NAME,
                BuildConfig.BUILD_TYPE,
                BuildConfig.BUILD_UUID,
                context.packageName,
                deviceInfo,
                Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" },
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE ?: "unknown"
            )
        }.getOrElse { error ->
            NPLogger.e(TAG, "Failed to install native crash handler", error)
            false
        }

        installed = installSucceeded
        if (installSucceeded) {
            NPLogger.i(TAG, "Native crash handler initialized: $crashDirectory")
        }
    }

    fun triggerTestCrash(context: Context, crashType: TestCrashType) {
        if (!ensureLibraryLoaded() || !installed) {
            ExceptionHandler.handleException(
                source = TAG,
                throwable = IllegalStateException(context.getString(R.string.debug_native_crash_unavailable))
            )
            return
        }
        nativeTriggerTestCrash(crashType.nativeValue)
    }

    private fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        if (loadAttempted) return false

        synchronized(this) {
            if (libraryLoaded) return true
            if (loadAttempted) return false

            loadAttempted = true
            return runCatching {
                System.loadLibrary(LIBRARY_NAME)
                libraryLoaded = true
                true
            }.getOrElse { error ->
                NPLogger.e(TAG, "Failed to load native crash handler library", error)
                false
            }
        }
    }

    @JvmStatic
    private external fun nativeInstall(
        crashDirectory: String,
        appVersion: String,
        buildType: String,
        buildUuid: String,
        packageName: String,
        deviceInfo: String,
        supportedAbis: String,
        sdkInt: Int,
        androidVersion: String
    ): Boolean

    @JvmStatic
    private external fun nativeTriggerTestCrash(crashType: Int)
}
