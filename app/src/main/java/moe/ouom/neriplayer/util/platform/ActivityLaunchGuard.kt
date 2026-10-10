package moe.ouom.neriplayer.util.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import moe.ouom.neriplayer.common.logging.NPLogger

private const val ACTIVITY_LAUNCH_TAG = "ActivityLaunchGuard"

internal fun <I> ActivityResultLauncher<I>.tryLaunch(input: I): Boolean =
    guardActivityLaunch { launch(input) }

internal fun Context.tryStartActivity(intent: Intent): Boolean =
    guardActivityLaunch { startActivity(intent) }

private inline fun guardActivityLaunch(launch: () -> Unit): Boolean {
    return try {
        launch()
        true
    } catch (error: ActivityNotFoundException) {
        NPLogger.w(ACTIVITY_LAUNCH_TAG, "no activity can handle the launch", error)
        false
    } catch (error: SecurityException) {
        NPLogger.w(ACTIVITY_LAUNCH_TAG, "activity launch was blocked", error)
        false
    }
}
