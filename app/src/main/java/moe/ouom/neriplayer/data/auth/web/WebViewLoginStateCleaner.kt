package moe.ouom.neriplayer.data.auth.web

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

internal const val ACTION_CLEAR_WEBVIEW_LOGIN_STATE =
    "moe.ouom.neriplayer.action.CLEAR_WEBVIEW_LOGIN_STATE"
internal const val ACTION_WEBVIEW_LOGIN_STATE_CLEARED =
    "moe.ouom.neriplayer.action.WEBVIEW_LOGIN_STATE_CLEARED"
internal const val EXTRA_WEBVIEW_CLEAR_REQUEST_ID =
    "moe.ouom.neriplayer.extra.WEBVIEW_CLEAR_REQUEST_ID"
internal const val EXTRA_WEBVIEW_CLEAR_RECEIVER =
    "moe.ouom.neriplayer.extra.WEBVIEW_CLEAR_RECEIVER"
internal const val EXTRA_WEBVIEW_CLEAR_SUCCEEDED =
    "moe.ouom.neriplayer.extra.WEBVIEW_CLEAR_SUCCEEDED"

private const val WEBVIEW_CLEAR_TIMEOUT_MS = 5_000L
private const val COOKIE_EXPIRED = "; Expires=Thu, 01 Jan 1970 00:00:00 GMT; " +
    "Max-Age=0; Path=/"

internal enum class WebLoginPlatform {
    NETEASE,
    BILI,
    YOUTUBE
}

private data class WebViewLoginStateClearTarget(
    val platform: WebLoginPlatform,
    val receiverClass: Class<out BroadcastReceiver>
)

private data class WebViewCookieScope(
    val urls: List<String>,
    val domains: List<String>,
    val storageOrigins: List<String>
)

private val webViewLoginStateClearTargets = listOf(
    WebViewLoginStateClearTarget(
        platform = WebLoginPlatform.NETEASE,
        receiverClass = NeteaseWebViewLoginStateClearReceiver::class.java
    ),
    WebViewLoginStateClearTarget(
        platform = WebLoginPlatform.BILI,
        receiverClass = BiliWebViewLoginStateClearReceiver::class.java
    ),
    WebViewLoginStateClearTarget(
        platform = WebLoginPlatform.YOUTUBE,
        receiverClass = YouTubeWebViewLoginStateClearReceiver::class.java
    )
)

private val webViewCookieScopes = mapOf(
    WebLoginPlatform.NETEASE to WebViewCookieScope(
        urls = listOf(
            "https://music.163.com",
            "https://y.music.163.com",
            "https://interface.music.163.com",
            "https://interface3.music.163.com",
            "https://api.music.163.com",
            "https://163.com",
            "https://126.net",
            "https://163yun.com"
        ),
        domains = listOf(
            ".music.163.com",
            "music.163.com",
            ".163.com",
            "163.com",
            ".126.net",
            "126.net",
            ".163yun.com",
            "163yun.com"
        ),
        storageOrigins = listOf(
            "https://music.163.com",
            "https://y.music.163.com",
            "https://interface.music.163.com",
            "https://interface3.music.163.com",
            "https://api.music.163.com"
        )
    ),
    WebLoginPlatform.BILI to WebViewCookieScope(
        urls = listOf(
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://www.bilibili.com",
            "https://m.bilibili.com"
        ),
        domains = listOf(".bilibili.com"),
        storageOrigins = listOf(
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://www.bilibili.com",
            "https://m.bilibili.com"
        )
    ),
    WebLoginPlatform.YOUTUBE to WebViewCookieScope(
        urls = listOf(
            "https://accounts.google.com",
            "https://www.google.com",
            "https://google.com",
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com"
        ),
        domains = listOf(".google.com", ".youtube.com"),
        storageOrigins = listOf(
            "https://accounts.google.com",
            "https://www.google.com",
            "https://google.com",
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com"
        )
    )
)

internal suspend fun clearWebViewLoginState() {
    clearCurrentProcessWebViewLoginState(platform = null)
}

internal suspend fun clearWebViewLoginState(platform: WebLoginPlatform) {
    clearCurrentProcessWebViewLoginState(platform)
}

internal suspend fun clearWebViewLoginStateInDedicatedProcess() {
    clearCurrentProcessWebViewLoginState(platform = null)
}

internal suspend fun clearWebViewLoginState(
    context: Context,
    platform: WebLoginPlatform
) {
    clearWebViewLoginState(platform)
    requestRemoteWebViewLoginStateClear(
        context = context,
        platforms = setOf(platform)
    )
}

internal suspend fun clearAllWebViewLoginState(context: Context) {
    clearWebViewLoginState()
    requestRemoteWebViewLoginStateClear(
        context = context,
        platforms = WebLoginPlatform.entries.toSet()
    )
}

internal fun remoteWebViewLoginStateClearReceiverNames(
    platforms: Set<WebLoginPlatform>
): List<String> {
    return webViewLoginStateClearTargets
        .filter { it.platform in platforms }
        .map { it.receiverClass.name }
}

