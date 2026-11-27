package moe.ouom.neriplayer.ui.feedback

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.util.platform.tryLaunch
import moe.ouom.neriplayer.util.platform.tryStartActivity

/** 启动外部页面失败时给出统一提示，而不是让当前页面因 ActivityNotFoundException 崩溃 */
fun Context.startActivityOrShowUnavailable(intent: Intent): Boolean {
    val started = tryStartActivity(intent)
    if (!started) showNoAppForAction()
    return started
}

/** 部分电视和精简系统没有 DocumentsUI，系统文件选择器无法打开时提示而不是崩溃 */
internal fun <I> ActivityResultLauncher<I>.launchOrShowUnavailable(context: Context, input: I): Boolean {
    val launched = tryLaunch(input)
    if (!launched) context.showNoAppForAction()
    return launched
}

private fun Context.showNoAppForAction() {
    AppFeedback.show(this, getString(CoreCommonR.string.error_no_app_for_action))
}
