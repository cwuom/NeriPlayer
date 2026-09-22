package moe.ouom.neriplayer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import moe.ouom.neriplayer.BuildConfig
import rikka.shizuku.Shizuku

/** Keeps one reconnectable Shizuku user-service binding for the lifetime of the app process. */
internal object ShizukuKeepAliveManager {
    private const val TAG = "NeriPlayerShizukuKeepAlive"
    private const val SERVICE_PROCESS_SUFFIX = "shizuku"
    private const val SERVICE_TAG = "neriplayer_keepalive"
    private const val SERVICE_VERSION = 1
    private const val RETRY_DELAY_MS = 5_000L
    private const val BIND_TIMEOUT_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var binding = false
    private var boundBinder: IBinder? = null
    private var retryScheduled = false

    private val retryRunnable = Runnable {
        retryScheduled = false
        bindIfPossible()
    }
    private val bindTimeoutRunnable = Runnable {
        if (binding) {
            binding = false
            Log.w(TAG, "Shizuku user service bind timed out")
            scheduleRetry()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            boundBinder = service
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            Log.i(TAG, "Shizuku user service connected: $name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            boundBinder = null
            Log.w(TAG, "Shizuku user service disconnected: $name")
            scheduleRetry()
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        bindIfPossible()
    }

    fun ensureBound(context: Context? = null) {
        context?.let { appContext = it.applicationContext }
        bindIfPossible()
    }

    private fun bindIfPossible() {
        val context = appContext ?: return
        if (binding || boundBinder != null) return
        if (!ShizukuPermissionHelper.isPermissionGranted()) {
            scheduleRetry()
            return
        }

        binding = true
        mainHandler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuKeepAliveService::class.java.name)
        )
            .tag(SERVICE_TAG)
            .daemon(true)
            .processNameSuffix(SERVICE_PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)

        runCatching { Shizuku.bindUserService(args, serviceConnection) }
            .onFailure {
                binding = false
                mainHandler.removeCallbacks(bindTimeoutRunnable)
                Log.w(TAG, "Unable to bind Shizuku user service", it)
                scheduleRetry()
            }
    }

    private fun scheduleRetry() {
        if (appContext == null || retryScheduled) return
        retryScheduled = true
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }
}
