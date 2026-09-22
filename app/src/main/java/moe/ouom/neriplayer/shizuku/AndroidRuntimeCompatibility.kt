package moe.ouom.neriplayer.shizuku

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Process-local Android compatibility setup used before any hidden framework API is touched. */
internal object AndroidRuntimeCompatibility {
    private const val TAG = "NeriPlayerRuntime"

    fun installHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onSuccess { Log.i(TAG, "Hidden API exemptions installed for process") }
            .onFailure { Log.w(TAG, "Unable to install hidden API exemptions", it) }
    }
}
