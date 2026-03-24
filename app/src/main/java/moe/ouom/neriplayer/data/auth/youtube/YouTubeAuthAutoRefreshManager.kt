package moe.ouom.neriplayer.data.auth.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import org.json.JSONTokener

internal data class YouTubeAuthAutoRefreshResult(
    val attempted: Boolean = false,
    val refreshed: Boolean = false,
    val authChanged: Boolean = false,
    val reason: String = ""
)

internal fun extractYouTubeRequestFailureCode(error: Throwable): Int? {
    val message = error.message.orEmpty()
    return Regex("""request failed:\s*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun isYouTubeAuthRecoverableFailure(error: Throwable): Boolean {
    return extractYouTubeRequestFailureCode(error) in setOf(401, 403, 429)
}

class YouTubeAuthAutoRefreshManager(
    context: Context,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() },
    private val authHealthProvider: () -> YouTubeAuthHealth = { YouTubeAuthHealth() },
    private val authUpdater: (YouTubeAuthBundle) -> Unit = {}
) {
    companion object {
        private const val TAG = "YouTubeAuthRefresh"
        private val REFRESH_URLS = listOf(
            YOUTUBE_MUSIC_ORIGIN,
            "https://www.youtube.com/?themeRefresh=1"
        )
        private val AUTH_HOSTS = setOf(
            "music.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "m.youtube.com"
        )
        private const val PAGE_LOAD_TIMEOUT_MS = 12_000L
        private const val PAGE_SETTLE_DELAY_MS = 800L
        private const val REFRESH_COOLDOWN_MS = 15L * 60L * 1000L
        private const val FORCE_REFRESH_BACKOFF_MS = 90_000L
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val CIRCUIT_BREAK_MS = 30L * 60L * 1000L
    }

    private data class CapturedRequestHeaders(
        val authorization: String = "",
        val xGoogAuthUser: String = "",
        val origin: String = YOUTUBE_MUSIC_ORIGIN,
        val userAgent: String = ""
    )

    private data class RefreshPageSnapshot(
        val readyState: String = "",
        val hasYtcfg: Boolean = false,
        val loggedIn: Boolean = false,
        val delegatedSessionId: String = "",
        val userSessionId: String = ""
    ) {
        fun hasLiveSessionSignal(): Boolean {
            return loggedIn ||
                delegatedSessionId.isNotBlank() ||
                userSessionId.isNotBlank()
        }
    }

    private val applicationContext = context.applicationContext
    private val accessMutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pendingPageLoad: CompletableDeferred<Boolean>? = null

    @Volatile
    private var capturedHeaders: CapturedRequestHeaders? = null

    @Volatile
    private var lastAttemptAtMs: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var circuitOpenUntilMs: Long = 0L

    internal suspend fun refreshIfNeeded(
        reason: String,
        force: Boolean = false
    ): YouTubeAuthAutoRefreshResult {
        val auth = authProvider().normalized()
        val health = authHealthProvider()
        if (!auth.hasLoginCookies()) {
            return YouTubeAuthAutoRefreshResult(reason = "no_login_cookies")
        }
        return accessMutex.withLock {
            val currentAuth = authProvider().normalized()
            val currentHealth = authHealthProvider()
            val now = System.currentTimeMillis()
            val gateDecision = shouldAttemptRefresh(
                auth = currentAuth,
                health = currentHealth,
                now = now,
                force = force
            )
            if (!gateDecision.allowed) {
                return@withLock YouTubeAuthAutoRefreshResult(reason = gateDecision.reason)
            }

            lastAttemptAtMs = now
            NPLogger.d(
                TAG,
                "refresh attempt reason=$reason force=$force state=${currentHealth.state}"
            )

            val activeWebView = ensureWebView()
            syncCookies(activeWebView, currentAuth)
            REFRESH_URLS.forEach { url ->
                if (!loadUrlAndAwait(activeWebView, url)) {
                    return@forEach
                }
                delay(PAGE_SETTLE_DELAY_MS)
                val refreshedAuth = buildObservedAuthBundle(
                    base = currentAuth,
                    activeWebView = activeWebView
                )
                val pageSnapshot = readPageSnapshot(activeWebView)
                val refreshedHealth = evaluateYouTubeAuthHealth(
                    bundle = refreshedAuth,
                    now = System.currentTimeMillis()
                )
                if (
                    !YouTubeCookieSupport.isLoggedIn(refreshedAuth.cookies) ||
                    refreshedHealth.activeCookieKeys.isEmpty()
                ) {
                    return@forEach
                }

                val authChanged = hasMeaningfulYouTubeAuthChange(currentAuth, refreshedAuth)
                val recoveredActiveSession = currentHealth.activeCookieKeys.isEmpty() &&
                    refreshedHealth.activeCookieKeys.isNotEmpty()
                val pageConfirmedSession = pageSnapshot?.hasLiveSessionSignal() == true
                if (!authChanged && !recoveredActiveSession && !pageConfirmedSession) {
                    NPLogger.w(
                        TAG,
                        "refresh skipped reason=$reason url=$url pageReady=${pageSnapshot?.readyState.orEmpty()} hasYtcfg=${pageSnapshot?.hasYtcfg == true}"
                    )
                    return@forEach
                }

                val shouldPersist = authChanged ||
                    currentHealth.state != refreshedHealth.state ||
                    currentHealth.state != YouTubeAuthState.Valid
                if (shouldPersist) {
                    authUpdater(refreshedAuth)
                }
                consecutiveFailures = 0
                circuitOpenUntilMs = 0L
                NPLogger.i(
                    TAG,
                    "refresh success reason=$reason url=$url authChanged=$shouldPersist state=${refreshedHealth.state} liveSession=$pageConfirmedSession"
                )
                return@withLock YouTubeAuthAutoRefreshResult(
                    attempted = true,
                    refreshed = true,
                    authChanged = shouldPersist,
                    reason = "refreshed"
                )
            }

            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpenUntilMs = System.currentTimeMillis() + CIRCUIT_BREAK_MS
            }
            NPLogger.w(
                TAG,
                "refresh failed reason=$reason failures=$consecutiveFailures circuitUntil=$circuitOpenUntilMs"
            )
            YouTubeAuthAutoRefreshResult(
                attempted = true,
                refreshed = false,
                reason = "refresh_failed"
            )
        }
    }

    private data class RefreshGateDecision(
        val allowed: Boolean,
        val reason: String
    )

    private fun shouldAttemptRefresh(
        auth: YouTubeAuthBundle,
        health: YouTubeAuthHealth,
        now: Long,
        force: Boolean
    ): RefreshGateDecision {
        if (!auth.hasLoginCookies()) {
            return RefreshGateDecision(allowed = false, reason = "no_login_cookies")
        }
        if (!force && health.state == YouTubeAuthState.Valid) {
            return RefreshGateDecision(allowed = false, reason = "auth_valid")
        }
        if (health.state == YouTubeAuthState.Missing) {
            return RefreshGateDecision(allowed = false, reason = "auth_missing")
        }
        if (circuitOpenUntilMs > now) {
            return RefreshGateDecision(allowed = false, reason = "circuit_open")
        }
        val minIntervalMs = if (force) FORCE_REFRESH_BACKOFF_MS else REFRESH_COOLDOWN_MS
        if (lastAttemptAtMs > 0L && now - lastAttemptAtMs < minIntervalMs) {
            return RefreshGateDecision(allowed = false, reason = "cooldown")
        }
        return RefreshGateDecision(allowed = true, reason = "allowed")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }

        WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = RefreshWebViewClient()
        }.also { created ->
            webView = created
        }
    }

    private suspend fun syncCookies(
        activeWebView: WebView,
        auth: YouTubeAuthBundle
    ) = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = auth.normalized(savedAt = auth.savedAt).cookies,
            skipExisting = false,
            includeConsentCookie = true
        )
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
        cookieManager.flush()
    }

    private suspend fun loadUrlAndAwait(
        activeWebView: WebView,
        url: String
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        capturedHeaders = null
        pendingPageLoad = deferred
        withContext(Dispatchers.Main) {
            activeWebView.stopLoading()
            activeWebView.loadUrl(url)
        }
        return withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
            deferred.await()
        } ?: false
    }

    private suspend fun readPageSnapshot(
        activeWebView: WebView
    ): RefreshPageSnapshot? {
        val raw = evaluateJavascript(
            activeWebView = activeWebView,
            script = """
                (() => {
                  const topWindow = window.top;
                  const ytcfg = topWindow?.ytcfg;
                  const getConfig = (key) => {
                    try {
                      if (ytcfg?.get) {
                        return ytcfg.get(key);
                      }
                      return ytcfg?.data_?.[key];
                    } catch (error) {
                      return null;
                    }
                  };
                  return JSON.stringify({
                    readyState: document.readyState || '',
                    hasYtcfg: !!ytcfg,
                    loggedIn: !!getConfig('LOGGED_IN'),
                    delegatedSessionId: String(getConfig('DELEGATED_SESSION_ID') || ''),
                    userSessionId: String(getConfig('USER_SESSION_ID') || '')
                  });
                })()
            """.trimIndent()
        ) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            RefreshPageSnapshot(
                readyState = root.optString("readyState"),
                hasYtcfg = root.optBoolean("hasYtcfg"),
                loggedIn = root.optBoolean("loggedIn"),
                delegatedSessionId = root.optString("delegatedSessionId"),
                userSessionId = root.optString("userSessionId")
            )
        }.getOrNull()
    }

    private fun buildObservedAuthBundle(
        base: YouTubeAuthBundle,
        activeWebView: WebView
    ): YouTubeAuthBundle {
        CookieManager.getInstance().flush()
        val headers = capturedHeaders
        return mergeYouTubeAuthBundle(
            base = base,
            observedCookies = collectYouTubeWebCookies(CookieManager.getInstance()),
            authorization = headers?.authorization.orEmpty(),
            xGoogAuthUser = headers?.xGoogAuthUser.orEmpty(),
            origin = headers?.origin.orEmpty().ifBlank { YOUTUBE_MUSIC_ORIGIN },
            userAgent = headers?.userAgent.orEmpty()
                .ifBlank { activeWebView.settings.userAgentString.orEmpty() },
            savedAt = System.currentTimeMillis()
        )
    }

    private fun captureAuthHeaders(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val currentRequest = request ?: return false
        val host = currentRequest.url.host?.lowercase() ?: return false
        if (host !in AUTH_HOSTS) {
            return false
        }

        val headers = currentRequest.requestHeaders
        val authorization = findHeader(headers, "Authorization")
        val cookieHeader = findHeader(headers, "Cookie")
        val xGoogAuthUser = findHeader(headers, "X-Goog-AuthUser")
        val origin = findHeader(headers, "X-Origin")
            .ifBlank { findHeader(headers, "Origin") }
            .ifBlank { YOUTUBE_MUSIC_ORIGIN }
        val userAgent = findHeader(headers, "User-Agent")
            .ifBlank { view?.settings?.userAgentString.orEmpty() }

        val path = currentRequest.url.encodedPath.orEmpty()
        val hasUsefulCookie = cookieHeader.contains("SAPISID=") ||
            cookieHeader.contains("__Secure-1PAPISID=") ||
            cookieHeader.contains("__Secure-3PAPISID=") ||
            cookieHeader.contains("LOGIN_INFO=")
        val isYouTubeiRequest = path.startsWith("/youtubei/v1/")
        if (!isYouTubeiRequest && authorization.isBlank() && !hasUsefulCookie) {
            return false
        }

        capturedHeaders = CapturedRequestHeaders(
            authorization = authorization,
            xGoogAuthUser = xGoogAuthUser,
            origin = origin,
            userAgent = userAgent
        )
        return true
    }

    private fun findHeader(headers: Map<String, String>, name: String): String {
        return headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    private suspend fun evaluateJavascript(
        activeWebView: WebView,
        script: String
    ): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        activeWebView.evaluateJavascript(script) { raw ->
            result.complete(decodeEvaluateJavascriptValue(raw))
        }
        result.await()
    }

    private fun decodeEvaluateJavascriptValue(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "null") {
            return null
        }
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is String -> parsed
                else -> parsed.toString()
            }
        }.getOrNull()
    }

    private inner class RefreshWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ) = super.shouldInterceptRequest(view, request).also {
            captureAuthHeaders(view, request)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            CookieManager.getInstance().flush()
            pendingPageLoad?.complete(true)
            pendingPageLoad = null
            super.onPageFinished(view, url)
        }
    }
}
