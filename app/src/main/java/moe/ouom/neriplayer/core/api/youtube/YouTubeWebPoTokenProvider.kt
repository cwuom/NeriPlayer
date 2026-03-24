package moe.ouom.neriplayer.core.api.youtube

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeWebPoTokenProvider
 * Updated: 2026/3/23
 */

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.auth.youtube.applyYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.collectYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.hasMeaningfulYouTubeAuthChange
import moe.ouom.neriplayer.data.auth.youtube.mergeYouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthHealth
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeCookieSupport
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

interface YouTubePoTokenProvider {
    suspend fun warmSession()

    suspend fun getWebRemixGvsPoToken(
        videoId: String,
        visitorData: String,
        remoteHost: String,
        forceRefresh: Boolean = false
    ): String?
}

private data class WebPoPageSnapshot(
    val readyState: String = "",
    val hasYtcfg: Boolean = false,
    val hasWebPoClient: Boolean = false,
    val visitorData: String = "",
    val dataSyncId: String = "",
    val bindsGvsTokenToVideoId: Boolean = false,
    val signInUrl: String = "",
    val signInButtonDetected: Boolean = false
)

private data class CachedWebPoToken(
    val token: String,
    val expiresAtMs: Long
)

private data class WebPoMintResult(
    val status: String = "",
    val token: String = "",
    val error: String = ""
)

private data class AutoSignInGateDecision(
    val allowed: Boolean,
    val reason: String
)

private data class AutoSignInRefreshResult(
    val triggered: Boolean = false,
    val mechanism: String = "",
    val authChanged: Boolean = false,
    val reason: String = ""
)

