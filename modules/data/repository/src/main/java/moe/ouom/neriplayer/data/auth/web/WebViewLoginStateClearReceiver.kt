package moe.ouom.neriplayer.data.auth.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger

internal abstract class WebViewLoginStateClearReceiver(
    private val platform: WebLoginPlatform
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLEAR_WEBVIEW_LOGIN_STATE) {
            return
        }
        val requestId = intent.getStringExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID) ?: return
        NPLogger.d(
            "NERI-WebLoginState",
            "Received WebView state clear request for $platform"
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val succeeded = runCatching {
                // 每个登录接收器都有独立的 WebView 数据目录，因此可以完整清理
                // 这样也能覆盖未知域名或路径的 Cookie
                clearWebViewLoginStateInDedicatedProcess()
            }.onFailure { error ->
                NPLogger.e(
                    "NERI-WebLoginState",
                    "Failed to clear $platform WebView state in login process",
                    error
                )
            }.isSuccess
            NPLogger.d(
                "NERI-WebLoginState",
                "Finished WebView state clear for $platform succeeded=$succeeded"
            )
            try {
                context.sendBroadcast(
                    Intent(ACTION_WEBVIEW_LOGIN_STATE_CLEARED)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID, requestId)
                        .putExtra(EXTRA_WEBVIEW_CLEAR_RECEIVER, javaClass.name)
                        .putExtra(EXTRA_WEBVIEW_CLEAR_SUCCEEDED, succeeded)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal class NeteaseWebViewLoginStateClearReceiver :
    WebViewLoginStateClearReceiver(WebLoginPlatform.NETEASE)

internal class BiliWebViewLoginStateClearReceiver :
    WebViewLoginStateClearReceiver(WebLoginPlatform.BILI)

internal class YouTubeWebViewLoginStateClearReceiver :
    WebViewLoginStateClearReceiver(WebLoginPlatform.YOUTUBE)
