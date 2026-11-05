package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import moe.ouom.neriplayer.common.logging.NPLogger

private const val ACTIVITY_LAUNCH_TAG = "ActivityLaunchGuard"

internal fun <I> ActivityResultLauncher<I>.tryLaunch(input: I): Boolean =
    guardActivityLaunch { launch(input) }

internal fun Context.tryStartActivity(intent: Intent): Boolean =
    guardActivityLaunch {
        // 语言和密度配置 Context 可能不再关联 Activity，需要在外层启动 Intent 上补齐任务标记
        val launchIntent = if (hasActivityContext()) {
            intent
        } else {
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(launchIntent)
    }

private tailrec fun Context.hasActivityContext(): Boolean = when (this) {
    is Activity -> true
    is ContextWrapper -> baseContext.hasActivityContext()
    else -> false
}

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
