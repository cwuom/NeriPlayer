package moe.ouom.neriplayer.core.api.netease

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

private const val NETEASE_YD_TOKEN_TAG = "NERI-NeteaseYdToken"
private const val NETEASE_YD_TOKEN_URL = "https://music.163.com/"
private const val NETEASE_YD_TOKEN_APP_ID = "9d0ef7e0905d422cba1ecf7e73d77e67"
private const val NETEASE_YD_TOKEN_BRIDGE = "__NERI_NETEASE_YD_BRIDGE__"
private const val NETEASE_YD_TOKEN_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"
private const val NETEASE_YD_PAGE_TIMEOUT_MS = 15_000L
private const val NETEASE_YD_READY_TIMEOUT_MS = 12_000L
private const val NETEASE_YD_TOKEN_TIMEOUT_MS = 12_000L
private const val NETEASE_YD_READY_POLL_MS = 400L

internal data class NeteaseYdDeviceSnapshot(
    val token: String = "",
    val sDeviceId: String = "",
    val cookies: Map<String, String> = emptyMap()
)

internal class NeteaseYdDeviceTokenProvider(
    private val context: Context
) {

    suspend fun getSnapshot(): NeteaseYdDeviceSnapshot {
        NPLogger.d(NETEASE_YD_TOKEN_TAG, "getToken start")
        val pageLoaded = CompletableDeferred<Unit>()
        val tokenResult = CompletableDeferred<NeteaseYdDeviceSnapshot>()
        val webView = createWebView(pageLoaded, tokenResult)

        return try {
            val pageReady = waitPageReady(webView, pageLoaded)
            if (!pageReady) {
                NPLogger.w(NETEASE_YD_TOKEN_TAG, "getToken aborted because page did not finish loading")
                return NeteaseYdDeviceSnapshot()
            }

            val apiReady = waitFingerprintApiReady(webView)
            if (!apiReady) {
                NPLogger.w(NETEASE_YD_TOKEN_TAG, "getToken aborted because createNEFingerprint is unavailable")
                return NeteaseYdDeviceSnapshot(cookies = readCookieMap())
            }

            requestToken(webView)
            val snapshot = withTimeoutOrNull(NETEASE_YD_TOKEN_TIMEOUT_MS) {
                tokenResult.await()
            } ?: NeteaseYdDeviceSnapshot(cookies = readCookieMap())
            NPLogger.d(
                NETEASE_YD_TOKEN_TAG,
                "getToken finished tokenLength=${snapshot.token.length} sDeviceIdLength=${snapshot.sDeviceId.length} cookieKeys=${snapshot.cookies.keys}"
            )
            snapshot
        } finally {
            destroyWebView(webView)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun createWebView(
        pageLoaded: CompletableDeferred<Unit>,
        tokenResult: CompletableDeferred<NeteaseYdDeviceSnapshot>
    ): WebView = withContext(Dispatchers.Main) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = NETEASE_YD_TOKEN_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    NPLogger.d(NETEASE_YD_TOKEN_TAG, "pageFinished url=$url")
                    if (!pageLoaded.isCompleted) {
                        pageLoaded.complete(Unit)
                    }
                }
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onToken(raw: String?) {
                        val payload = raw.orEmpty()
                        runCatching {
                            val json = JSONObject(payload)
                            val token = json.optString("token")
                            val error = json.optString("error")
                            val cookieMap = readCookieMap()
                            val sDeviceId = cookieMap["sDeviceId"].orEmpty()
                            NPLogger.d(
                                NETEASE_YD_TOKEN_TAG,
                                "bridge onToken tokenLength=${token.length} error=${error.ifBlank { "<none>" }} " +
                                    "sDeviceIdLength=${sDeviceId.length} cookieKeys=${cookieMap.keys}"
                            )
                            if (!tokenResult.isCompleted) {
                                tokenResult.complete(
                                    NeteaseYdDeviceSnapshot(
                                        token = token.takeIf { it.isNotBlank() }.orEmpty(),
                                        sDeviceId = sDeviceId,
                                        cookies = cookieMap
                                    )
                                )
                            }
                        }.onFailure { error ->
                            NPLogger.w(NETEASE_YD_TOKEN_TAG, "bridge parse failed raw=${payload.take(160)}", error)
                            if (!tokenResult.isCompleted) {
                                tokenResult.complete(
                                    NeteaseYdDeviceSnapshot(cookies = readCookieMap())
                                )
                            }
                        }
                    }
                },
                NETEASE_YD_TOKEN_BRIDGE
            )
            loadUrl(NETEASE_YD_TOKEN_URL)
        }
    }

    private suspend fun waitPageReady(
        webView: WebView,
        pageLoaded: CompletableDeferred<Unit>
    ): Boolean {
        val loaded = withTimeoutOrNull(NETEASE_YD_PAGE_TIMEOUT_MS) {
            pageLoaded.await()
        } != null
        val cookieMap = readCookieMap()
        val sDeviceIdLength = cookieMap["sDeviceId"]?.length ?: 0
        NPLogger.d(
            NETEASE_YD_TOKEN_TAG,
            "waitPageReady loaded=$loaded currentUrl=${withContext(Dispatchers.Main) { webView.url.orEmpty() }}"
        )
        NPLogger.d(
            NETEASE_YD_TOKEN_TAG,
            "waitPageReady cookieKeys=${cookieMap.keys} sDeviceIdLength=$sDeviceIdLength"
        )
        return loaded
    }

    private suspend fun waitFingerprintApiReady(webView: WebView): Boolean {
        val deadline = System.currentTimeMillis() + NETEASE_YD_READY_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            val ready = evaluateJson(webView, READY_CHECK_SCRIPT)?.optBoolean("ready") == true
            NPLogger.d(NETEASE_YD_TOKEN_TAG, "waitFingerprintApiReady attempt=$attempt ready=$ready")
            if (ready) {
                return true
            }
            delay(NETEASE_YD_READY_POLL_MS)
        }
        return false
    }

    private suspend fun requestToken(webView: WebView) {
        NPLogger.d(NETEASE_YD_TOKEN_TAG, "requestToken start")
        evaluateRaw(webView, buildRequestTokenScript())
    }

    private fun readCookieMap(): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie(NETEASE_YD_TOKEN_URL).orEmpty()
        if (raw.isBlank()) {
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        raw.split(';')
            .map(String::trim)
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { pair ->
                val index = pair.indexOf('=')
                if (index > 0) {
                    result[pair.substring(0, index)] = pair.substring(index + 1)
                }
            }
        return result
    }

    private suspend fun evaluateJson(webView: WebView, script: String): JSONObject? {
        val raw = evaluateRaw(webView, script) ?: return null
        val normalized = raw.decodeJsStringLiteral()
        return runCatching { JSONObject(normalized) }
            .onFailure { error ->
                NPLogger.w(NETEASE_YD_TOKEN_TAG, "evaluateJson failed raw=${normalized.take(160)}", error)
            }
            .getOrNull()
    }

    private suspend fun evaluateRaw(webView: WebView, script: String): String? {
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            webView.evaluateJavascript(script, ValueCallback { value ->
                if (!deferred.isCompleted) {
                    deferred.complete(value)
                }
            })
            deferred.await()
        }
    }

    private suspend fun destroyWebView(webView: WebView) = withContext(Dispatchers.Main) {
        runCatching {
            webView.removeJavascriptInterface(NETEASE_YD_TOKEN_BRIDGE)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.destroy()
        }.onFailure { error ->
            NPLogger.w(NETEASE_YD_TOKEN_TAG, "destroyWebView failed", error)
        }
    }

    private fun buildRequestTokenScript(): String {
        return """
            (function() {
              try {
                if (typeof createNEFingerprint !== 'function') {
                  window.$NETEASE_YD_TOKEN_BRIDGE.onToken(JSON.stringify({error: 'createNEFingerprint missing'}));
                  return 'missing';
                }
                var instance = createNEFingerprint({appId: '$NETEASE_YD_TOKEN_APP_ID', timeout: 6000});
                instance.getToken().then(function(result) {
                  window.$NETEASE_YD_TOKEN_BRIDGE.onToken(JSON.stringify({
                    token: (result && result.token) || '',
                    keys: result ? Object.keys(result) : []
                  }));
                }).catch(function(error) {
                  window.$NETEASE_YD_TOKEN_BRIDGE.onToken(JSON.stringify({error: String(error)}));
                });
                return 'started';
              } catch (error) {
                window.$NETEASE_YD_TOKEN_BRIDGE.onToken(JSON.stringify({error: String(error)}));
                return 'error';
              }
            })();
        """.trimIndent()
    }

    private fun String.decodeJsStringLiteral(): String {
        val trimmed = trim()
        if (trimmed == "null" || trimmed.isEmpty()) {
            return ""
        }
        if (trimmed.firstOrNull() != '"' || trimmed.lastOrNull() != '"') {
            return trimmed
        }
        return runCatching { JSONObject("{\"v\":$trimmed}").optString("v") }.getOrDefault(trimmed)
    }

    private companion object {
        private val READY_CHECK_SCRIPT = """
            (function() {
              try {
                return JSON.stringify({
                  ready: typeof createNEFingerprint === 'function',
                  href: location.href
                });
              } catch (error) {
                return JSON.stringify({
                  ready: false,
                  error: String(error)
                });
              }
            })();
        """.trimIndent()
    }
}
