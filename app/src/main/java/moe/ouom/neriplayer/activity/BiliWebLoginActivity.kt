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
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
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
    private var hasReturned = false
    private val loginCompletionWatcher = WebLoginCompletionWatcher(::maybeReturnIfLoggedIn)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        forceFreshWebContext()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = InnerClient()
        }
        setContentView(webView)

        loginCompletionWatcher.start()
        webView.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        loginCompletionWatcher.stop()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun forceFreshWebContext() {
        val cm = CookieManager.getInstance()

        cm.removeAllCookies(null)
        cm.removeSessionCookies(null)
        val domains = listOf(
            ".bilibili.com", "bilibili.com", "www.bilibili.com", "m.bilibili.com"
        )
        val keys = listOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "sid"
        )
        domains.forEach { d ->
            keys.forEach { k ->
                cm.setCookie(
                    "https://$d",
                    "$k=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                )
            }
        }
        cm.flush()

        WebStorage.getInstance().deleteAllData()
        if (this::webView.isInitialized) {
            webView.clearCache(true)
            webView.clearHistory()
        }
    }

    private inner class InnerClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val currentRequest = request ?: return false
            val uri = currentRequest.url
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedLoginUri(it) }) {
                NPLogger.w("NERI-BiliLogin", "Blocked unexpected navigation: $uri")
                return true
            }
            if (currentRequest.isForMainFrame) {
                loginCompletionWatcher.scheduleCheck()
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
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
            val host = runCatching { url?.toUri()?.host }.getOrNull()
            if (hostMatchesAnyDomain(host, ALLOWED_LOGIN_DOMAINS)) {
                loginCompletionWatcher.scheduleCheck()
            }
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
                "NERI-BiliLogin",
                "Still waiting for stable login cookies, observed=${cookieMap.keys.intersect(IMPORTANT_COOKIE_KEYS.toSet())}"
            )
            return false
        }

        hasReturned = true
        val json = org.json.JSONObject().apply {
            cookieMap.forEach { (k, v) -> put(k, v) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d("NERI-BiliLogin", "Login OK, cookie keys=${cookieMap.keys}")
        finish()
        return true
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
