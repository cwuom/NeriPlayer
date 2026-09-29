package moe.ouom.neriplayer.shizuku

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Shared, serialized Shizuku permission request used by the Super Island integration. */
internal object ShizukuPermissionHelper {
    private const val TAG = "NeriPlayerShizuku"
    private const val BINDER_READY_RETRIES = 20
    private const val BINDER_READY_RETRY_DELAY_MS = 250L
    private const val PERMISSION_REQUEST_TIMEOUT_MS = 10_000L

    private val permissionMutex = Mutex()
    private var nextRequestCode = 2000

    fun isPermissionGranted(): Boolean {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permission = runCatching { Shizuku.checkSelfPermission() }
        val granted = binderAlive && permission.getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(
            TAG,
            "permission check: binderAlive=$binderAlive result=${permission.getOrNull()} " +
                "exception=${permission.exceptionOrNull()?.javaClass?.simpleName} granted=$granted"
        )
        return granted
    }

    /**
     * Checks an already-granted permission without opening the Shizuku request dialog.
     *
     * Shizuku's provider can be a little late during a cold start. Waiting briefly here avoids
     * treating that transient state as a revoked permission in the playback path.
     */
    suspend fun isPermissionGrantedWhenReady(): Boolean = withContext(Dispatchers.Main.immediate) {
        val binderReady = waitForBinder()
        if (!binderReady) {
            Log.w(TAG, "permission check: binder did not become ready")
            return@withContext false
        }
        val permission = runCatching { Shizuku.checkSelfPermission() }
        val granted = permission.getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(
            TAG,
            "permission check after wait: result=${permission.getOrNull()} " +
                "exception=${permission.exceptionOrNull()?.javaClass?.simpleName} granted=$granted"
        )
        granted
    }

    suspend fun ensurePermission(forceRequest: Boolean = false): Boolean = permissionMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val binderReady = waitForBinder()
            Log.i(TAG, "ensure permission: binderReady=$binderReady forceRequest=$forceRequest")
            if (!binderReady) return@withContext false
            val currentPermission = runCatching { Shizuku.checkSelfPermission() }
                .getOrDefault(PackageManager.PERMISSION_DENIED)
            Log.i(TAG, "ensure permission: currentPermission=$currentPermission")
            if (!forceRequest && currentPermission == PackageManager.PERMISSION_GRANTED) {
                return@withContext true
            }

            val requestResult = withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val requestCode = synchronized(this@ShizukuPermissionHelper) {
                        nextRequestCode = (nextRequestCode + 1).coerceAtLeast(2001)
                        nextRequestCode
                    }
                    lateinit var listener: Shizuku.OnRequestPermissionResultListener
                    listener = Shizuku.OnRequestPermissionResultListener { returnedCode, result ->
                        if (returnedCode != requestCode || !continuation.isActive) {
                            return@OnRequestPermissionResultListener
                        }
                        Shizuku.removeRequestPermissionResultListener(listener)
                        continuation.resume(result == PackageManager.PERMISSION_GRANTED)
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    continuation.invokeOnCancellation {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                    val requestCall = runCatching { Shizuku.requestPermission(requestCode) }
                    Log.i(
                        TAG,
                        "ensure permission: requestPermission code=$requestCode " +
                            "success=${requestCall.isSuccess} exception=" +
                            requestCall.exceptionOrNull()?.javaClass?.simpleName
                    )
                    requestCall.onFailure {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
            if (requestResult == null) {
                Log.w(TAG, "ensure permission: request result timed out")
            }
            // A pre-existing grant may not produce a callback when the request is used only to
            // re-register the package after the Shizuku server was restarted. Re-read it before
            // reporting failure to the settings UI.
            requestResult == true || runCatching {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }

    private suspend fun waitForBinder(): Boolean {
        repeat(BINDER_READY_RETRIES) {
            if (isBinderAlive()) return true
            delay(BINDER_READY_RETRY_DELAY_MS)
        }
        return isBinderAlive()
    }

    private fun isBinderAlive(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }
}