private suspend fun clearCurrentProcessWebViewLoginState(
    platform: WebLoginPlatform?
) {
    withContext(Dispatchers.Main.immediate) {
        val cookieManager = CookieManager.getInstance()
        val knownCookies = runCatching {
            collectKnownCookieNames(
                platforms = platform?.let { setOf(it) }
                    ?: WebLoginPlatform.entries.toSet()
            )
        }.onFailure { error ->
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "NERI-WebLoginState",
                "Could not snapshot WebView cookies before clearing",
                error
            )
        }.getOrDefault(emptyMap())
        if (platform == null) {
            cookieManager.removeAllCookiesAwait()
            cookieManager.removeSessionCookiesAwait()
            runCatching { clearKnownCookies(cookieManager, knownCookies) }
                .onFailure { error ->
                    moe.ouom.neriplayer.core.logging.NPLogger.w(
                        "NERI-WebLoginState",
                        "Could not expire known WebView cookies after full clear",
                        error
                    )
                }
            WebStorage.getInstance().deleteAllData()
        } else {
            clearKnownCookies(cookieManager, knownCookies)
            webViewCookieScopes.getValue(platform).storageOrigins.forEach { origin ->
                WebStorage.getInstance().deleteOrigin(origin)
            }
        }
        cookieManager.flush()
    }
}

private fun collectKnownCookieNames(
    platforms: Set<WebLoginPlatform>
): Map<WebLoginPlatform, Map<String, List<String>>> {
    val result = linkedMapOf<WebLoginPlatform, Map<String, List<String>>>()
    val cookieManager = CookieManager.getInstance()
    platforms.forEach { platform ->
        result[platform] = webViewCookieScopes.getValue(platform).urls.associateWith { url ->
            cookieNames(cookieManager.getCookie(url).orEmpty())
        }
    }
    return result
}

private suspend fun clearKnownCookies(
    cookieManager: CookieManager,
    knownCookies: Map<WebLoginPlatform, Map<String, List<String>>>
) {
    knownCookies.forEach { (platform, cookiesByUrl) ->
        val scope = webViewCookieScopes.getValue(platform)
        cookiesByUrl.forEach { (url, names) ->
            names.forEach { name ->
                cookieManager.setCookieAwait(url, "$name=$COOKIE_EXPIRED")
                scope.domains.forEach { domain ->
                    cookieManager.setCookieAwait(
                        url,
                        "$name=$COOKIE_EXPIRED; Domain=$domain"
                    )
                }
            }
        }
    }
}

private fun cookieNames(rawCookieHeader: String): List<String> {
    return rawCookieHeader
        .split(';')
        .mapNotNull { item ->
            val separator = item.indexOf('=')
            item.takeIf { separator > 0 }
                ?.substring(0, separator)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        .distinct()
}

private suspend fun requestRemoteWebViewLoginStateClear(
    context: Context,
    platforms: Set<WebLoginPlatform>
) {
    val appContext = context.applicationContext
    val targets = webViewLoginStateClearTargets.filter { it.platform in platforms }
    if (targets.isEmpty()) {
        return
    }

    withContext(Dispatchers.Main.immediate) {
        val requestId = UUID.randomUUID().toString()
        val targetComponents = targets.map { target ->
            ComponentName(appContext.packageName, target.receiverClass.name)
        }
        val completedTargets = linkedSetOf<ComponentName>()
        val completed = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID) != requestId) {
                    return
                }
                val receiverName = intent.getStringExtra(EXTRA_WEBVIEW_CLEAR_RECEIVER)
                    ?: return
                val component = ComponentName(appContext.packageName, receiverName)
                if (!intent.getBooleanExtra(EXTRA_WEBVIEW_CLEAR_SUCCEEDED, false)) {
                    moe.ouom.neriplayer.core.logging.NPLogger.w(
                        "NERI-WebLoginState",
                        "Remote WebView state clear failed: $receiverName"
                    )
                }
                completedTargets += component
                if (completedTargets.containsAll(targetComponents)) {
                    completed.complete(Unit)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_WEBVIEW_LOGIN_STATE_CLEARED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            targets.forEachIndexed { index, _ ->
                appContext.sendBroadcast(
                    Intent(ACTION_CLEAR_WEBVIEW_LOGIN_STATE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .setComponent(targetComponents[index])
                        .putExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID, requestId)
                )
            }
            if (withTimeoutOrNull(WEBVIEW_CLEAR_TIMEOUT_MS) { completed.await() } == null) {
                val missing = targetComponents
                    .map { it.className }
                    .filterNot { className -> completedTargets.containsClassName(className) }
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "NERI-WebLoginState",
                    "Timed out clearing remote WebView state: ${missing.joinToString()}"
                )
            }
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }
}

private fun Set<ComponentName>.containsClassName(className: String): Boolean {
    return any { component -> component.className == className }
}

private suspend fun CookieManager.removeAllCookiesAwait() {
    suspendCancellableCoroutine { continuation ->
        removeAllCookies {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}

private suspend fun CookieManager.removeSessionCookiesAwait() {
    suspendCancellableCoroutine { continuation ->
        removeSessionCookies {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}

private suspend fun CookieManager.setCookieAwait(
    url: String,
    value: String
) {
    suspendCancellableCoroutine { continuation ->
        setCookie(url, value) {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}
