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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeEjsChallengeSolver
 * Updated: 2026/3/23
 */


import android.annotation.SuppressLint
import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.io.IOException
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class YouTubeJsChallengeSolution(
    val signature: String? = null,
    val throttlingParameter: String? = null
)

internal class YouTubeEjsChallengeSolver(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "YouTubeEjsSolver"
        private const val LIB_ASSET_PATH = "youtube/yt.solver.lib.min.js"
        private const val CORE_ASSET_PATH = "youtube/yt.solver.core.min.js"
        private const val SCRIPT_TIMEOUT_SECONDS = 45L
        private const val CACHE_CAPACITY = 32
    }

    private val appContext = context.applicationContext
    private val solverLock = Any()
    private val playerScriptCache = linkedMapOf<String, String>()
    private val signatureCache = linkedMapOf<String, String>()
    private val throttlingCache = linkedMapOf<String, String>()
    private val libScript by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.assets.open(LIB_ASSET_PATH).bufferedReader().use { it.readText() }
    }
    private val coreScript by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.assets.open(CORE_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    @SuppressLint("RequiresFeature")
    fun solve(
        playerJsUrl: String,
        encryptedSignature: String? = null,
        throttlingParameter: String? = null
    ): YouTubeJsChallengeSolution? {
        val resolvedPlayerJsUrl = playerJsUrl.trim()
        val requestedSignature = encryptedSignature?.takeIf { it.isNotBlank() }
        val requestedThrottling = throttlingParameter?.takeIf { it.isNotBlank() }
        if (resolvedPlayerJsUrl.isBlank()) {
            return null
        }
        if (requestedSignature == null && requestedThrottling == null) {
            return YouTubeJsChallengeSolution()
        }

        val signatureKey = requestedSignature?.let { cacheKey(resolvedPlayerJsUrl, it) }
        val throttlingKey = requestedThrottling?.let { cacheKey(resolvedPlayerJsUrl, it) }
        val cachedSignature = signatureKey?.let { getCached(signatureCache, it) }
        val cachedThrottling = throttlingKey?.let { getCached(throttlingCache, it) }
        if ((requestedSignature == null || cachedSignature != null) &&
            (requestedThrottling == null || cachedThrottling != null)
        ) {
            return YouTubeJsChallengeSolution(
                signature = cachedSignature,
                throttlingParameter = cachedThrottling
            )
        }

        val resolved = synchronized(solverLock) {
            val warmSignature = signatureKey?.let { getCached(signatureCache, it) }
            val warmThrottling = throttlingKey?.let { getCached(throttlingCache, it) }
            if ((requestedSignature == null || warmSignature != null) &&
                (requestedThrottling == null || warmThrottling != null)
            ) {
                return@synchronized YouTubeJsChallengeSolution(
                    signature = warmSignature,
                    throttlingParameter = warmThrottling
                )
            }

            if (!JavaScriptSandbox.isSupported()) {
                NPLogger.w(TAG, "JavaScriptSandbox is not supported on this device")
                return@synchronized null
            }

            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(appContext)
                .get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            try {
                if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN) ||
                    !sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)
                ) {
                    NPLogger.w(
                        TAG,
                        "JavaScriptSandbox missing required features: promise=${sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)}, arrayBuffer=${sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)}"
                    )
                    return@synchronized null
                }

                val isolate = sandbox.createIsolate()
                val supportsProvideNamedData = sandbox.isFeatureSupported(
                    JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
                )
                try {
                    val playerScript = getPlayerScript(resolvedPlayerJsUrl)
                    isolate.evaluateJavaScriptAsync(
                        buildString {
                            append(libScript)
                            append('\n')
                            append("Object.assign(globalThis, lib);\n")
                            append(coreScript)
                        }
                    ).get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                    val playerDataName = "player_js_${UUID.randomUUID().toString().replace("-", "")}"
                    if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)) {
                        NPLogger.w(TAG, "JavaScriptSandbox does not support provideNamedData")
                        return@synchronized null
                    }
                    isolate.provideNamedData(playerDataName, playerScript.toByteArray(Charsets.UTF_8))
                    val responseJson = isolate.evaluateJavaScriptAsync(
                        buildSolveScript(
                            playerDataName = playerDataName,
                            encryptedSignature = if (warmSignature == null) requestedSignature else null,
                            throttlingParameter = if (warmThrottling == null) requestedThrottling else null
                        )
                    ).get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                    val resolved = parseSolveResponse(
                        responseJson = responseJson,
                        requestedSignature = if (warmSignature == null) requestedSignature else null,
                        requestedThrottling = if (warmThrottling == null) requestedThrottling else null
                    ) ?: return@synchronized null

                    resolved.signature?.let { solved ->
                        signatureKey?.let { putCached(signatureCache, it, solved) }
                    }
                    resolved.throttlingParameter?.let { solved ->
                        throttlingKey?.let { putCached(throttlingCache, it, solved) }
                    }

                    return@synchronized YouTubeJsChallengeSolution(
                        signature = warmSignature ?: resolved.signature,
                        throttlingParameter = warmThrottling ?: resolved.throttlingParameter
                    )
                } finally {
                    closeQuietly(isolate)
                }
            } catch (error: Exception) {
                NPLogger.w(TAG, "Failed to solve YouTube JS challenge", error)
                return@synchronized null
            } finally {
                closeQuietly(sandbox)
            }
        }
        return resolved
    }

    private fun buildSolveScript(
        playerDataName: String,
        encryptedSignature: String?,
        throttlingParameter: String?
    ): String {
        val requests = JSONArray().apply {
            encryptedSignature?.let { challenge ->
                put(
                    JSONObject()
                        .put("type", "sig")
                        .put("challenges", JSONArray().put(challenge))
                )
            }
            throttlingParameter?.let { challenge ->
                put(
                    JSONObject()
                        .put("type", "n")
                        .put("challenges", JSONArray().put(challenge))
                )
            }
        }
        val input = JSONObject()
            .put("type", "player")
            .put("requests", requests)
            .put("output_preprocessed", false)

        return """
            const _input = $input;
            const _decodeUtf8FromBuffer = (buffer) => {
              const _bytes = new Uint8Array(buffer);
              if (typeof TextDecoder !== "undefined") {
                return new TextDecoder("utf-8").decode(_bytes);
              }
              let _result = "";
              for (let _index = 0; _index < _bytes.length;) {
                const _byte1 = _bytes[_index++];
                if (_byte1 < 0x80) {
                  _result += String.fromCharCode(_byte1);
                  continue;
                }
                if (_byte1 < 0xE0 && _index < _bytes.length) {
                  const _byte2 = _bytes[_index++];
                  _result += String.fromCharCode(((_byte1 & 0x1F) << 6) | (_byte2 & 0x3F));
                  continue;
                }
                if (_byte1 < 0xF0 && _index + 1 < _bytes.length) {
                  const _byte2 = _bytes[_index++];
                  const _byte3 = _bytes[_index++];
                  _result += String.fromCharCode(
                    ((_byte1 & 0x0F) << 12) |
                    ((_byte2 & 0x3F) << 6) |
                    (_byte3 & 0x3F)
                  );
                  continue;
                }
                if (_index + 2 < _bytes.length) {
                  const _byte2 = _bytes[_index++];
                  const _byte3 = _bytes[_index++];
                  const _byte4 = _bytes[_index++];
                  let _codePoint =
                    ((_byte1 & 0x07) << 18) |
                    ((_byte2 & 0x3F) << 12) |
                    ((_byte3 & 0x3F) << 6) |
                    (_byte4 & 0x3F);
                  _codePoint -= 0x10000;
                  _result += String.fromCharCode(
                    0xD800 + (_codePoint >> 10),
                    0xDC00 + (_codePoint & 0x3FF)
                  );
                  continue;
                }
                _result += String.fromCharCode(_byte1);
              }
              return _result;
            };
            android.consumeNamedDataAsArrayBuffer("$playerDataName").then((buffer) => {
              _input.player = _decodeUtf8FromBuffer(buffer);
              return JSON.stringify(jsc(_input));
            });
        """.trimIndent()
    }

    private fun parseSolveResponse(
        responseJson: String,
        requestedSignature: String?,
        requestedThrottling: String?
    ): YouTubeJsChallengeSolution? {
        if (responseJson.isBlank()) {
            return null
        }
        val root = JSONObject(responseJson)
        if (root.optString("type") != "result") {
            return null
        }

        var resolvedSignature: String? = null
        var resolvedThrottling: String? = null
        val responses = root.optJSONArray("responses") ?: JSONArray()
        for (index in 0 until responses.length()) {
            val response = responses.optJSONObject(index) ?: continue
            if (response.optString("type") != "result") {
                continue
            }
            val data = response.optJSONObject("data") ?: continue
            val keys = data.keys()
            while (keys.hasNext()) {
                val challenge = keys.next()
                val value = data.optString(challenge).takeIf { it.isNotBlank() } ?: continue
                when (challenge) {
                    requestedSignature -> resolvedSignature = value
                    requestedThrottling -> resolvedThrottling = value
                }
            }
        }
        if (requestedSignature != null && resolvedSignature == null) {
            return null
        }
        if (requestedThrottling != null && resolvedThrottling == null) {
            return null
        }
        return YouTubeJsChallengeSolution(
            signature = resolvedSignature,
            throttlingParameter = resolvedThrottling
        )
    }

    @Synchronized
    private fun getPlayerScript(playerJsUrl: String): String {
        playerScriptCache[playerJsUrl]?.let { cached ->
            playerScriptCache.remove(playerJsUrl)
            playerScriptCache[playerJsUrl] = cached
            return cached
        }
        val request = Request.Builder()
            .url(playerJsUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val script = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch player JS: ${response.code}")
            }
            response.body.string()
        }
        putCached(playerScriptCache, playerJsUrl, script)
        return script
    }

    @Synchronized
    private fun getCached(cache: LinkedHashMap<String, String>, key: String): String? {
        val value = cache.remove(key) ?: return null
        cache[key] = value
        return value
    }

    @Synchronized
    private fun putCached(cache: LinkedHashMap<String, String>, key: String, value: String) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > CACHE_CAPACITY) {
            val eldestKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(eldestKey)
        }
    }

    private fun cacheKey(playerJsUrl: String, challenge: String): String {
        return "$playerJsUrl::$challenge"
    }

    private fun closeQuietly(isolate: JavaScriptIsolate) {
        runCatching { isolate.close() }
    }

    private fun closeQuietly(sandbox: JavaScriptSandbox) {
        runCatching { sandbox.close() }
    }
}
