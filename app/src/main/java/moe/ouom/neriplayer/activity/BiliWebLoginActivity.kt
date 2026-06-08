package moe.ouom.neriplayer.activity

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
 * File: moe.ouom.neriplayer.activity/BiliWebLoginActivity
 * Created: 2025/8/13
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.web.shouldAutoCompleteBiliWebLogin
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.hostMatchesAnyDomain
import moe.ouom.neriplayer.util.isAllowedMainFrameRequest
import moe.ouom.neriplayer.util.lockPortraitIfPhone

/**
 * 用内置 WebView 登录哔哩哔哩
 * 登录后读取 Cookie 并返回 JSON(Map<String,String>)
 *
 * 通过 Intent Extra 约定返回：
 *   - RESULT_COOKIE: String(JSON of Map<String,String>)
 */
class BiliWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_json"
        private const val LOG_TAG = "NERI-BiliLogin"
        private const val LOGIN_URL = "https://passport.bilibili.com/login"

        private val ALLOWED_LOGIN_DOMAINS = setOf(
            "bilibili.com",
            "hdslb.com",
            "biliimg.com"
        )

        private val IMPORTANT_COOKIE_KEYS = listOf(
            "SESSDATA",
            "bili_jct",
            "DedeUserID",
            "DedeUserID__ckMd5",
            "buvid3",
            "sid"
        )
    }

    private lateinit var webView: WebView
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false
    private val loginCompletionWatcher = WebLoginCompletionWatcher(::maybeReturnIfLoggedIn)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        NPLogger.d(LOG_TAG, "Bilibili login activity created")
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("bilibili")
        AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
        forceFreshWebContext()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true

            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = InnerChromeClient()
            webViewClient = InnerClient()
        }
        setContentView(webView)

        loginCompletionWatcher.start()
        reloadLoginPage("create")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NPLogger.d(LOG_TAG, "Bilibili login activity received new intent")
        forceFreshWebContext()
        reloadLoginPage("newIntent")
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onDestroy() {
        loginCompletionWatcher.stop()
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        super.onDestroy()
    }

    private fun forceFreshWebContext() {
        NPLogger.d(LOG_TAG, "Clearing Bilibili WebView state")
        val cm = CookieManager.getInstance()
        val urls = listOf(
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://www.bilibili.com",
            "https://m.bilibili.com"
        )
        val keys = listOf(
            "SESSDATA",
            "bili_jct",
            "DedeUserID",
            "DedeUserID__ckMd5",
            "buvid3",
            "buvid4",
            "sid"
        )
        urls.forEach { url ->
            keys.forEach { k ->
                expireCookie(cm, url, k, domain = null)
                expireCookie(cm, url, k, domain = ".bilibili.com")
                expireCookie(cm, url, k, domain = "bilibili.com")
            }
        }
        cm.flush()

        listOf(
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://www.bilibili.com",
            "https://m.bilibili.com"
        ).forEach(WebStorage.getInstance()::deleteOrigin)
        if (this::webView.isInitialized) {
            webView.clearCache(true)
            webView.clearHistory()
        }
    }

    private fun expireCookie(
        cookieManager: CookieManager,
        url: String,
        key: String,
        domain: String?
    ) {
        val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
        cookieManager.setCookie(
            url,
            "$key=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0$domainPart; Path=/; Secure"
        )
    }

    private fun reloadLoginPage(reason: String) {
        if (hasReturned || !this::webView.isInitialized) {
            return
        }
        NPLogger.d(LOG_TAG, "Loading Bilibili login reason=$reason url=$LOGIN_URL")
        if (!webView.url.isNullOrBlank()) {
            webView.stopLoading()
        }
        webView.loadUrl(LOGIN_URL)
    }

    private inner class InnerClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val currentRequest = request ?: return false
            val uri = currentRequest.url
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedLoginUri(it) }) {
                NPLogger.w(LOG_TAG, "Blocked unexpected navigation: $uri")
                return true
            }
            if (currentRequest.isForMainFrame) {
                loginCompletionWatcher.scheduleCheck()
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            NPLogger.d(LOG_TAG, "Page started: $url")
            val host = runCatching { url?.toUri()?.host }.getOrNull()
            if (hostMatchesAnyDomain(host, ALLOWED_LOGIN_DOMAINS)) {
                loginCompletionWatcher.scheduleCheck()
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            view?.post { loginCompletionWatcher.scheduleCheck() }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            NPLogger.d(LOG_TAG, "Page finished: $url")
            val host = runCatching { url?.toUri()?.host }.getOrNull()
            if (hostMatchesAnyDomain(host, ALLOWED_LOGIN_DOMAINS)) {
                loginCompletionWatcher.scheduleCheck()
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (shouldLogWebRequest(request)) {
                NPLogger.w(
                    LOG_TAG,
                    "Web error main=${request?.isForMainFrame} " +
                        "code=${error?.errorCode} desc=${error?.description} url=${request?.url}"
                )
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (shouldLogWebRequest(request)) {
                NPLogger.w(
                    LOG_TAG,
                    "HTTP error main=${request?.isForMainFrame} " +
                        "status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} " +
                        "url=${request?.url}"
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            NPLogger.e(LOG_TAG, "SSL error: $error")
            handler?.cancel()
        }
    }

    private inner class InnerChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress == 100 || newProgress % 25 == 0) {
                NPLogger.d(LOG_TAG, "Progress=$newProgress url=${view?.url}")
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val message = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
            val logMessage = "Console ${message.messageLevel()} " +
                "${message.sourceId()}:${message.lineNumber()} ${message.message().compactForLog()}"

            when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> NPLogger.e(LOG_TAG, logMessage)
                ConsoleMessage.MessageLevel.WARNING -> NPLogger.w(LOG_TAG, logMessage)
                else -> NPLogger.d(LOG_TAG, logMessage)
            }
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private fun maybeReturnIfLoggedIn(): Boolean {
        if (hasReturned) {
            return true
        }
        CookieManager.getInstance().flush()
        val cookieMap = readCookieForDomains(
            listOf(
                ".bilibili.com",
                "bilibili.com",
                "www.bilibili.com",
                "m.bilibili.com"
            )
        )
        if (!shouldAutoCompleteBiliWebLogin(cookieMap)) {
            NPLogger.d(
                LOG_TAG,
                "Still waiting for stable login cookies, observed=${cookieMap.keys.intersect(IMPORTANT_COOKIE_KEYS.toSet())}"
            )
            return false
        }

        hasReturned = true
        val json = org.json.JSONObject().apply {
            cookieMap.forEach { (k, v) -> put(k, v) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(LOG_TAG, "Login OK, cookie keys=${cookieMap.keys}")
        finish()
        return true
    }

    private fun shouldLogWebRequest(request: WebResourceRequest?): Boolean {
        val currentRequest = request ?: return false
        if (currentRequest.isForMainFrame) {
            return true
        }
        return hostMatchesAnyDomain(currentRequest.url.host, ALLOWED_LOGIN_DOMAINS)
    }

    private fun String.compactForLog(maxLength: Int = 400): String {
        val compact = replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (compact.length <= maxLength) {
            return compact
        }
        return "${compact.take(maxLength)}..."
    }

    private fun isAllowedLoginUri(uri: Uri?): Boolean {
        val resolvedUri = uri ?: return false
        if (resolvedUri.toString() == "about:blank") {
            return true
        }
        if (!resolvedUri.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        return hostMatchesAnyDomain(resolvedUri.host, ALLOWED_LOGIN_DOMAINS)
    }

    private fun readCookieForDomains(domains: List<String>): Map<String, String> {
        val cm = CookieManager.getInstance()
        val result = linkedMapOf<String, String>()
        domains.forEach { d ->
            val raw = cm.getCookie("https://$d").orEmpty()
            if (raw.isBlank()) return@forEach
            raw.split(';')
                .map { it.trim() }
                .forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val k = pair.substring(0, eq)
                        val v = pair.substring(eq + 1)
                        result[k] = v
                    }
                }
        }
        return result
    }
}
