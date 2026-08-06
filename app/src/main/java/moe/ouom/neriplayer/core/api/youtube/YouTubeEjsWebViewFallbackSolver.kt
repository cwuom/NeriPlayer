package moe.ouom.neriplayer.core.api.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

private const val YOUTUBE_EJS_WEBVIEW_CACHE_DIRECTORY = "youtube/ejs_webview"
private const val YOUTUBE_EJS_WEBVIEW_PAGE_FILE_NAME = "session.html"
private const val YOUTUBE_EJS_WEBVIEW_PAGE_PATH = "/youtube-ejs/$YOUTUBE_EJS_WEBVIEW_PAGE_FILE_NAME"
private const val YOUTUBE_EJS_WEBVIEW_PAGE_URL =
    "https://${WebViewAssetLoader.DEFAULT_DOMAIN}$YOUTUBE_EJS_WEBVIEW_PAGE_PATH"
private const val YOUTUBE_EJS_WEBVIEW_INIT_RESULT_GLOBAL = "__neriPlayerEjsWebViewInitResult"
private const val YOUTUBE_EJS_WEBVIEW_PAGE_TIMEOUT_MS = 30_000L
private const val YOUTUBE_EJS_WEBVIEW_EVALUATION_TIMEOUT_MS = 8_000L

/**
 * The fallback runs the same EJS parser in a local-only WebView renderer when JavaScriptSandbox
 * is unavailable or has exhausted its process-wide heap. No account cookies, app bridge, file
 * URLs, or network requests are exposed to the player script.
 */