internal class YouTubeWebPoTokenProvider(
    context: Context,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() },
    private val authHealthProvider: () -> YouTubeAuthHealth = { YouTubeAuthHealth() },
    private val authUpdater: (YouTubeAuthBundle) -> Unit = {}
) : YouTubePoTokenProvider {
    companion object {
        private const val TAG = "YouTubeWebPoToken"
        private const val JS_BRIDGE_NAME = "__NERI_YT_WEBPO_BRIDGE__"
        private val WEB_PO_BOOTSTRAP_URLS = listOf(
            "https://www.youtube.com/?themeRefresh=1",
            "https://music.youtube.com/"
        )
        private const val PAGE_PREPARE_ATTEMPTS = 16
        private const val PAGE_PREPARE_BACKOFF_MS = 1_000L
        private const val MINT_ATTEMPTS = 10
        private const val MINT_BACKOFF_MS = 1_000L
        private const val WEBVIEW_RELOAD_TTL_MS = 20L * 60L * 1000L
        private const val TOKEN_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ASYNC_SCRIPT_TIMEOUT_MS = 20_000L
        private const val TOKEN_CACHE_MAX_SIZE = 16
        private const val SIGN_IN_REFRESH_BACKOFF_MS = 1_500L
        private const val AUTO_SIGN_IN_COOLDOWN_MS = 20L * 60L * 1000L
        private const val AUTO_SIGN_IN_TIMEOUT_MS = 12_000L
        private const val AUTO_SIGN_IN_MAX_CONSECUTIVE_FAILURES = 2
        private const val AUTO_SIGN_IN_CIRCUIT_BREAK_MS = 60L * 60L * 1000L
    }

    private val applicationContext = context.applicationContext
    private val accessMutex = Mutex()
    private val tokenCache = linkedMapOf<String, CachedWebPoToken>()
    private val pendingBridgeResults = linkedMapOf<String, CompletableDeferred<String>>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var preparedCookieFingerprint: String? = null

    @Volatile
    private var preparedAtMs: Long = 0L

    @Volatile
    private var preparedUrl: String = WEB_PO_BOOTSTRAP_URLS.first()

    @Volatile
    private var lastAutoSignInAttemptAtMs: Long = 0L

    @Volatile
    private var lastAutoSignInSuccessAtMs: Long = 0L

    @Volatile
    private var consecutiveAutoSignInFailures: Int = 0

    @Volatile
    private var autoSignInCircuitOpenUntilMs: Long = 0L

    override suspend fun warmSession() {
        val auth = authProvider().normalized()
        if (!auth.hasLoginCookies()) {
            return
        }
        accessMutex.withLock {
            ensurePreparedPage(
                auth = auth,
                authFingerprint = buildAuthFingerprint(auth),
                forceRefresh = false
            )
        }
    }

    override suspend fun getWebRemixGvsPoToken(
        videoId: String,
        visitorData: String,
        remoteHost: String,
        forceRefresh: Boolean
    ): String? {
        val auth = authProvider().normalized()
        return accessMutex.withLock {
        val now = System.currentTimeMillis()
        val authFingerprint = buildAuthFingerprint(auth)
        val pageSnapshot = ensurePreparedPage(
            auth = auth,
            authFingerprint = authFingerprint,
            forceRefresh = forceRefresh
        ) ?: return@withLock null

        val contentBinding = resolveContentBinding(
            videoId = videoId,
            pageSnapshot = pageSnapshot,
            visitorDataFallback = visitorData,
            isAuthenticated = auth.hasLoginCookies()
        ) ?: return@withLock null

        val cacheKey = buildCacheKey(
            contentBinding = contentBinding,
            remoteHost = remoteHost
        )
        if (!forceRefresh) {
            synchronized(tokenCache) {
                tokenCache[cacheKey]
                    ?.takeIf { it.expiresAtMs > now }
                    ?.let { cached ->
                        touchCacheKey(cacheKey, cached)
                        return@withLock cached.token
                    }
            }
        }

        repeat(MINT_ATTEMPTS) { attempt ->
            val result = mintPoToken(contentBinding)
            when {
                result?.status == "ok" && result.token.isNotBlank() -> {
                    synchronized(tokenCache) {
                        tokenCache[cacheKey] = CachedWebPoToken(
                            token = result.token,
                            expiresAtMs = now + TOKEN_TTL_MS
                        )
                        trimCacheLocked()
                    }
                    return@withLock result.token
                }

                result?.status == "backoff" -> {
                    delay(MINT_BACKOFF_MS)
                }

                attempt < MINT_ATTEMPTS - 1 -> {
                    NPLogger.w(
                        TAG,
                        "WebPoClient mint failed (attempt=${attempt + 1}): ${result?.error.orEmpty()}"
                    )
                    ensurePreparedPage(
                        auth = auth,
                        authFingerprint = authFingerprint,
                        forceRefresh = true
                    ) ?: return@withLock null
                }

                else -> {
                    NPLogger.w(
                        TAG,
                        "WebPoClient mint failed: ${result?.error.orEmpty()}"
                    )
                }
            }
        }

        null
        }
    }

    private fun resolveContentBinding(
        videoId: String,
        pageSnapshot: WebPoPageSnapshot,
        visitorDataFallback: String,
        isAuthenticated: Boolean
    ): String? {
        if (pageSnapshot.bindsGvsTokenToVideoId) {
            return videoId.takeIf { it.isNotBlank() }
        }
        if (isAuthenticated) {
            return pageSnapshot.dataSyncId.takeIf { it.isNotBlank() }
        }
        return pageSnapshot.visitorData.ifBlank { visitorDataFallback }
            .takeIf { it.isNotBlank() }
    }

    private suspend fun ensurePreparedPage(
        auth: YouTubeAuthBundle,
        authFingerprint: String,
        forceRefresh: Boolean
    ): WebPoPageSnapshot? {
        val shouldReload = forceRefresh ||
            webView == null ||
            preparedCookieFingerprint != authFingerprint ||
            System.currentTimeMillis() - preparedAtMs > WEBVIEW_RELOAD_TTL_MS
        if (!shouldReload) {
            return readPageSnapshot()
        }

        val activeWebView = ensureWebView()
        syncCookies(activeWebView, auth)
        persistObservedAuthIfNeeded(currentAuth = auth, activeWebView = activeWebView)
        preparedCookieFingerprint = authFingerprint
        var autoSignInHandled = false
        var autoSignInPendingRecovery = false

        for (bootstrapUrl in WEB_PO_BOOTSTRAP_URLS) {
            withContext(Dispatchers.Main) {
                activeWebView.settings.userAgentString = auth.resolveBootstrapUserAgent()
                activeWebView.stopLoading()
                activeWebView.loadUrl(bootstrapUrl)
            }

            repeat(PAGE_PREPARE_ATTEMPTS) { attempt ->
                delay(PAGE_PREPARE_BACKOFF_MS)
                val snapshot = readPageSnapshot() ?: return@repeat
                if (snapshot.hasYtcfg && snapshot.hasWebPoClient) {
                    persistObservedAuthIfNeeded(currentAuth = auth, activeWebView = activeWebView)
                    if (autoSignInPendingRecovery) {
                        recordAutoSignInSuccess(
                            detail = "page_ready url=$bootstrapUrl preparedUrl=$preparedUrl"
                        )
                        autoSignInPendingRecovery = false
                    }
                    preparedAtMs = System.currentTimeMillis()
                    preparedUrl = bootstrapUrl
                    return snapshot
                }
                if (!autoSignInHandled && snapshot.signInButtonDetected) {
                    autoSignInHandled = true
                    val authHealth = authHealthProvider()
                    val gateDecision = shouldAttemptAutoSignInRefresh(
                        auth = auth,
                        health = authHealth,
                        now = System.currentTimeMillis()
                    )
                    if (!gateDecision.allowed) {
                        NPLogger.i(
                            TAG,
                            "auto_sign_in skipped reason=${gateDecision.reason} state=${authHealth.state} url=$bootstrapUrl signInUrl=${snapshot.signInUrl.isNotBlank()}"
                        )
                        return@repeat
                    }

                    recordAutoSignInAttempt(
                        detail = "state=${authHealth.state} url=$bootstrapUrl signInUrl=${snapshot.signInUrl.isNotBlank()}"
                    )
                    val refreshResult = triggerSignInRefresh(
                        activeWebView = activeWebView,
                        bootstrapUrl = bootstrapUrl,
                        currentAuth = auth,
                        snapshot = snapshot
                    )
                    if (!refreshResult.triggered) {
                        recordAutoSignInFailure(
                            reason = refreshResult.reason.ifBlank { "trigger_failed" }
                        )
                        return@repeat
                    }

                    NPLogger.i(
                        TAG,
                        "auto_sign_in triggered mechanism=${refreshResult.mechanism} authChanged=${refreshResult.authChanged} state=${authHealth.state}"
                    )
                    if (refreshResult.authChanged) {
                        recordAutoSignInSuccess(
                            detail = "auth_changed mechanism=${refreshResult.mechanism}"
                        )
                    } else {
                        autoSignInPendingRecovery = true
                    }
                    return@repeat
                }
                if (attempt == PAGE_PREPARE_ATTEMPTS - 1) {
                    NPLogger.w(
                        TAG,
                        "WebPoClient unavailable on $bootstrapUrl, ready=${snapshot.readyState}, ytcfg=${snapshot.hasYtcfg}, signInDetected=${snapshot.signInButtonDetected}"
                    )
                }
            }
        }

        if (autoSignInPendingRecovery) {
            recordAutoSignInFailure(reason = "post_refresh_page_unavailable")
        }

        return readPageSnapshot()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }

        WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(WebPoResultBridge(), JS_BRIDGE_NAME)
        }.also { created ->
            webView = created
        }
    }

    private suspend fun syncCookies(
        activeWebView: WebView,
        auth: YouTubeAuthBundle
    ) = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        val cookies = auth.normalized(savedAt = auth.savedAt).cookies
        applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = cookies,
            skipExisting = true,
            includeConsentCookie = true
        )
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
        cookieManager.flush()
    }

    private suspend fun persistObservedAuthIfNeeded(
        currentAuth: YouTubeAuthBundle,
        activeWebView: WebView
    ): Boolean = withContext(Dispatchers.Main) {
        val observedCookies = collectYouTubeWebCookies(CookieManager.getInstance())
        if (!YouTubeCookieSupport.isLoggedIn(observedCookies)) {
            return@withContext false
        }

        val mergedAuth = mergeYouTubeAuthBundle(
            base = currentAuth,
            observedCookies = observedCookies,
            userAgent = activeWebView.settings.userAgentString.orEmpty(),
            savedAt = System.currentTimeMillis()
        )
        if (hasMeaningfulYouTubeAuthChange(currentAuth, mergedAuth)) {
            authUpdater(mergedAuth)
            return@withContext true
        }
        false
    }

    private suspend fun triggerSignInRefresh(
        activeWebView: WebView,
        bootstrapUrl: String,
        currentAuth: YouTubeAuthBundle,
        snapshot: WebPoPageSnapshot
    ): AutoSignInRefreshResult {
        return withTimeoutOrNull(AUTO_SIGN_IN_TIMEOUT_MS) {
            val mechanism = if (snapshot.signInUrl.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    activeWebView.stopLoading()
                    activeWebView.loadUrl(snapshot.signInUrl)
                }
                "href"
            } else if (triggerSignInButtonClick()) {
                "click"
            } else {
                return@withTimeoutOrNull AutoSignInRefreshResult(
                    triggered = false,
                    reason = "sign_in_trigger_unavailable"
                )
            }

            delay(SIGN_IN_REFRESH_BACKOFF_MS)
            val authChanged = persistObservedAuthIfNeeded(
                currentAuth = currentAuth,
                activeWebView = activeWebView
            )
            withContext(Dispatchers.Main) {
                activeWebView.stopLoading()
                activeWebView.loadUrl(bootstrapUrl)
            }
            AutoSignInRefreshResult(
                triggered = true,
                mechanism = mechanism,
                authChanged = authChanged
            )
        } ?: AutoSignInRefreshResult(
            triggered = false,
            reason = "timeout"
        )
    }

    private suspend fun triggerSignInButtonClick(): Boolean {
        val raw = evaluateJavascript(
            """
                (() => {
                  const normalize = (value) => String(value || '').trim().toLowerCase();
                  const matchesSignIn = (value) => ['sign in', '登录', '登入']
                    .some((token) => normalize(value).includes(token));
                  const candidates = Array.from(
                    document.querySelectorAll(
                      'a[href], button, div[role="button"], yt-button-renderer, yt-button-shape, tp-yt-paper-button'
                    )
                  );
                  for (const candidate of candidates) {
                    const nestedAnchor = candidate.matches?.('a[href]')
                      ? candidate
                      : candidate.querySelector?.('a[href]');
                    const label = [
                      candidate.innerText,
                      candidate.textContent,
                      candidate.getAttribute?.('aria-label'),
                      candidate.getAttribute?.('title'),
                      nestedAnchor?.innerText,
                      nestedAnchor?.textContent
                    ].join(' ');
                    if (matchesSignIn(label)) {
                      candidate.click?.();
                      nestedAnchor?.click?.();
                      return 'true';
                    }
                  }
                  return 'false';
                })()
            """.trimIndent()
        )
        return raw == "true"
    }

    private suspend fun readPageSnapshot(): WebPoPageSnapshot? {
        val raw = evaluateJavascript(
            """
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
                  const findFactory = () => {
                    try {
                      const direct = topWindow?.['havuokmhhs-0']?.bevasrs?.wpc;
                      if (typeof direct === 'function') {
                        return direct;
                      }
                      for (const key of Object.getOwnPropertyNames(topWindow || {})) {
                        const candidate = topWindow?.[key]?.bevasrs?.wpc;
                        if (typeof candidate === 'function') {
                          return candidate;
                        }
                      }
                    } catch (error) {}
                    return null;
                  };
                  const webPlayerContexts = getConfig('WEB_PLAYER_CONTEXT_CONFIGS') || {};
                  const bindsToVideoId = Object.values(webPlayerContexts).some((context) => {
                    const flags = String(context?.serializedExperimentFlags || '');
                    return flags.includes('html5_generate_content_po_token=true');
                  });
                  const normalize = (value) => String(value || '').trim().toLowerCase();
                  const matchesSignIn = (value) => ['sign in', '登录', '登入']
                    .some((token) => normalize(value).includes(token));
                  const toAbsoluteUrl = (value) => {
                    try {
                      return value ? new URL(value, location.href).toString() : '';
                    } catch (error) {
                      return '';
                    }
                  };
                  const findSignInEntry = () => {
                    try {
                      const candidates = Array.from(
                        document.querySelectorAll(
                          'a[href], button, div[role="button"], yt-button-renderer, yt-button-shape, tp-yt-paper-button'
                        )
                      );
                      for (const candidate of candidates) {
                        const nestedAnchor = candidate.matches?.('a[href]')
                          ? candidate
                          : candidate.querySelector?.('a[href]');
                        const href = toAbsoluteUrl(
                          nestedAnchor?.href
                            || candidate.href
                            || candidate.getAttribute?.('href')
                            || nestedAnchor?.getAttribute?.('href')
                        );
                        const label = [
                          candidate.innerText,
                          candidate.textContent,
                          candidate.getAttribute?.('aria-label'),
                          candidate.getAttribute?.('title'),
                          nestedAnchor?.innerText,
                          nestedAnchor?.textContent
                        ].join(' ');
                        if (
                          href.includes('accounts.google.com')
                          || href.includes('ServiceLogin')
                          || matchesSignIn(label)
                        ) {
                          return {
                            detected: true,
                            url: href
                          };
                        }
                      }
                    } catch (error) {}
                    return {
                      detected: false,
                      url: ''
                    };
                  };
                  const signInEntry = findSignInEntry();
                  return JSON.stringify({
                    readyState: document.readyState || '',
                    hasYtcfg: !!ytcfg,
                    hasWebPoClient: !!findFactory(),
                    visitorData: String(
                      getConfig('VISITOR_DATA')
                        || getConfig('INNERTUBE_CONTEXT')?.client?.visitorData
                        || ''
                    ),
                    dataSyncId: String(
                      getConfig('DATASYNC_ID')
                        || getConfig('datasyncId')
                        || ''
                    ),
                    bindsGvsTokenToVideoId: !!bindsToVideoId,
                    signInUrl: String(signInEntry.url || ''),
                    signInButtonDetected: !!signInEntry.detected
                  });
                })()
            """.trimIndent()
        ) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            WebPoPageSnapshot(
                readyState = root.optString("readyState"),
                hasYtcfg = root.optBoolean("hasYtcfg"),
                hasWebPoClient = root.optBoolean("hasWebPoClient"),
                visitorData = root.optString("visitorData"),
                dataSyncId = root.optString("dataSyncId"),
                bindsGvsTokenToVideoId = root.optBoolean("bindsGvsTokenToVideoId"),
                signInUrl = root.optString("signInUrl"),
                signInButtonDetected = root.optBoolean("signInButtonDetected")
            )
        }.getOrNull()
    }

    private suspend fun mintPoToken(
        contentBinding: String
    ): WebPoMintResult? {
        val quotedBinding = JSONObject.quote(contentBinding)
        val raw = runAsyncJavascript(
            """
                const findFactory = () => {
                  const topWindow = window.top;
                  try {
                    const direct = topWindow?.['havuokmhhs-0']?.bevasrs?.wpc;
                    if (typeof direct === 'function') {
                      return direct;
                    }
                    for (const key of Object.getOwnPropertyNames(topWindow || {})) {
                      const candidate = topWindow?.[key]?.bevasrs?.wpc;
                      if (typeof candidate === 'function') {
                        return candidate;
                      }
                    }
                  } catch (error) {}
                  return null;
                };
                const factory = findFactory();
                if (!factory) {
                  __neriDone(JSON.stringify({ status: 'missing', error: 'WebPoClient unavailable' }));
                  return;
                }
                try {
                  const client = await factory();
                  const token = await client.mws({
                    c: $quotedBinding,
                    mc: false,
                    me: false
                  });
                  __neriDone(JSON.stringify({ status: 'ok', token: String(token || '') }));
                } catch (error) {
                  const message = String(error || '');
                  if (message.includes('SDF:notready')) {
                    __neriDone(JSON.stringify({ status: 'backoff', error: message }));
                    return;
                  }
                  __neriDone(JSON.stringify({ status: 'error', error: message }));
                }
            """.trimIndent()
        ) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            WebPoMintResult(
                status = root.optString("status"),
                token = root.optString("token"),
                error = root.optString("error")
            )
        }.getOrNull()
    }

    private suspend fun evaluateJavascript(script: String): String? {
        val activeWebView = webView ?: return null
        return withContext(Dispatchers.Main) {
            val result = CompletableDeferred<String?>()
            activeWebView.evaluateJavascript(script) { raw ->
                result.complete(decodeEvaluateJavascriptValue(raw))
            }
            result.await()
        }
    }

    private suspend fun runAsyncJavascript(script: String): String? {
        val activeWebView = webView ?: return null
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        synchronized(pendingBridgeResults) {
            pendingBridgeResults[requestId] = deferred
        }

        return try {
            withContext(Dispatchers.Main) {
                activeWebView.evaluateJavascript(
                    """
                        (async () => {
                          const __neriDone = (payload) => {
                            try {
                              const encoded = btoa(unescape(encodeURIComponent(String(payload || ''))));
                              $JS_BRIDGE_NAME.postResult(${JSONObject.quote(requestId)}, encoded);
                            } catch (error) {
                              $JS_BRIDGE_NAME.postResult(${JSONObject.quote(requestId)}, '');
                            }
                          };
                          try {
                            $script
                          } catch (error) {
                            __neriDone(JSON.stringify({ status: 'error', error: String(error || '') }));
                          }
                        })();
                    """.trimIndent(),
                    null
                )
            }
            withTimeoutOrNull(ASYNC_SCRIPT_TIMEOUT_MS) { deferred.await() }
        } finally {
            synchronized(pendingBridgeResults) {
                pendingBridgeResults.remove(requestId)
            }
        }
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

    private fun buildAuthFingerprint(auth: YouTubeAuthBundle): String {
        return buildString {
            append(auth.effectiveCookieHeader().hashCode())
            append('|')
            append(auth.resolveBootstrapUserAgent())
        }
    }

    private fun shouldAttemptAutoSignInRefresh(
        auth: YouTubeAuthBundle,
        health: YouTubeAuthHealth,
        now: Long
    ): AutoSignInGateDecision {
        if (!auth.hasLoginCookies()) {
            return AutoSignInGateDecision(allowed = false, reason = "no_login_cookies")
        }
        if (health.state == YouTubeAuthState.Missing) {
            return AutoSignInGateDecision(allowed = false, reason = "auth_missing")
        }
        if (autoSignInCircuitOpenUntilMs > now) {
            return AutoSignInGateDecision(allowed = false, reason = "circuit_open")
        }
        if (lastAutoSignInAttemptAtMs > 0L && now - lastAutoSignInAttemptAtMs < AUTO_SIGN_IN_COOLDOWN_MS) {
            return AutoSignInGateDecision(allowed = false, reason = "cooldown")
        }
        return AutoSignInGateDecision(allowed = true, reason = "allowed")
    }

    private fun recordAutoSignInAttempt(detail: String) {
        lastAutoSignInAttemptAtMs = System.currentTimeMillis()
        NPLogger.i(
            TAG,
            "auto_sign_in attempt detail=$detail cooldownMs=$AUTO_SIGN_IN_COOLDOWN_MS failures=$consecutiveAutoSignInFailures"
        )
    }

    private fun recordAutoSignInSuccess(detail: String) {
        lastAutoSignInSuccessAtMs = System.currentTimeMillis()
        consecutiveAutoSignInFailures = 0
        autoSignInCircuitOpenUntilMs = 0L
        NPLogger.i(
            TAG,
            "auto_sign_in success detail=$detail lastSuccessAt=$lastAutoSignInSuccessAtMs"
        )
    }

    private fun recordAutoSignInFailure(reason: String) {
        consecutiveAutoSignInFailures += 1
        if (consecutiveAutoSignInFailures >= AUTO_SIGN_IN_MAX_CONSECUTIVE_FAILURES) {
            autoSignInCircuitOpenUntilMs = System.currentTimeMillis() + AUTO_SIGN_IN_CIRCUIT_BREAK_MS
        }
        NPLogger.w(
            TAG,
            "auto_sign_in failure reason=$reason failures=$consecutiveAutoSignInFailures circuitUntil=$autoSignInCircuitOpenUntilMs"
        )
    }

    private fun buildCacheKey(
        contentBinding: String,
        remoteHost: String
    ): String {
        return "$contentBinding|$remoteHost"
    }

    private fun touchCacheKey(
        cacheKey: String,
        entry: CachedWebPoToken
    ) {
        synchronized(tokenCache) {
            tokenCache.remove(cacheKey)
            tokenCache[cacheKey] = entry
        }
    }

    private fun trimCacheLocked() {
        while (tokenCache.size > TOKEN_CACHE_MAX_SIZE) {
            val eldestKey = tokenCache.entries.firstOrNull()?.key ?: break
            tokenCache.remove(eldestKey)
        }
    }

    private inner class WebPoResultBridge {
        @JavascriptInterface
        fun postResult(requestId: String, encodedPayload: String) {
            val payload = runCatching {
                if (encodedPayload.isBlank()) {
                    ""
                } else {
                    String(Base64.decode(encodedPayload, Base64.DEFAULT), Charsets.UTF_8)
                }
            }.getOrDefault("")

            synchronized(pendingBridgeResults) {
                pendingBridgeResults.remove(requestId)?.complete(payload)
            }
        }
    }
}
