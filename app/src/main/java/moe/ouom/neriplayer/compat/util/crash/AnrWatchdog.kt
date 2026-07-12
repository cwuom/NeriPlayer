package moe.ouom.neriplayer.util

import android.content.Context

internal object AnrWatchdog {
    fun capturePreviousAnrIfNeeded(context: Context) {
        moe.ouom.neriplayer.util.crash.AnrWatchdog.capturePreviousAnrIfNeeded(context)
    }

    fun triggerTestAnr(context: Context) {
        moe.ouom.neriplayer.util.crash.AnrWatchdog.triggerTestAnr(context)
    }
}
