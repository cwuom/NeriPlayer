package moe.ouom.neriplayer.core.customsource

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
 * File: moe.ouom.neriplayer.core.customsource/LxScriptEngine
 * Created: 2026/7/26
 *
 * 基于系统 WebView 的洛雪音乐(LX Music)自定义源脚本运行引擎。
 *
 * 在隐藏 WebView 中构造一个 globalThis.lx 上下文,提供 LX 脚本所需的
 * EVENT_NAMES / on / send / request / utils / env / version 接口,
 * HTTP 请求通过 Java 桥接由 OkHttp 完成(绕开 WebView 的跨域限制)。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NERI-LxEngine"

/**
 * 单个脚本对应一个引擎实例。引擎持有一个 WebView,生命周期跟随脚本的启用状态。
 */
class LxScriptEngine(
    private val appContext: Context,
    private val script: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile private var webView: WebView? = null
    @Volatile private var initialized = false
    @Volatile private var initResult: InitResult? = null
    private val initLatch = CompletableDeferred<InitResult>()

    private val requestSeq = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val httpCalls = ConcurrentHashMap<String, Call>()

    data class InitResult(
        val ok: Boolean,
        val sources: Map<String, List<String>>,
        val error: String? = null
    )

    /** 在主线程创建 WebView 并注入脚本;挂起直到脚本触发 inited 或超时。 */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun start(timeoutMs: Long = 15_000): InitResult {
        initResult?.let { return it }
        mainHandler.post {
            try {
                val wv = WebView(appContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.addJavascriptInterface(Bridge(), "NeriBridge")
                webView = wv
                val html = buildBootstrapHtml(script)
                wv.loadDataWithBaseURL(
                    "https://neriplayer.local/",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (e: Throwable) {
                NPLogger.e(TAG, "WebView 创建失败", e)
                if (!initLatch.isCompleted) {
                    initLatch.complete(InitResult(false, emptyMap(), e.message))
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { initLatch.await() }
            ?: InitResult(false, emptyMap(), "脚本初始化超时")
        initialized = true
        initResult = result
        return result
    }

    /**
     * 请求某首歌的播放地址。
     * @param source LX 平台 key,例如 "wy"
     * @param quality LX 音质,例如 "320k" / "flac"
     * @param musicInfo LX musicInfo(至少含 songmid/id/name/singer)
     * @return 可播放 URL,失败返回 null
     */
    suspend fun getMusicUrl(
        source: String,
        quality: String,
        musicInfo: JSONObject,
        timeoutMs: Long = 20_000
    ): String? {
        if (!initialized) start()
        if (initResult?.ok == false) return null

        val callId = "req_${requestSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<String>()
        pendingRequests[callId] = deferred

        val payload = JSONObject().apply {
            put("callId", callId)
            put("source", source)
            put("action", "musicUrl")
            put("info", JSONObject().apply {
                put("type", quality)
                put("musicInfo", musicInfo)
            })
        }
        val js = "window.__neri_invoke(${JSONObject.quote(payload.toString())});"
        runJs(js)

        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingRequests.remove(callId)
        if (raw == null) {
            NPLogger.w(TAG, "musicUrl 超时: source=$source quality=$quality")
            return null
        }
        return try {
            val obj = JSONObject(raw)
            if (obj.optBoolean("ok", false)) {
                obj.optString("url").takeIf { it.isNotBlank() }
            } else {
                NPLogger.w(TAG, "musicUrl 脚本返回失败: ${obj.optString("error")}")
                null
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "musicUrl 结果解析失败: $raw", e)
            null
        }
    }

    fun destroy() {
        mainHandler.post {
            httpCalls.values.forEach { runCatching { it.cancel() } }
            httpCalls.clear()
            pendingRequests.values.forEach { it.cancel() }
            pendingRequests.clear()
            webView?.let { wv ->
                runCatching {
                    wv.removeJavascriptInterface("NeriBridge")
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
            webView = null
        }
    }

    private fun runJs(js: String) {
        mainHandler.post {
            webView?.evaluateJavascript(js, null)
        }
    }

    /** Java <-> JS 桥。方法运行在 WebView 的 JS 线程,不可阻塞。 */
    private inner class Bridge {

        @JavascriptInterface
        fun onInited(sourcesJson: String) {
            NPLogger.d(TAG, "脚本 inited: $sourcesJson")
            val map = parseSources(sourcesJson)
            if (!initLatch.isCompleted) {
                initLatch.complete(InitResult(true, map))
            }
        }

        @JavascriptInterface
        fun onInitError(message: String) {
            NPLogger.w(TAG, "脚本 inited 失败: $message")
            if (!initLatch.isCompleted) {
                initLatch.complete(InitResult(false, emptyMap(), message))
            }
        }

        @JavascriptInterface
        fun onRequestResult(callId: String, resultJson: String) {
            pendingRequests[callId]?.complete(resultJson)
        }

        @JavascriptInterface
        fun log(message: String) {
            NPLogger.d(TAG, "[script] $message")
        }

        /** 由脚本发起 HTTP 请求;完成后回调 JS __neri_httpCallback。 */
        @JavascriptInterface
        fun httpRequest(requestId: String, url: String, optionsJson: String) {
            try {
                val options = JSONObject(optionsJson)
                val method = options.optString("method", "GET").uppercase()
                val builder = Request.Builder().url(url)

                options.optJSONObject("headers")?.let { headers ->
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        builder.header(k, headers.optString(k))
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    val bodyStr = options.optString("body", "")
                    val contentType = options.optJSONObject("headers")
                        ?.optString("Content-Type")
                        ?.takeIf { it.isNotBlank() }
                        ?: "application/x-www-form-urlencoded"
                    val body = bodyStr.toRequestBody(contentType.toMediaTypeOrNull())
                    builder.method(method, body)
                } else {
                    builder.method(method, null)
                }

                val call = http.newCall(builder.build())
                httpCalls[requestId] = call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        httpCalls.remove(requestId)
                        deliverHttp(requestId, error = e.message ?: "network error", resp = null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        httpCalls.remove(requestId)
                        response.use { r ->
                            val bodyStr = r.body?.string() ?: ""
                            val headersObj = JSONObject()
                            r.headers.forEach { pair ->
                                headersObj.put(pair.first.lowercase(), pair.second)
                            }
                            val respObj = JSONObject().apply {
                                put("statusCode", r.code)
                                put("headers", headersObj)
                                put("body", bodyStr)
                            }
                            deliverHttp(requestId, error = null, resp = respObj)
                        }
                    }
                })
            } catch (e: Exception) {
                deliverHttp(requestId, error = e.message ?: "request error", resp = null)
            }
        }

        @JavascriptInterface
        fun httpAbort(requestId: String) {
            httpCalls.remove(requestId)?.let { runCatching { it.cancel() } }
        }
    }

    private fun deliverHttp(requestId: String, error: String?, resp: JSONObject?) {
        val payload = JSONObject().apply {
            put("requestId", requestId)
            if (error != null) put("error", error) else put("error", JSONObject.NULL)
            put("response", resp ?: JSONObject.NULL)
        }
        runJs("window.__neri_httpCallback(${JSONObject.quote(payload.toString())});")
    }

    private fun parseSources(json: String): Map<String, List<String>> {
        return try {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, List<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val src = obj.optJSONObject(key) ?: continue
                val quals = src.optJSONArray("qualitys") ?: src.optJSONArray("qualities") ?: JSONArray()
                val list = ArrayList<String>(quals.length())
                for (i in 0 until quals.length()) list.add(quals.optString(i))
                out[key] = list
            }
            out
        } catch (e: Exception) {
            NPLogger.w(TAG, "解析 sources 失败", e)
            emptyMap()
        }
    }
}
