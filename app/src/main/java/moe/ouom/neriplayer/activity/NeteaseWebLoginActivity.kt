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
 * File: moe.ouom.neriplayer.activity/NeteaseWebLoginActivity
 * Created: 2025/8/12
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.web.normalizeNeteaseWebLoginCookies
import moe.ouom.neriplayer.data.auth.web.shouldAutoCompleteNeteaseWebLogin
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.hostMatchesAnyDomain
import moe.ouom.neriplayer.util.isAllowedMainFrameRequest
import moe.ouom.neriplayer.util.lockPortraitIfPhone

class NeteaseWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_map_json"
        private const val LOG_TAG = "NERI-NeteaseLogin"
        private const val TARGET_URL = "https://music.163.com/"
        private val ALLOWED_LOGIN_DOMAINS = setOf(
            "163.com",
            "126.net",
            "163yun.com"
        )
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var toolbar: MaterialToolbar
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false
    private var initialCookies: Map<String, String> = emptyMap()
    private val loginCompletionWatcher = WebLoginCompletionWatcher(::maybeReturnIfLoggedIn)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("netease")

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
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.netease_web_login)
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_netease_web_login)
            setOnMenuItemClickListener { onToolbarMenu(it) }
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
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = false
                allowContentAccess = false
                userAgentString = DESKTOP_UA
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = InnerChromeClient()
            webViewClient = InnerClient()
        }
        // WebView 的 JS 定时器是进程级的，前台登录页先主动恢复一次更稳
        webView.resumeTimers()

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
                    if (this@NeteaseWebLoginActivity::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        initialCookies = readCookieMap()
        loginCompletionWatcher.start()
        webView.loadUrl(TARGET_URL)
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
            webView.onResume()
        }
    }

    override fun onPause() {
        if (this::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
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

    private fun onToolbarMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_read_cookie -> {
                readAndReturnCookies()
                true
            }
            else -> false
        }
    }

    private fun readAndReturnCookies() {
        try {
            val map = readCookieMap()
            if (map.isEmpty()) {
                Snackbar.make(webView, getString(R.string.snackbar_cookie_empty), Snackbar.LENGTH_SHORT).show()
                return
            }

            val json = org.json.JSONObject(map as Map<*, *>).toString()
            setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
            finish()
        } catch (e: Throwable) {
            Snackbar.make(
                webView,
                getString(R.string.snackbar_read_failed, e.message ?: e.javaClass.simpleName),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun cookieStringToMap(raw: String): MutableMap<String, String> {
        val map = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { part ->
                val idx = part.indexOf('=')
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        return map
    }

    private fun readCookieMap(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val main = cm.getCookie("https://music.163.com").orEmpty()
        val api = cm.getCookie("https://interface.music.163.com").orEmpty()
        val api3 = cm.getCookie("https://interface3.music.163.com").orEmpty()
        val merged = listOf(main, api, api3).filter { it.isNotBlank() }.joinToString("; ")
        if (merged.isBlank()) {
            return emptyMap()
        }
        return normalizeNeteaseWebLoginCookies(cookieStringToMap(merged))
    }

    private inner class InnerClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val currentRequest = request ?: return false
            val uri = currentRequest.url
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedLoginUri(it) }) {
                NPLogger.w("NERI-NeteaseLogin", "Blocked unexpected navigation: $uri")
                return true
            }
            if (currentRequest.isForMainFrame) {
                loginCompletionWatcher.scheduleCheck()
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val host = runCatching { url?.let(Uri::parse)?.host }.getOrNull()
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
            val host = runCatching { url?.let(Uri::parse)?.host }.getOrNull()
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
    }

    private inner class InnerChromeClient : WebChromeClient() {

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
        val currentCookies = readCookieMap()
        if (!shouldAutoCompleteNeteaseWebLogin(initialCookies, currentCookies)) {
            NPLogger.d("NERI-NeteaseLogin", "Waiting for stable NetEase login cookies.")
            return false
        }

        hasReturned = true
        val json = org.json.JSONObject(currentCookies as Map<*, *>).toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d("NERI-NeteaseLogin", "Login OK, cookie keys=${currentCookies.keys}")
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
        val scheme = resolvedUri.scheme.orEmpty()
        if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true)) {
            return false
        }
        return hostMatchesAnyDomain(resolvedUri.host, ALLOWED_LOGIN_DOMAINS)
    }
}
