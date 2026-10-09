package moe.ouom.neriplayer.activity.auth

import android.app.Activity
import android.webkit.WebSettings
import android.widget.Toast
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger

private const val TAG = "WebLoginWebView"

/**
 * 设备没有 WebView 提供方、提供方被停用或正在更新时，第一次触碰 WebView 会抛出
 * MissingWebViewPackageException 等运行时异常
 */
internal fun isWebViewProviderAvailable(probe: () -> Unit): Boolean {
    return runCatching(probe)
        .onFailure { error -> NPLogger.w(TAG, "WebView provider unavailable", error) }
        .isSuccess
}

/** 登录页依赖 WebView，提供方不可用时提示用户并直接结束页面，返回 true 表示已结束 */
internal fun Activity.finishIfWebViewUnavailable(): Boolean {
    if (isWebViewProviderAvailable { WebSettings.getDefaultUserAgent(this) }) {
        return false
    }
    Toast.makeText(
        this,
        getString(CoreCommonR.string.web_login_webview_unavailable),
        Toast.LENGTH_LONG
    ).show()
    finish()
    return true
}
