package moe.ouom.neriplayer.core.player.shizuku

import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Process-local Android compatibility setup used before any hidden framework API is touched. */
object AndroidRuntimeCompatibility {
    private const val TAG = "NeriPlayerRuntime"

    fun installHiddenApiExemptions() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onSuccess { Log.i(TAG, "Hidden API exemptions installed for process") }
            .onFailure { Log.w(TAG, "Unable to install hidden API exemptions", it) }
    }
}
