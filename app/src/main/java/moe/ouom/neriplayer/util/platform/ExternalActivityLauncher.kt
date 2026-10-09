package moe.ouom.neriplayer.util.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import moe.ouom.neriplayer.common.logging.NPLogger

private const val EXTERNAL_ACTIVITY_TAG = "ExternalActivity"

/**
 * 精简系统、工作资料或儿童模式下可能没有浏览器、分享目标或文件选择器，
 * 隐式 Intent 直接启动会抛 ActivityNotFoundException；返回 false 时由调用方提示用户
 */
fun Context.tryStartActivity(intent: Intent): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        NPLogger.w(EXTERNAL_ACTIVITY_TAG, "No activity for action=${intent.action}", error)
        false
    } catch (error: SecurityException) {
        NPLogger.w(EXTERNAL_ACTIVITY_TAG, "Activity blocked for action=${intent.action}", error)
        false
    }
}
