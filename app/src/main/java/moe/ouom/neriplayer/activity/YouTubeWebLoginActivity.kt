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
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import moe.ouom.neriplayer.data.YouTubeAuthBundle
import moe.ouom.neriplayer.data.YouTubeAuthRepository
import moe.ouom.neriplayer.data.YouTubeCookieSupport
import moe.ouom.neriplayer.data.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.parseCookieHeader
import moe.ouom.neriplayer.util.lockPortraitIfPhone
import org.json.JSONObject

@Suppress("unused")
class YouTubeWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_map_json"
        const val RESULT_AUTH_JSON = "result_youtube_auth_json"

        private const val TARGET_URL = "$YOUTUBE_MUSIC_ORIGIN/"
        private val AUTH_HOSTS = setOf(
            "music.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "m.youtube.com"
        )
    }

    private data class CapturedRequestHeaders(
        val authorization: String = "",
        val xGoogAuthUser: String = "",
        val origin: String = YOUTUBE_MUSIC_ORIGIN,
        val userAgent: String = ""
    )

    private lateinit var webView: WebView
    private val authRepo by lazy { YouTubeAuthRepository(this) }

    @Volatile
    private var capturedHeaders: CapturedRequestHeaders? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = InnerClient()
        }

        restorePersistedCookies()

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
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
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
            val cookieHeader = collectCookieHeader()
            val snapshot = capturedHeaders
            val bundle = YouTubeAuthBundle(
                cookieHeader = cookieHeader,
                cookies = parseCookieHeader(cookieHeader),
                authorization = snapshot?.authorization.orEmpty(),
                xGoogAuthUser = snapshot?.xGoogAuthUser.orEmpty(),
                origin = snapshot?.origin.orEmpty().ifBlank { YOUTUBE_MUSIC_ORIGIN },
                userAgent = snapshot?.userAgent.orEmpty()
                    .ifBlank { webView.settings.userAgentString.orEmpty() },
                savedAt = System.currentTimeMillis()
            ).normalized(savedAt = System.currentTimeMillis())

            if (!YouTubeCookieSupport.isLoggedIn(bundle.cookies)) {
                Snackbar.make(
                    webView,
                    getString(R.string.snackbar_cookie_empty),
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }

            val cookieJson = JSONObject(bundle.cookies as Map<*, *>).toString()
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(RESULT_AUTH_JSON, bundle.toJson())
                    .putExtra(RESULT_COOKIE, cookieJson)
            )
            finish()
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

    private fun restorePersistedCookies() {
        val savedBundle = authRepo.getAuthOnce()
        val savedCookies = savedBundle.cookies.ifEmpty {
            parseCookieHeader(savedBundle.cookieHeader)
        }
        if (savedCookies.isEmpty()) {
            return
        }

        val cookieManager = CookieManager.getInstance()
        YouTubeCookieSupport.webUrls.forEach { url ->
            savedCookies.forEach { (key, value) ->
                cookieManager.setCookie(
                    url,
                    "$key=$value; Path=/; Domain=.youtube.com; Secure"
                )
            }
        }
        cookieManager.flush()
    }

    private fun collectCookieHeader(): String {
        val cookieManager = CookieManager.getInstance()
        val mergedCookies = linkedMapOf<String, String>()
        listOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://youtube.com",
            "https://m.youtube.com"
        ).forEach { url ->
            parseCookieHeader(cookieManager.getCookie(url).orEmpty()).forEach { (key, value) ->
                mergedCookies[key] = value
            }
        }
        return mergedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun captureAuthHeaders(request: WebResourceRequest?) {
        val currentRequest = request ?: return
        val host = currentRequest.url.host?.lowercase() ?: return
        if (host !in AUTH_HOSTS) {
            return
        }

        val headers = currentRequest.requestHeaders
        val authorization = findHeader(headers, "Authorization")
        val cookieHeader = findHeader(headers, "Cookie")
        val xGoogAuthUser = findHeader(headers, "X-Goog-AuthUser")
        val origin = findHeader(headers, "X-Origin")
            .ifBlank { findHeader(headers, "Origin") }
            .ifBlank { YOUTUBE_MUSIC_ORIGIN }
        val userAgent = findHeader(headers, "User-Agent")
            .ifBlank { webView.settings.userAgentString.orEmpty() }

        val path = currentRequest.url.encodedPath.orEmpty()
        val hasUsefulCookie = cookieHeader.contains("SAPISID=") ||
            cookieHeader.contains("__Secure-1PAPISID=") ||
            cookieHeader.contains("__Secure-3PAPISID=") ||
            cookieHeader.contains("LOGIN_INFO=")
        val isYouTubeiRequest = path.startsWith("/youtubei/v1/")
        if (!isYouTubeiRequest && authorization.isBlank() && !hasUsefulCookie) {
            return
        }

        capturedHeaders = CapturedRequestHeaders(
            authorization = authorization,
            xGoogAuthUser = xGoogAuthUser,
            origin = origin,
            userAgent = userAgent
        )
    }

    private fun findHeader(headers: Map<String, String>, name: String): String {
        return headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    private inner class InnerClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            captureAuthHeaders(request)
            return super.shouldInterceptRequest(view, request)
        }
    }
}
