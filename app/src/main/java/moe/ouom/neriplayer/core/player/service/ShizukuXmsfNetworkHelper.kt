package moe.ouom.neriplayer.core.player.service

import android.content.Context
import android.util.Log
import moe.ouom.neriplayer.shizuku.ShizukuPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.shizuku.ShizukuHook

/**
 * Capsulyric's retrying XMSF helper, adapted for NeriPlayer.
 *
 * The settings page owns the explicit permission request. Playback only waits
 * for a cold-start Shizuku binder and checks an already granted permission, so
 * a lyric update can never open a second permission dialog.
 */
internal object ShizukuXmsfNetworkHelper {
    private const val TAG = "NeriPlayerXmsf"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 500L

    suspend fun setXmsfNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val uid = runCatching { appContext.packageManager.getPackageUid(XMSF_PACKAGE, 0) }
            .getOrElse {
                Log.i(TAG, "XMSF is not installed")
                return false
            }
        // Playback must never open a permission dialog. The settings sheet is the explicit
        // permission entry point; here we only wait for a cold-start binder and check the grant.
        if (!ShizukuPermissionHelper.isPermissionGrantedWhenReady()) {
            Log.w(TAG, "Shizuku is unavailable or permission is not granted")
            return false
        }

        return withContext(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    ShizukuHook.setPackageNetworkingEnabled(uid, enabled)
                    Log.d(TAG, "XMSF networking ${if (enabled) "restored" else "blocked"} for uid=$uid")
                    return@withContext true
                } catch (error: Throwable) {
                    lastFailure = error
                    Log.w(TAG, "Capsulyric hook attempt ${attempt + 1}/$MAX_RETRIES failed", error)
                    if (attempt + 1 < MAX_RETRIES) delay(RETRY_DELAY_MS)
                }
            }
            Log.w(TAG, "No compatible XMSF firewall backend", lastFailure)
            false
        }
    }
}
