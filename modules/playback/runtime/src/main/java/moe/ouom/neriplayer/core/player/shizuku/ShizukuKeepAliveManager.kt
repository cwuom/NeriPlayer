package moe.ouom.neriplayer.core.player.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Keeps one reconnectable Shizuku daemon user-service binding while a feature that needs
 * Shizuku is enabled.
 *
 * The binding is only attempted after the user has already granted permission; it never
 * opens the Shizuku request dialog. Without a grant it waits for Shizuku's binder/permission
 * callbacks instead of polling, and transient bind failures back off up to one minute.
 */
object ShizukuKeepAliveManager {
    private const val TAG = "NeriPlayerShizukuKeepAlive"
    private const val SERVICE_PROCESS_SUFFIX = "shizuku"
    private const val SERVICE_TAG = "neriplayer_keepalive"
    private const val SERVICE_VERSION = 2
    private const val INITIAL_RETRY_DELAY_MS = 5_000L
    private const val MAX_RETRY_DELAY_MS = 60_000L
    private const val BIND_TIMEOUT_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var active = false
    private var listenersRegistered = false
    private var binding = false
    private var boundBinder: IBinder? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS

    private val retryRunnable = Runnable { bindIfPossible() }
    private val bindTimeoutRunnable = Runnable {
        if (binding) {
            binding = false
            Log.w(TAG, "Shizuku user service bind timed out")
            scheduleRetry()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post {
            resetBackoff()
            bindIfPossible()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post {
            binding = false
            boundBinder = null
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            mainHandler.removeCallbacks(retryRunnable)
        }
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                mainHandler.post {
                    resetBackoff()
                    bindIfPossible()
                }
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            boundBinder = service
            resetBackoff()
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

    /** Starts or stops the keepalive binding; safe to call from any thread. */
    fun setActive(context: Context, active: Boolean) {
        val applicationContext = context.applicationContext
        mainHandler.post { setActiveOnMain(applicationContext, active) }
    }

    /** Binds immediately after an explicit grant from the settings page, if a feature needs it. */
    fun ensureBound(context: Context) {
        val applicationContext = context.applicationContext
        mainHandler.post {
            appContext = applicationContext
            resetBackoff()
            bindIfPossible()
        }
    }

    private fun setActiveOnMain(context: Context, active: Boolean) {
        appContext = context
        if (this.active == active) return
        this.active = active
        if (active) {
            registerListeners()
            resetBackoff()
            bindIfPossible()
        } else {
            unregisterListeners()
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            unbind(context)
        }
    }

    private fun bindIfPossible() {
        val context = appContext ?: return
        if (!active || binding || boundBinder != null) return
        if (!ShizukuPermissionHelper.isPermissionGranted()) {
            // Wait for OnBinderReceived / OnRequestPermissionResult instead of polling.
            return
        }

        binding = true
        mainHandler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS)
        runCatching { Shizuku.bindUserService(userServiceArgs(context), serviceConnection) }
            .onFailure {
                binding = false
                mainHandler.removeCallbacks(bindTimeoutRunnable)
                Log.w(TAG, "Unable to bind Shizuku user service", it)
                scheduleRetry()
            }
    }

    private fun unbind(context: Context) {
        if (!binding && boundBinder == null) return
        binding = false
        boundBinder = null
        // remove = true also stops the daemon process once the feature is switched off.
        runCatching { Shizuku.unbindUserService(userServiceArgs(context), serviceConnection, true) }
            .onFailure { Log.w(TAG, "Unable to unbind Shizuku user service", it) }
    }

    private fun scheduleRetry() {
        if (!active) return
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun resetBackoff() {
        retryDelayMs = INITIAL_RETRY_DELAY_MS
    }

    private fun registerListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) return
        listenersRegistered = false
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun userServiceArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuKeepAliveService::class.java.name)
    )
        .tag(SERVICE_TAG)
        .daemon(true)
        .processNameSuffix(SERVICE_PROCESS_SUFFIX)
        .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .version(SERVICE_VERSION)
}
