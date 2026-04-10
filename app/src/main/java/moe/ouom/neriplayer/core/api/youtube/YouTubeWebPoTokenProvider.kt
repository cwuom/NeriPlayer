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
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.auth.youtube.applyYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeCookieSupport
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.data.platform.youtube.installYouTubeBackgroundWebViewGuard
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeBootstrapHost
import moe.ouom.neriplayer.data.platform.youtube.removeYouTubeBackgroundWebViewGuard
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.util.isAllowedMainFrameRequest
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

    fun clearSession() {}
}

private const val YOUTUBE_WEB_PO_WARM_BOOTSTRAP_URL = "https://music.youtube.com/"
private const val YOUTUBE_WEB_PO_FOREGROUND_BOOTSTRAP_URL = "https://www.youtube.com/?themeRefresh=1"

internal fun resolveWebPoBootstrapUrls(backgroundWarmup: Boolean): List<String> {
    return if (backgroundWarmup) {
        listOf(YOUTUBE_WEB_PO_WARM_BOOTSTRAP_URL)
    } else {
        listOf(
            YOUTUBE_WEB_PO_FOREGROUND_BOOTSTRAP_URL,
            YOUTUBE_WEB_PO_WARM_BOOTSTRAP_URL
        )
    }
}

private data class WebPoPageSnapshot(
    val readyState: String = "",
    val hasYtcfg: Boolean = false,
    val hasWebPoClient: Boolean = false,
    val visitorData: String = "",
    val dataSyncId: String = "",
    val bindsGvsTokenToVideoId: Boolean = false
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

internal fun buildYouTubeWebPoAuthFingerprint(auth: YouTubeAuthBundle): String {
    return auth.buildBootstrapAuthFingerprint()
}

internal class YouTubeWebPoTokenProvider(
    context: Context,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() }
) : YouTubePoTokenProvider {
    companion object {
        private const val TAG = "YouTubeWebPoToken"
        private const val JS_BRIDGE_NAME = "__NERI_YT_WEBPO_BRIDGE__"
        private const val FOREGROUND_PAGE_PREPARE_ATTEMPTS = 3
        private const val FORCE_REFRESH_PAGE_PREPARE_ATTEMPTS = 5
        private const val BACKGROUND_PAGE_PREPARE_ATTEMPTS = 8
        private const val PAGE_PREPARE_BACKOFF_MS = 500L
        private const val PAGE_READY_UNAVAILABLE_THRESHOLD = 2
        private const val MINT_ATTEMPTS = 10
        private const val MINT_BACKOFF_MS = 1_000L
        private const val WEBVIEW_RELOAD_TTL_MS = 20L * 60L * 1000L
        private const val TOKEN_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ASYNC_SCRIPT_TIMEOUT_MS = 20_000L
        private const val TOKEN_CACHE_MAX_SIZE = 16
        private const val UNAVAILABLE_LOG_INTERVAL_MS = 10L * 60L * 1000L
    }

    private val applicationContext = context.applicationContext
    private val accessMutex = Mutex()
    private val tokenCache = linkedMapOf<String, CachedWebPoToken>()
    private val pendingBridgeResults = linkedMapOf<String, CompletableDeferred<String>>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var backgroundWebViewGuard: ScriptHandler? = null

    @Volatile
    private var preparedCookieFingerprint: String? = null

    @Volatile
    private var preparedAtMs: Long = 0L

    @Volatile
    private var preparedUrl: String = YOUTUBE_WEB_PO_WARM_BOOTSTRAP_URL

    @Volatile
    private var lastUnavailableLogAtMs: Long = 0L

    override suspend fun warmSession() {
        val auth = authProvider().normalized()
        if (!auth.hasLoginCookies()) {
            return
        }
        if (!accessMutex.tryLock()) {
            NPLogger.d(TAG, "warmSession skipped because provider is busy")
            return
        }
        val startedAtMs = System.currentTimeMillis()
        try {
            try {
                ensurePreparedPage(
                    auth = auth,
                    authFingerprint = buildAuthFingerprint(auth),
                    forceRefresh = false,
                    maxAttempts = BACKGROUND_PAGE_PREPARE_ATTEMPTS,
                    bootstrapUrls = resolveWebPoBootstrapUrls(backgroundWarmup = true)
                )
            } finally {
                setWebViewActive(active = false)
            }
            NPLogger.d(
                TAG,
                "warmSession completed elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        } finally {
            accessMutex.unlock()
        }
    }

    override fun clearSession() {
        synchronized(tokenCache) {
            tokenCache.clear()
        }
        preparedCookieFingerprint = null
        preparedAtMs = 0L
        preparedUrl = YOUTUBE_WEB_PO_WARM_BOOTSTRAP_URL
        destroyPreparedWebViewAsync()
    }

    override suspend fun getWebRemixGvsPoToken(
        videoId: String,
        visitorData: String,
        remoteHost: String,
        forceRefresh: Boolean
    ): String? {
        val auth = authProvider().normalized()
        val startedAtMs = System.currentTimeMillis()
        return accessMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val authFingerprint = buildAuthFingerprint(auth)
                val pageSnapshot = ensurePreparedPage(
                    auth = auth,
                    authFingerprint = authFingerprint,
                    forceRefresh = forceRefresh,
                    maxAttempts = if (forceRefresh) {
                        FORCE_REFRESH_PAGE_PREPARE_ATTEMPTS
                    } else {
                        FOREGROUND_PAGE_PREPARE_ATTEMPTS
                    },
                    bootstrapUrls = resolveWebPoBootstrapUrls(backgroundWarmup = false)
                ) ?: return@withLock null
                if (!pageSnapshot.hasWebPoClient) {
                    return@withLock null
                }

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
                                NPLogger.d(
                                    TAG,
                                    "GVS PO token cache hit videoId=$videoId ttlMs=${cached.expiresAtMs - now} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                                )
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
                            NPLogger.d(
                                TAG,
                                "GVS PO token minted videoId=$videoId elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                            )
                            return@withLock result.token
                        }

                        result?.status == "backoff" -> {
                            delay(MINT_BACKOFF_MS)
                        }

                        result?.status == "missing" -> {
                            logUnavailable(
                                url = preparedUrl,
                                snapshot = pageSnapshot
                            )
                            return@withLock null
                        }

                        attempt < MINT_ATTEMPTS - 1 -> {
                            NPLogger.w(
                                TAG,
                                "WebPoClient mint failed (attempt=${attempt + 1}): ${result?.error.orEmpty()}"
                            )
                            ensurePreparedPage(
                                auth = auth,
                                authFingerprint = authFingerprint,
                                forceRefresh = true,
                                maxAttempts = FORCE_REFRESH_PAGE_PREPARE_ATTEMPTS,
                                bootstrapUrls = resolveWebPoBootstrapUrls(backgroundWarmup = false)
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
            } finally {
                setWebViewActive(active = false)
            }
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
        forceRefresh: Boolean,
        maxAttempts: Int,
        bootstrapUrls: List<String>
    ): WebPoPageSnapshot? {
        val shouldReload = forceRefresh ||
            webView == null ||
            preparedCookieFingerprint != authFingerprint ||
            System.currentTimeMillis() - preparedAtMs > WEBVIEW_RELOAD_TTL_MS
        if (!shouldReload) {
            setWebViewActive(active = true)
            return readPageSnapshot()
        }

        val activeWebView = ensureWebView()
        setWebViewActive(active = true)
        syncCookies(activeWebView, auth)
        preparedCookieFingerprint = authFingerprint

        for (bootstrapUrl in bootstrapUrls) {
            var readyWithoutClientCount = 0
            withContext(Dispatchers.Main) {
                activeWebView.settings.userAgentString = auth.resolveBootstrapUserAgent()
                activeWebView.stopLoading()
                activeWebView.loadUrl(bootstrapUrl)
            }

            val resolvedAttempts = maxAttempts.coerceAtLeast(1)
            for (attempt in 0 until resolvedAttempts) {
                delay(PAGE_PREPARE_BACKOFF_MS)
                val snapshot = readPageSnapshot() ?: continue
                if (snapshot.hasYtcfg && snapshot.hasWebPoClient) {
                    preparedAtMs = System.currentTimeMillis()
                    preparedUrl = bootstrapUrl
                    return snapshot
                }
                val pageReady = snapshot.readyState.equals("interactive", ignoreCase = true) ||
                    snapshot.readyState.equals("complete", ignoreCase = true)
                if (pageReady && !snapshot.hasWebPoClient) {
                    readyWithoutClientCount += 1
                    if (readyWithoutClientCount >= PAGE_READY_UNAVAILABLE_THRESHOLD) {
                        logUnavailable(bootstrapUrl, snapshot)
                        break
                    }
                } else {
                    readyWithoutClientCount = 0
                }
                if (attempt == resolvedAttempts - 1) {
                    logUnavailable(bootstrapUrl, snapshot)
                }
            }
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
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkImage = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = BootstrapWebViewClient()
            addJavascriptInterface(WebPoResultBridge(), JS_BRIDGE_NAME)
        }.also { created ->
            backgroundWebViewGuard = installYouTubeBackgroundWebViewGuard(created, TAG)
            webView = created
        }
    }

    private suspend fun setWebViewActive(active: Boolean) = withContext(Dispatchers.Main) {
        val activeWebView = webView ?: return@withContext
        if (active) {
            activeWebView.onResume()
            activeWebView.resumeTimers()
        } else {
            activeWebView.onPause()
            activeWebView.pauseTimers()
        }
    }

    private fun destroyPreparedWebViewAsync() {
        val activeWebView = webView ?: return
        val activeGuard = backgroundWebViewGuard
        webView = null
        backgroundWebViewGuard = null
        activeWebView.post {
            runCatching {
                removeYouTubeBackgroundWebViewGuard(activeGuard)
                activeWebView.onPause()
                activeWebView.pauseTimers()
                activeWebView.stopLoading()
                activeWebView.removeJavascriptInterface(JS_BRIDGE_NAME)
                activeWebView.loadUrl("about:blank")
                activeWebView.destroy()
            }
        }
        synchronized(pendingBridgeResults) {
            pendingBridgeResults.values.forEach { deferred ->
                deferred.complete("")
            }
            pendingBridgeResults.clear()
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
            urls = YouTubeCookieSupport.webCookieReadUrls,
            skipExisting = true,
            includeConsentCookie = true
        )
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
        cookieManager.flush()
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
                    bindsGvsTokenToVideoId: !!bindsToVideoId
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
                bindsGvsTokenToVideoId = root.optBoolean("bindsGvsTokenToVideoId")
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
        return buildYouTubeWebPoAuthFingerprint(auth)
    }

    private fun logUnavailable(
        url: String,
        snapshot: WebPoPageSnapshot
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUnavailableLogAtMs < UNAVAILABLE_LOG_INTERVAL_MS) {
            return
        }
        lastUnavailableLogAtMs = now
        NPLogger.d(
            TAG,
            "WebPoClient unavailable on $url, ready=${snapshot.readyState}, ytcfg=${snapshot.hasYtcfg}"
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

    private fun isAllowedBootstrapUri(uri: Uri?): Boolean {
        val resolvedUri = uri ?: return false
        if (resolvedUri.toString() == "about:blank") {
            return true
        }
        if (!resolvedUri.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        return isTrustedYouTubeBootstrapHost(resolvedUri.host)
    }

    private inner class BootstrapWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val currentRequest = request ?: return false
            val uri = currentRequest.url
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedBootstrapUri(it) }) {
                NPLogger.w(TAG, "Blocked unexpected WebPo navigation: $uri")
                return true
            }
            return false
        }
    }

    private inner class WebPoResultBridge {
        @Suppress("unused")
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
