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
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import moe.ouom.neriplayer.util.NPLogger
import androidx.core.net.toUri

/**
 * 用内置 WebView 登录哔哩哔哩。登录后读取 Cookie 并返回 JSON(Map<String,String>)。
 *
 * 通过 Intent Extra 约定返回：
 *   - RESULT_COOKIE: String(JSON of Map<String,String>)
 */
class BiliWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_json"

        private val LOGIN_DONE_HOSTS = setOf(
            "www.bilibili.com",
            "m.bilibili.com",
            "t.bilibili.com",
            "space.bilibili.com"
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        forceFreshWebContext()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = InnerClient()
        }
        setContentView(webView)

        webView.loadUrl("https://passport.bilibili.com/login")
    }

    override fun onDestroy() {
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
            val url = request?.url?.toString().orEmpty()
            view?.loadUrl(url)
            maybeReturnIfLoggedIn(request?.url?.host)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val host = runCatching { url?.toUri()?.host }.getOrNull()
            maybeReturnIfLoggedIn(host)
        }

        private fun maybeReturnIfLoggedIn(host: String?) {
            if (hasReturned) return
            if (host == null) return
            if (host.lowercase() in LOGIN_DONE_HOSTS) {
                val cookieMap = readCookieForDomains(
                    listOf(
                        ".bilibili.com",
                        "bilibili.com",
                        "www.bilibili.com",
                        "m.bilibili.com"
                    )
                )
                val ok = IMPORTANT_COOKIE_KEYS.any { k -> cookieMap[k].orEmpty().isNotBlank() }
                if (ok) {
                    hasReturned = true
                    val json = org.json.JSONObject().apply {
                        cookieMap.forEach { (k, v) -> put(k, v) }
                    }.toString()
                    setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
                    NPLogger.d("NERI-BiliLogin", "Login OK, cookie keys=${cookieMap.keys}")
                    finish()
                } else {
                    NPLogger.d("NERI-BiliLogin", "Still missing important cookies, keep browsing…")
                }
            }
        }
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