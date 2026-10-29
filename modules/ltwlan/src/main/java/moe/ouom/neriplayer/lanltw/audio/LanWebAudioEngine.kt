package moe.ouom.neriplayer.lanltw.audio

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanNtpEstimator
import moe.ouom.neriplayer.lanltw.protocol.LanCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** A fixed local page owns Web Audio. No remote page, executable content or arbitrary file path is exposed. */
class LanWebAudioEngine(
    context: Context, private val onState: (LanAudioState) -> Unit,
    private val onEnded: () -> Unit, private val onFailure: (String) -> Unit
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pageReady = CompletableDeferred<Unit>()
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonElement>>()
    private val recentDiagnostics = ArrayDeque<String>()
    fun diagnostics(): String = recentDiagnostics.joinToString(prefix = "[", postfix = "]")
    private var sequence = 0
    private var destroyed = false
    private var initialized = false
    private var mediaKey: String? = null
    @Volatile private var media: Pair<String, File>? = null
    var state = LanAudioState(); private set
    private val assetLoader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
    private val bridge = Bridge()
    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(app).apply {
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        addJavascriptInterface(bridge, "LanAudioHost")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.url.scheme != "https" || request.url.host != ORIGIN || request.method != "GET") return blocked()
                val current = media
                if (current != null && request.url.path == "/media/${current.first}") {
                    return runCatching { WebResourceResponse("application/octet-stream", null, current.second.inputStream()).apply {
                        responseHeaders = mapOf("Cache-Control" to "no-store", "Content-Length" to current.second.length().toString())
                    } }.getOrElse { blocked() }
                }
                if (request.url.path?.startsWith("/assets/lan-audio/") != true) return blocked()
                return assetLoader.shouldInterceptRequest(request.url) ?: blocked()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("AUDIO_ENGINE"); destroy(); return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("AUDIO_ENGINE")
            }
        }
        loadUrl("https://$ORIGIN/assets/lan-audio/index.html")
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    private inner class Bridge {
        @JavascriptInterface fun nowMs(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000.0
        @JavascriptInterface fun post(text: String) {
            if (text.length > 64 * 1024) return
            handler.post { if (!destroyed) handle(text) }
        }
    }
    private fun handle(text: String) {
        val event = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (event["event"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> pageReady.complete(Unit)
            "reply" -> {
                val id = event["id"]?.jsonPrimitive?.intOrNull ?: return
                val waiter = pending.remove(id) ?: return
                val error = event["error"]?.jsonPrimitive?.contentOrNull
                if (error == null) waiter.complete(event["result"] ?: JsonNull) else waiter.completeExceptionally(IllegalStateException(error))
            }
            "state" -> { state = LanAudioState.parse(event); onState(state) }
            "ended" -> onEnded()
            "resync" -> if (initialized) onFailure("AUDIO_SUSPENDED")
            "diagnostic" -> {
                val detail = event["detail"].toString()
                if (recentDiagnostics.size >= 128) recentDiagnostics.removeFirst()
                recentDiagnostics.addLast(detail)
                NPLogger.d("LAN-WebAudio", detail)
            }
        }
    }
    suspend fun resetClock() { invoke("resetNtp") }
    private suspend fun invoke(op: String, data: JsonObject = buildJsonObject {}): JsonElement {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!destroyed) { "AUDIO_ENGINE" }
        withTimeout(15_000) { pageReady.await() }
        check(pending.size < 96) { "AUDIO_ENGINE" }
        val id = ++sequence
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        val request = buildJsonObject { put("id", id); put("op", op); put("data", data) }
        webView.evaluateJavascript("NeriBeatSyncAudio.dispatch($request)", null)
        return try { withTimeout(if (op == "load") 90_000L else 10_000L) { result.await() } }
        finally { pending.remove(id) }
    }
    suspend fun initialize() {
        if (initialized) return
        state = LanAudioState.parse(invoke("initialize").jsonObject); initialized = true; onState(state)
    }
    suspend fun load(key: String, file: File, position: Double, expectedDurationMs: Long = 0): Boolean {
        initialize()
        if (mediaKey == key) return true
        check(file.length() in 1..moe.ouom.neriplayer.data.model.lan.LanLocalMedia.MAX_BYTES) { "MEMORY_LIMIT" }
        val token = UUID.randomUUID().toString(); media = token to file
        val result = invoke("load", buildJsonObject { put("key", key); put("url", "https://$ORIGIN/media/$token"); put("position", position); put("expectedDurationMs", expectedDurationMs) })
        mediaKey = result.jsonObject["key"]?.jsonPrimitive?.content
        return mediaKey == key
    }
    fun ready(key: String): Boolean = initialized && !destroyed && mediaKey == key && state.contextState == "running"
    suspend fun schedule(m: LanMessage, offset: Double, nudge: Int): Boolean {
        val action = m.scheduledAction ?: return false
        val result = invoke("schedule", buildJsonObject {
            put("type", action.type); put("key", action.audioSource); put("positionSeconds", action.trackTimeSeconds)
            put("serverTimeMs", m.serverTimeToExecute ?: return false); put("revision", m.revision ?: return false)
            put("offsetMs", offset); put("nudgeMs", nudge)
        }).jsonObject
        return result["accepted"]?.jsonPrimitive?.booleanOrNull == true
    }
    suspend fun receiveProbe(message: LanMessage, receivedAt: Double): LanNtpEstimator.Estimate? {
        val result = invoke("ntp", buildJsonObject { put("receivedAt", receivedAt); put("message", Json.parseToJsonElement(LanCodec.encode(message))) })
        if (result !is JsonObject) return null
        return LanNtpEstimator.Estimate(result.getValue("offset").jsonPrimitive.double, result.getValue("rtt").jsonPrimitive.double, result.getValue("synced").jsonPrimitive.boolean)
    }
    fun invalidateMedia() { mediaKey = null; media = null }
    fun pause() { fire("pause") }
    fun position(value: Double) { fire("position", buildJsonObject { put("positionSeconds", value) }) }
    fun nudge(value: Int, delta: Int) { fire("nudge", buildJsonObject { put("value", value); put("delta", delta) }) }
    private fun fire(op: String, data: JsonObject = buildJsonObject {}) {
        if (!initialized || destroyed) return
        webView.evaluateJavascript("NeriBeatSyncAudio.dispatch(${buildJsonObject { put("id", 0); put("op", op); put("data", data) }})", null)
    }
    private fun fail(code: String) {
        pageReady.completeExceptionally(IllegalStateException(code))
        pending.values.forEach { it.completeExceptionally(IllegalStateException(code)) }; pending.clear(); onFailure(code)
    }
    fun destroy() {
        pageReady.cancel()
        if (destroyed) return
        destroyed = true; media = null; mediaKey = null
        pending.values.forEach { it.cancel() }; pending.clear()
        webView.removeJavascriptInterface("LanAudioHost"); webView.stopLoading(); webView.destroy()
    }
    companion object { private const val ORIGIN = "appassets.androidplatform.net" }
}
