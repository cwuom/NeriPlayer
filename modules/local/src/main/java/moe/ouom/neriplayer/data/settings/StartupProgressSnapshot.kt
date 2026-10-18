package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.core.content.edit

private const val STARTUP_PROGRESS_PREFS = "startup_progress_cache"
internal const val STARTUP_PROGRESS_DISCLAIMER_KEY = "disclaimer_accepted"
internal const val STARTUP_PROGRESS_ONBOARDING_KEY = "onboarding_completed"

/**
 * Synchronous mirror of the startup gate flags. DataStore can take seconds on a cold process,
 * and an unknown value must never be mistaken for "not completed", so only a cached `true`
 * is trusted ahead of the real read.
 */
internal fun readStartupProgressCompleted(context: Context, key: String): Boolean? {
    val prefs = context.getSharedPreferences(STARTUP_PROGRESS_PREFS, Context.MODE_PRIVATE)
    return true.takeIf { prefs.getBoolean(key, false) }
}

internal fun persistStartupProgress(context: Context, key: String, value: Boolean) {
    val prefs = context.getSharedPreferences(STARTUP_PROGRESS_PREFS, Context.MODE_PRIVATE)
    if (prefs.contains(key) && prefs.getBoolean(key, false) == value) return
    prefs.edit { putBoolean(key, value) }
}