internal fun buildYouTubeEjsWebViewDocument(playerScript: String): String {
    val quotedPlayerScript = JSONObject.quote(playerScript).replace("</", "<\\/")
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <script>
        $YOUTUBE_EJS_LOCALE_COMPATIBILITY_PRELUDE
        </script>
        <script src="/assets/youtube/yt.solver.lib.min.js"></script>
        <script>
        Object.assign(globalThis, lib);
        </script>
        <script src="/assets/youtube/yt.solver.core.min.js"></script>
        <script>
        (() => {
          try {
            const _preprocessed = jsc({
              type: "player",
              player: $quotedPlayerScript,
              requests: [],
              output_preprocessed: true,
            });
            if (typeof _preprocessed.preprocessed_player !== "string") {
              throw new Error("missing preprocessed player");
            }
            const _functions = { n: null, sig: null };
            Function("_result", _preprocessed.preprocessed_player)(_functions);
            if (typeof _functions.n !== "function" && typeof _functions.sig !== "function") {
              throw new Error("missing player challenge functions");
            }
            globalThis.$YOUTUBE_EJS_SESSION_GLOBAL = _functions;
            globalThis.$YOUTUBE_EJS_WEBVIEW_INIT_RESULT_GLOBAL = JSON.stringify({
              type: "session-ready",
              hasN: typeof _functions.n === "function",
              hasSig: typeof _functions.sig === "function",
            });
          } catch (error) {
            globalThis.$YOUTUBE_EJS_WEBVIEW_INIT_RESULT_GLOBAL = JSON.stringify({
              type: "session-error",
              error: error instanceof Error ? String(error.stack || error.message) : String(error),
            });
          }
        })();
        </script>
        </head>
        <body></body>
        </html>
    """.trimIndent()
}

private fun buildYouTubeEjsWebViewSessionResultScript(): String {
    return """
        (() => {
          const _result = globalThis.$YOUTUBE_EJS_WEBVIEW_INIT_RESULT_GLOBAL;
          return typeof _result === "string" ? _result : "";
        })();
    """.trimIndent()
}

internal class YouTubeEjsWebViewFallbackSolver(context: Context) {
    private data class Session(
        val playerJsUrl: String,
        val webView: WebView,
        val rendererDead: AtomicBoolean
    )

    private val appContext = context.applicationContext
    private val sessionLock = Mutex()
    private val pageDirectory = File(appContext.cacheDir, YOUTUBE_EJS_WEBVIEW_CACHE_DIRECTORY)
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(
            "/youtube-ejs/",
            WebViewAssetLoader.InternalStoragePathHandler(appContext, pageDirectory)
        )
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
        .build()
    private var session: Session? = null

    suspend fun warm(playerJsUrl: String, playerScript: String) {
        sessionLock.withLock {
            obtainSession(playerJsUrl, playerScript)
        }
    }

    suspend fun discardSessionForPlayerJsUrl(playerJsUrl: String) {
        sessionLock.withLock {
            if (session?.playerJsUrl == playerJsUrl) {
                discardSession()
            }
        }
    }

    suspend fun solve(
        playerJsUrl: String,
        playerScript: String,
        encryptedSignature: String?,
        throttlingParameter: String?
    ): YouTubeJsChallengeSolveResult = sessionLock.withLock {
        if (encryptedSignature.isNullOrBlank() && throttlingParameter.isNullOrBlank()) {
            return@withLock YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution()
            )
        }
        try {
            val activeSession = obtainSession(playerJsUrl, playerScript)
            val responseJson = evaluateJavascript(
                activeSession,
                buildYouTubeEjsLoadedPlayerSolveScript(
                    encryptedSignature = encryptedSignature?.takeIf { it.isNotBlank() },
                    throttlingParameter = throttlingParameter?.takeIf { it.isNotBlank() }
                )
            )
            parseYouTubeJsChallengeSolveResponse(
                responseJson = responseJson,
                requestedSignature = encryptedSignature?.takeIf { it.isNotBlank() },
                requestedThrottling = throttlingParameter?.takeIf { it.isNotBlank() }
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            discardSession()
            YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                detail = "local WebView EJS fallback failed",
                cause = error
            )
        }
    }

    private suspend fun obtainSession(playerJsUrl: String, playerScript: String): Session {
        session?.takeIf { it.playerJsUrl == playerJsUrl && !it.rendererDead.get() }?.let {
            return it
        }
        discardSession()
        writeDocument(playerScript)
        val created = createSession(playerJsUrl)
        return try {
            val initializationResponse = evaluateJavascript(
                created,
                buildYouTubeEjsWebViewSessionResultScript()
            )
            if (!isYouTubeEjsPlayerSessionReady(initializationResponse)) {
                throw IOException("local WebView EJS player session did not initialize")
            }
            created.also { session = it }
        } catch (error: Throwable) {
            destroyWebView(created.webView)
            throw error
        }
    }

    private suspend fun writeDocument(playerScript: String) = withContext(Dispatchers.IO) {
        if (!pageDirectory.exists() && !pageDirectory.mkdirs()) {
            throw IOException("could not create local EJS WebView cache directory")
        }
        val pageFile = File(pageDirectory, YOUTUBE_EJS_WEBVIEW_PAGE_FILE_NAME)
        val temporaryFile = File(pageDirectory, "$YOUTUBE_EJS_WEBVIEW_PAGE_FILE_NAME.tmp")
        temporaryFile.writeText(buildYouTubeEjsWebViewDocument(playerScript))
        if (!temporaryFile.renameTo(pageFile)) {
            pageFile.writeText(temporaryFile.readText())
            temporaryFile.delete()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createSession(playerJsUrl: String): Session {
        val pageReady = CompletableDeferred<Unit>()
        val rendererDead = AtomicBoolean(false)
        val created = withContext(Dispatchers.Main) {
            WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.loadsImagesAutomatically = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse {
                        return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
                    }

                    @Deprecated("WebView calls this callback on API levels before 21")
                    @Suppress("DEPRECATION")
                    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse {
                        return assetLoader.shouldInterceptRequest(url.toUri()) ?:
                            blockedResponse()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        return request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == YOUTUBE_EJS_WEBVIEW_PAGE_URL) {
                            pageReady.complete(Unit)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            pageReady.completeExceptionally(
                                IOException("local EJS WebView page error: ${error.description}")
                            )
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        rendererDead.set(true)
                        pageReady.completeExceptionally(
                            IOException("local EJS WebView renderer exited")
                        )
                        return true
                    }
                }
                loadUrl(YOUTUBE_EJS_WEBVIEW_PAGE_URL)
            }
        }
        return try {
            withTimeout(YOUTUBE_EJS_WEBVIEW_PAGE_TIMEOUT_MS) { pageReady.await() }
            Session(playerJsUrl = playerJsUrl, webView = created, rendererDead = rendererDead)
        } catch (error: Throwable) {
            destroyWebView(created)
            throw error
        }
    }

    private suspend fun evaluateJavascript(session: Session, script: String): String {
        if (session.rendererDead.get()) {
            throw IOException("local EJS WebView renderer is unavailable")
        }
        val result = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            if (session.rendererDead.get()) {
                result.complete(null)
            } else {
                session.webView.evaluateJavascript(script) { raw ->
                    result.complete(decodeEvaluateJavascriptValue(raw))
                }
            }
        }
        return try {
            withTimeout(YOUTUBE_EJS_WEBVIEW_EVALUATION_TIMEOUT_MS) { result.await() }
                ?: throw IOException("local EJS WebView returned no result")
        } catch (error: TimeoutCancellationException) {
            throw IOException("local EJS WebView evaluation timed out", error)
        }
    }

    private suspend fun discardSession() {
        val stale = session ?: return
        session = null
        destroyWebView(stale.webView)
    }

    private suspend fun destroyWebView(webView: WebView) {
        withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun decodeEvaluateJavascriptValue(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }
}
