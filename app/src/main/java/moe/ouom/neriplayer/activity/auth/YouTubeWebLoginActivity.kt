package moe.ouom.neriplayer.activity.auth

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
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.web.shouldAutoCompleteYouTubeWebLogin
import moe.ouom.neriplayer.data.auth.youtube.applyYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.clearYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.collectObservedYouTubeAuthCookies
import moe.ouom.neriplayer.data.auth.youtube.collectYouTubeWebCookies
import moe.ouom.neriplayer.data.auth.youtube.hasMeaningfulYouTubeAuthChange
import moe.ouom.neriplayer.data.auth.youtube.mergeYouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.preserveMatchingYouTubeAuthCookies
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeBootstrapSessionState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeCookieSupport
import moe.ouom.neriplayer.data.auth.youtube.YouTubeWebLoginVerifier
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.auth.youtube.evaluateYouTubeAuthHealth
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_DEFAULT_WEB_USER_AGENT
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeLoginHost
import moe.ouom.neriplayer.data.platform.youtube.resolveYouTubeMobileWebLoginUserAgent
import moe.ouom.neriplayer.util.network.DynamicProxySelector
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.isAllowedMainFrameRequest
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

class YouTubeWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_map_json"
        const val RESULT_AUTH_JSON = "result_youtube_auth_json"

        private const val TARGET_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
        private val AUTH_HOSTS = setOf(
            "music.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "m.youtube.com"
        )
        private const val YOUTUBE_MUSIC_RETURN_URL = "https://music.youtube.com/"
        private const val MAX_OFF_SITE_LOGIN_RETURNS = 2
        private val WEB_STORAGE_ORIGINS = listOf(
            "https://accounts.google.com",
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://youtube.com",
            "https://m.youtube.com"
        )
    }

    private data class CapturedRequestHeaders(
        val cookieHeader: String = "",
        val authorization: String = "",
        val xGoogAuthUser: String = "",
        val origin: String = YOUTUBE_MUSIC_ORIGIN,
        val userAgent: String = ""
    )

    private data class ObservedPageSessionState(
        val readyState: String = "",
        val hasYtcfg: Boolean = false,
        val loggedIn: Boolean = false,
        val sessionIndex: String = "",
        val delegatedSessionId: String = "",
        val userSessionId: String = ""
    ) {
        fun hasLiveSessionSignal(): Boolean {
            return loggedIn ||
                delegatedSessionId.isNotBlank() ||
                userSessionId.isNotBlank()
        }
    }

    private lateinit var webView: WebView
    private var persistedAuthBaseline: YouTubeAuthBundle = YouTubeAuthBundle()
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false
    private val loginCompletionWatcher = WebLoginCompletionWatcher(::maybeReturnObservedAuth)
    private val loginVerificationClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(DynamicProxySelector)
            .build()
    }
    private val loginVerifier by lazy {
        YouTubeWebLoginVerifier(::executeLoginVerificationRequest)
    }

    @Volatile
    private var capturedHeaders: CapturedRequestHeaders? = null

    @Volatile
    private var observedPageSessionState: ObservedPageSessionState = ObservedPageSessionState()

    @Volatile
    private var webViewUserAgent: String = ""

    @Volatile
    private var loginVerificationInFlight: Boolean = false

    /** 校验期间的常驻提示, 避免误判点完成无响应 */
    private var verifyingSnack: Snackbar? = null
    private var offSiteLoginReturns = 0

    @Volatile
    private var lastRejectedVerificationKey: String = ""

    @Volatile
    private var lastRejectedVerificationAtMs: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("youtube")
        persistedAuthBaseline = YouTubeAuthRepository(applicationContext).getAuthOnce()

        val root = CoordinatorLayout(this).apply {
            fitsSystemWindows = false
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.cardBackgroundColor,
                    Color.WHITE
                )
            )
        }

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE
                )
            )
        }

        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.youtube_web_login)
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_netease_web_login)
            setOnMenuItemClickListener(::onToolbarMenu)
        }
        appBar.addView(toolbar)

        webView = WebView(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                // 用剥掉 WebView 标记的移动 Chrome UA 导航:桌面 UA 会被 Google 判定为不安全浏览器
                // 并硬封锁("此浏览器或应用可能不安全"),移动 UA 规避该硬封锁;去掉 "; wv" 与
                // "Version/4.0" 标记后更像真实移动浏览器,尽量规避跳转 Play/app
                userAgentString = resolveYouTubeMobileWebLoginUserAgent(
                    runCatching {
                        WebSettings.getDefaultUserAgent(this@YouTubeWebLoginActivity)
                    }.getOrNull()
                )
            }
            // 存入 bundle 的 UA 仍固定为桌面 Web UA(WEB_REMIX 播放经 resolveBootstrapUserAgent 也用它),
            // 登录导航用移动 UA, 播放请求用桌面 UA,二者解耦,登录 UA 不污染播放指纹语义
            webViewUserAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = InnerClient()
        }
        // WebView 的 JS 定时器是进程级的, 前台登录页先主动恢复一次更稳
        webView.resumeTimers()

        restorePersistedCookies()
        loginCompletionWatcher.start()

        root.addView(webView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            webView.updatePadding(bottom = nav.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (this@YouTubeWebLoginActivity::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        webView.loadUrl(TARGET_URL)
    }

    override fun onPause() {
        persistObservedAuthIfNeeded()
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
            webView.onResume()
        }
    }

    override fun onDestroy() {
        loginCompletionWatcher.stop()
        persistObservedAuthIfNeeded()
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        super.onDestroy()
    }

    private fun onToolbarMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_read_cookie -> {
                readAndReturnAuth()
                true
            }
            else -> false
        }
    }

    private fun readAndReturnAuth() {
        try {
            CookieManager.getInstance().flush()
            capturePageSessionState { pageSessionState ->
                attemptReturnObservedAuth(
                    bundle = buildObservedAuthBundle(savedAt = System.currentTimeMillis()),
                    pageSessionState = pageSessionState,
                    showFailureSnack = true
                )
            }
        } catch (error: Throwable) {
            Snackbar.make(
                webView,
                getString(
                    R.string.snackbar_read_failed,
                    error.message ?: error.javaClass.simpleName
                ),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun maybeReturnObservedAuth(): Boolean {
        if (hasReturned) {
            return true
        }
        CookieManager.getInstance().flush()
        attemptReturnObservedAuth(
            bundle = buildObservedAuthBundle(savedAt = System.currentTimeMillis()),
            pageSessionState = observedPageSessionState,
            showFailureSnack = false
        )
        return hasReturned
    }

    private fun restorePersistedCookies() {
        val savedBundle = persistedAuthBaseline
        val savedCookies = savedBundle.cookies.ifEmpty {
            YouTubeCookieSupport.parseCookieString(savedBundle.cookieHeader)
        }
        val cookieManager = CookieManager.getInstance()
        val existingCookies = collectYouTubeWebCookies(
            cookieManager = cookieManager,
            urls = YouTubeCookieSupport.webCookieReadUrls
        )
        val cookiesToReset = linkedMapOf<String, String>().apply {
            putAll(existingCookies)
            putAll(savedCookies)
        }
        val resetCookieKeys = YouTubeCookieSupport.collectWebLoginResetCookieKeys(
            cookiesToReset
        )
        if (resetCookieKeys.isNotEmpty()) {
            NPLogger.d(
                "NERI-YouTubeLogin",
                "Resetting Google and YouTube WebView cookies before sign-in: " +
                    resetCookieKeys.joinToString()
            )
            clearYouTubeWebCookies(
                cookieManager = cookieManager,
                cookieKeys = resetCookieKeys,
                urls = YouTubeCookieSupport.webCookieReadUrls
            )
        }
        resetPersistedWebStorage()

        // Google 登录页对历史 cookie 很敏感, 这里只保留最小 consent 基线
        applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = emptyMap(),
            urls = YouTubeCookieSupport.webCookieReadUrls,
            skipExisting = false,
            includeConsentCookie = true
        )
    }

    private fun resetPersistedWebStorage() {
        val webStorage = WebStorage.getInstance()
        WEB_STORAGE_ORIGINS.forEach(webStorage::deleteOrigin)
        if (this::webView.isInitialized) {
            webView.clearHistory()
        }
    }

    private fun buildObservedAuthBundle(
        savedAt: Long = System.currentTimeMillis()
    ): YouTubeAuthBundle {
        val snapshot = capturedHeaders
        val observedCookies = collectObservedYouTubeAuthCookies(
            snapshotCookies = collectYouTubeWebCookies(CookieManager.getInstance()),
            requestCookieHeader = snapshot?.cookieHeader.orEmpty()
        )
        val merged = mergeYouTubeAuthBundle(
            base = persistedAuthBaseline,
            observedCookies = observedCookies,
            observedCookiesAreSnapshot = true,
            authorization = snapshot?.authorization.orEmpty(),
            xGoogAuthUser = snapshot?.xGoogAuthUser.orEmpty()
                .ifBlank { observedPageSessionState.sessionIndex },
            origin = snapshot?.origin.orEmpty().ifBlank { YOUTUBE_MUSIC_ORIGIN },
            userAgent = snapshot?.userAgent.orEmpty().ifBlank { webViewUserAgent },
            savedAt = savedAt
        )
        return preserveMatchingYouTubeAuthCookies(
            previous = persistedAuthBaseline,
            current = merged
        )
    }

    private fun persistObservedAuthIfNeeded() {
        if (!this::webView.isInitialized) {
            return
        }
        if (!observedPageSessionState.hasLiveSessionSignal()) {
            return
        }
        val bundle = buildObservedAuthBundle()
        if (!shouldAutoCompleteYouTubeWebLogin(
                currentAuth = bundle,
                pageConfirmedSession = observedPageSessionState.hasLiveSessionSignal()
            )
        ) {
            return
        }
        persistBundleIfChanged(bundle)
    }

    private fun persistBundleIfChanged(bundle: YouTubeAuthBundle) {
        val existing = persistedAuthBaseline
        if (hasMeaningfulYouTubeAuthChange(existing, bundle)) {
            persistedAuthBaseline = bundle
        }
    }

    private fun attemptReturnObservedAuth(
        bundle: YouTubeAuthBundle,
        pageSessionState: ObservedPageSessionState,
        showFailureSnack: Boolean
    ) {
        if (hasReturned) {
            return
        }
        if (loginVerificationInFlight) {
            // 静默吞掉重复点击会表现为按钮无响应
            if (showFailureSnack) {
                showVerifyingSnack()
            }
            return
        }

        val health = evaluateYouTubeAuthHealth(bundle)
        if (health.state == YouTubeAuthState.Missing || health.activeCookieKeys.isEmpty()) {
            if (showFailureSnack) {
                showCookieMissingSnack()
            }
            return
        }

        val verificationKey = buildVerificationKey(bundle, pageSessionState)
        val now = System.currentTimeMillis()
        if (
            !showFailureSnack &&
            verificationKey == lastRejectedVerificationKey &&
            now - lastRejectedVerificationAtMs < 3_000L
        ) {
            return
        }

        loginVerificationInFlight = true
        if (showFailureSnack) {
            showVerifyingSnack()
        }
        lifecycleScope.launch {
            val verification = runCatching {
                withContext(Dispatchers.IO) {
                    loginVerifier.verifyBlocking(bundle)
                }
            }
            loginVerificationInFlight = false
            dismissVerifyingSnack()
            if (hasReturned) {
                return@launch
            }

            val pageConfirmedSession = pageSessionState.hasLiveSessionSignal()
            val verifiedSessionState = verification.getOrNull()
            val verifiedLoggedIn = verifiedSessionState?.hasLiveSessionSignal() == true
            if (!pageConfirmedSession && !verifiedLoggedIn) {
                lastRejectedVerificationKey = verificationKey
                lastRejectedVerificationAtMs = System.currentTimeMillis()
                NPLogger.w(
                    "NERI-YouTubeLogin",
                    "Reject login completion pageConfirmed=$pageConfirmedSession verifiedLoggedIn=$verifiedLoggedIn authUser=${bundle.xGoogAuthUser}"
                )
                if (showFailureSnack) {
                    showCookieMissingSnack()
                }
                return@launch
            }

            val completedBundle = bundle.copy(
                xGoogAuthUser = bundle.xGoogAuthUser.ifBlank {
                    verifiedSessionState?.sessionIndex.orEmpty()
                }
            ).normalized(savedAt = bundle.savedAt)
            completeObservedAuth(
                bundle = completedBundle,
                verificationState = verifiedSessionState,
                pageConfirmedSession = pageConfirmedSession
            )
        }
    }

    private fun completeObservedAuth(
        bundle: YouTubeAuthBundle,
        verificationState: YouTubeBootstrapSessionState?,
        pageConfirmedSession: Boolean
    ) {
        if (hasReturned) {
            return
        }

        lastRejectedVerificationKey = ""
        lastRejectedVerificationAtMs = 0L
        persistBundleIfChanged(bundle)
        val cookieJson = JSONObject(bundle.cookies as Map<*, *>).toString()
        hasReturned = true
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_AUTH_JSON, bundle.toJson())
                .putExtra(RESULT_COOKIE, cookieJson)
        )
        NPLogger.d(
            "NERI-YouTubeLogin",
            "Login OK, pageConfirmed=$pageConfirmedSession verifiedLoggedIn=${verificationState?.loggedIn == true} cookie keys=${bundle.cookies.keys} authUser=${bundle.xGoogAuthUser}"
        )
        finish()
    }

    private fun showCookieMissingSnack() {
        dismissVerifyingSnack()
        Snackbar.make(
            webView,
            getString(R.string.settings_youtube_auth_missing),
            Snackbar.LENGTH_LONG
        ).show()
    }

    /** 校验走网络可能耗时数秒, 期间没有反馈用户会误判为无响应并中途退出 */
    private fun showVerifyingSnack() {
        if (verifyingSnack?.isShown == true) {
            return
        }
        verifyingSnack = Snackbar.make(
            webView,
            getString(R.string.settings_youtube_auth_verifying),
            Snackbar.LENGTH_INDEFINITE
        ).also { it.show() }
    }

    private fun dismissVerifyingSnack() {
        verifyingSnack?.dismiss()
        verifyingSnack = null
    }

    private fun executeLoginVerificationRequest(request: Request): String {
        loginVerificationClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("YouTube verification failed: ${response.code}")
            }
            return body
        }
    }

    private fun buildVerificationKey(
        bundle: YouTubeAuthBundle,
        pageSessionState: ObservedPageSessionState
    ): String {
        val normalizedBundle = bundle.normalized(savedAt = 0L)
        return buildString {
            append(normalizedBundle.cookieHeader)
            append('|')
            append(normalizedBundle.authorization)
            append('|')
            append(normalizedBundle.xGoogAuthUser)
            append('|')
            append(pageSessionState.loggedIn)
            append('|')
            append(pageSessionState.sessionIndex)
            append('|')
            append(pageSessionState.delegatedSessionId)
            append('|')
            append(pageSessionState.userSessionId)
        }
    }

    private fun captureAuthHeaders(request: WebResourceRequest?): Boolean {
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
            .ifBlank { webViewUserAgent }

        val path = currentRequest.url.encodedPath.orEmpty()
        val hasUsefulCookie = YouTubeCookieSupport.hasUsefulRequestCookies(cookieHeader)
        val isYouTubeiRequest = path.startsWith("/youtubei/v1/")
        if (!isYouTubeiRequest && authorization.isBlank() && !hasUsefulCookie) {
            return false
        }

        capturedHeaders = CapturedRequestHeaders(
            cookieHeader = cookieHeader,
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

    private fun capturePageSessionState(
        onCaptured: ((ObservedPageSessionState) -> Unit)? = null
    ) {
        if (!this::webView.isInitialized) {
            onCaptured?.invoke(observedPageSessionState)
            return
        }
        webView.evaluateJavascript(
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
                  return JSON.stringify({
                    readyState: document.readyState || '',
                    hasYtcfg: !!ytcfg,
                    loggedIn: !!getConfig('LOGGED_IN'),
                    sessionIndex: String(getConfig('SESSION_INDEX') || ''),
                    delegatedSessionId: String(getConfig('DELEGATED_SESSION_ID') || ''),
                    userSessionId: String(getConfig('USER_SESSION_ID') || '')
                  });
                })()
            """.trimIndent()
        ) { raw ->
            val state = parseObservedPageSessionState(raw) ?: ObservedPageSessionState()
            val previousHadLiveSession = observedPageSessionState.hasLiveSessionSignal()
            observedPageSessionState = state
            if (!previousHadLiveSession && state.hasLiveSessionSignal()) {
                NPLogger.d(
                    "NERI-YouTubeLogin",
                    "Confirmed live page session loggedIn=${state.loggedIn} hasYtcfg=${state.hasYtcfg}"
                )
                persistObservedAuthIfNeeded()
                loginCompletionWatcher.scheduleCheck(delayMs = 0L)
            }
            onCaptured?.invoke(state)
        }
    }

    private fun parseObservedPageSessionState(raw: String?): ObservedPageSessionState? {
        val decoded = decodeEvaluateJavascriptValue(raw) ?: return null
        return runCatching {
            val root = JSONObject(decoded)
            ObservedPageSessionState(
                readyState = root.optString("readyState"),
                hasYtcfg = root.optBoolean("hasYtcfg"),
                loggedIn = root.optBoolean("loggedIn"),
                sessionIndex = root.optString("sessionIndex"),
                delegatedSessionId = root.optString("delegatedSessionId"),
                userSessionId = root.optString("userSessionId")
            )
        }.getOrNull()
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

    private inner class InnerClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val currentRequest = request ?: return false
            val uri = currentRequest.url
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedLoginUri(it) }) {
                NPLogger.w("NERI-YouTubeLogin", "Blocked unexpected navigation: $uri")
                return true
            }
            if (currentRequest.isForMainFrame) {
                capturedHeaders = null
                observedPageSessionState = ObservedPageSessionState()
                loginCompletionWatcher.scheduleCheck()
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val captured = captureAuthHeaders(request)
            view?.post {
                if (captured) {
                    persistObservedAuthIfNeeded()
                    capturePageSessionState()
                }
                loginCompletionWatcher.scheduleCheck()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            capturedHeaders = null
            observedPageSessionState = ObservedPageSessionState()
            super.onPageStarted(view, url, favicon)
            loginCompletionWatcher.scheduleCheck()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            CookieManager.getInstance().flush()
            capturePageSessionState()
            persistObservedAuthIfNeeded()
            if (shouldReturnToYouTubeAfterLogin(url)) {
                NPLogger.d(
                    "YouTubeWebLogin",
                    "login landed off-site, returning to YouTube Music to harvest cookies"
                )
                view?.loadUrl(YOUTUBE_MUSIC_RETURN_URL)
                return
            }
            loginCompletionWatcher.scheduleCheck()
            super.onPageFinished(view, url)
        }
    }

    /**
     * 登录链路有时会停在 Play 商店的 YouTube Music 页而不是 music.youtube.com
     * 此时账号其实已经登上, 但收割只认 YouTube 域, 界面就会一直卡在等 cookie
     */
    private fun shouldReturnToYouTubeAfterLogin(url: String?): Boolean {
        if (offSiteLoginReturns >= MAX_OFF_SITE_LOGIN_RETURNS) {
            return false
        }
        val host = runCatching { url.orEmpty().toUri().host }
            .getOrNull()
            ?.lowercase()
            .orEmpty()
        if (host.isEmpty() || host in AUTH_HOSTS || host.endsWith("google.com")) {
            return false
        }
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(YOUTUBE_MUSIC_RETURN_URL)
        }.getOrNull().orEmpty()
        if (!YouTubeCookieSupport.isLoggedIn(parseYouTubeCookieHeader(cookieHeader))) {
            return false
        }
        offSiteLoginReturns++
        return true
    }

    private fun parseYouTubeCookieHeader(raw: String): Map<String, String> {
        return raw.split(';')
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                val index = trimmed.indexOf('=')
                if (index <= 0) return@mapNotNull null
                trimmed.substring(0, index).trim() to trimmed.substring(index + 1).trim()
            }
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .toMap()
    }

    private fun isAllowedLoginUri(uri: Uri?): Boolean {
        val resolvedUri = uri ?: return false
        if (resolvedUri.toString() == "about:blank") {
            return true
        }
        if (!resolvedUri.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        return isTrustedYouTubeLoginHost(resolvedUri.host)
    }
}
