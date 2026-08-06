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
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.SandboxDeadException
import java.io.IOException
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class YouTubeJsChallengeSolution(
    val signature: String? = null,
    val throttlingParameter: String? = null
)

internal enum class YouTubeJsChallengeSolveStatus {
    SUCCESS,
    PLAYER_JS_URL_BLANK,
    JAVASCRIPT_SANDBOX_UNSUPPORTED,
    JAVASCRIPT_SANDBOX_TEMPORARILY_DISABLED,
    JAVASCRIPT_SANDBOX_CONNECTION_FAILED,
    JAVASCRIPT_SANDBOX_TIMEOUT,
    MISSING_SANDBOX_FEATURES,
    PLAYER_SCRIPT_FETCH_FAILED,
    SCRIPT_EVALUATION_FAILED,
    INVALID_RESPONSE,
    SIGNATURE_NOT_RESOLVED,
    THROTTLING_NOT_RESOLVED
}

internal data class YouTubeJsChallengeSolveResult(
    val status: YouTubeJsChallengeSolveStatus,
    val solution: YouTubeJsChallengeSolution = YouTubeJsChallengeSolution(),
    val detail: String? = null,
    val cause: Throwable? = null
) {
    val isSuccess: Boolean
        get() = status == YouTubeJsChallengeSolveStatus.SUCCESS

    fun summary(): String {
        return buildString {
            append(status.name)
            detail?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
            cause?.message?.takeIf { it.isNotBlank() }?.let { message ->
                if (detail.isNullOrBlank()) {
                    append(": ")
                } else {
                    append(" (")
                }
                append(message)
                if (!detail.isNullOrBlank()) {
                    append(')')
                }
            }
        }
    }
}

internal fun buildYouTubeEjsSandboxBootstrapScript(
    libScript: String,
    coreScript: String
): String {
    return buildString {
        append(YOUTUBE_EJS_LOCALE_COMPATIBILITY_PRELUDE)
        append('\n')
        append(libScript)
        append('\n')
        append("Object.assign(globalThis, lib);\n")
        append(coreScript)
    }
}

internal fun buildYouTubeEjsPlayerSessionInitializeScript(playerDataName: String): String {
    return """
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
          const _preprocessed = jsc({
            type: "player",
            player: _decodeUtf8FromBuffer(buffer),
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
          return JSON.stringify({
            type: "session-ready",
            hasN: typeof _functions.n === "function",
            hasSig: typeof _functions.sig === "function",
          });
        });
    """.trimIndent()
}

internal fun buildYouTubeEjsLoadedPlayerSolveScript(
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
    return """
        (() => {
          const _session = globalThis.$YOUTUBE_EJS_SESSION_GLOBAL;
          if (!_session) {
            throw new Error("player challenge session is not ready");
          }
          const _requests = $requests;
          const _responses = _requests.map((request) => {
            const _solver = _session[request.type];
            if (typeof _solver !== "function") {
              return {
                type: "error",
                error: "Failed to extract " + request.type + " function",
              };
            }
            try {
              const _data = Object.create(null);
              for (const _challenge of request.challenges) {
                _data[_challenge] = _solver(_challenge);
              }
              return { type: "result", data: _data };
            } catch (error) {
              return {
                type: "error",
                error: error instanceof Error ? String(error.stack || error.message) : String(error),
              };
            }
          });
          return JSON.stringify({ type: "result", responses: _responses });
        })();
    """.trimIndent()
}

// Let the WebView provider choose its device-specific bound instead of pinning every device to
// a guessed limit. A fixed 128 MiB cap can terminate the whole sandbox during player rotation.
internal const val YOUTUBE_EJS_ISOLATE_MAX_HEAP_SIZE_BYTES = 0L
// the on-disk store keeps the longer rollback history; memory only needs the active pair
internal const val YOUTUBE_EJS_PLAYER_SCRIPT_MEMORY_CACHE_CAPACITY = 2
internal const val YOUTUBE_EJS_SESSION_GLOBAL = "__neriPlayerEjsChallengeSession"
// start the local fallback when the complete Sandbox session misses the warmup budget
internal const val YOUTUBE_EJS_WEBVIEW_WARMUP_HEDGE_DELAY_MS = 500L

internal fun shouldStartYouTubeEjsWebViewWarmup(
    sandboxSessionReadyWithinGracePeriod: Boolean
): Boolean = !sandboxSessionReadyWithinGracePeriod

internal fun youtubeEjsIsolateMaxHeapSizeBytes(
    supportsExplicitHeapLimit: Boolean
): Long? {
    return YOUTUBE_EJS_ISOLATE_MAX_HEAP_SIZE_BYTES.takeIf { supportsExplicitHeapLimit }
}

internal fun shouldInvalidateYouTubeEjsSandbox(error: Throwable): Boolean {
    return when (error) {
        is MemoryLimitExceededException,
        is SandboxDeadException -> true
        else -> error.cause?.let(::shouldInvalidateYouTubeEjsSandbox) == true
    }
}

internal fun shouldDiscardYouTubeEjsPlayerSession(error: Throwable): Boolean {
    return shouldInvalidateYouTubeEjsSandbox(error) ||
        error is TimeoutException ||
        error.cause?.let(::shouldDiscardYouTubeEjsPlayerSession) == true
}

internal fun isYouTubeEjsMemoryLimitFailure(error: Throwable?): Boolean {
    return when (error) {
        null -> false
        is MemoryLimitExceededException -> true
        else -> isYouTubeEjsMemoryLimitFailure(error.cause)
    }
}

internal fun shouldRetryYouTubeEjsSandboxAfterMemoryFailure(
    result: YouTubeJsChallengeSolveResult
): Boolean {
    return result.status == YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED &&
        isYouTubeEjsMemoryLimitFailure(result.cause)
}

internal fun shouldUseYouTubeEjsWebViewFallback(
    result: YouTubeJsChallengeSolveResult
): Boolean {
    return when (result.status) {
        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_UNSUPPORTED,
        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TEMPORARILY_DISABLED,
        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_CONNECTION_FAILED,
        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT,
        YouTubeJsChallengeSolveStatus.MISSING_SANDBOX_FEATURES -> true
        YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED -> {
            result.cause?.let(::shouldInvalidateYouTubeEjsSandbox) == true
        }
        else -> false
    }
}

internal fun isYouTubeEjsPlayerSessionReady(responseJson: String): Boolean {
    val response = runCatching { JSONObject(responseJson) }.getOrNull() ?: return false
    return response.optString("type") == "session-ready" &&
        (response.optBoolean("hasN") || response.optBoolean("hasSig"))
}

internal fun cachedYouTubeEjsChallengeResult(
    requestedSignature: String?,
    requestedThrottling: String?,
    cachedSignature: String?,
    cachedThrottling: String?
): YouTubeJsChallengeSolveResult? {
    if ((requestedSignature != null && cachedSignature == null) ||
        (requestedThrottling != null && cachedThrottling == null)
    ) {
        return null
    }
    return YouTubeJsChallengeSolveResult(
        status = YouTubeJsChallengeSolveStatus.SUCCESS,
        solution = YouTubeJsChallengeSolution(
            signature = cachedSignature,
            throttlingParameter = cachedThrottling
        )
    )
}

internal class YouTubeEjsPlayerSessionCache<T>(
    private val closeSession: (T) -> Unit
) {
    private data class Session<T>(
        val playerJsUrl: String,
        val value: T
    )

    private val lock = Any()
    private var session: Session<T>? = null

    fun <R> withSession(
        playerJsUrl: String,
        createSession: () -> T,
        block: (T) -> R
    ): R {
        return synchronized(lock) {
            val activeSession = session?.takeIf { it.playerJsUrl == playerJsUrl }
            if (activeSession != null) {
                return@synchronized block(activeSession.value)
            }
            session?.let { stale ->
                runCatching { closeSession(stale.value) }
            }
            session = null
            val createdSession = Session(
                playerJsUrl = playerJsUrl,
                value = createSession()
            )
            session = createdSession
            block(createdSession.value)
        }
    }

    fun invalidate() {
        synchronized(lock) {
            session?.let { stale ->
                runCatching { closeSession(stale.value) }
            }
            session = null
        }
    }
}

internal val YOUTUBE_EJS_LOCALE_COMPATIBILITY_PRELUDE = """
    (() => {
      const patchLocaleStringIfBroken = (prototype, sample, options = {}) => {
        if (!prototype || sample === null || typeof sample === "undefined") {
          return;
        }
        try {
          sample.toLocaleString(undefined, options);
          return;
        } catch (_) {
          // Android JavaScriptSandbox can expose Intl without a usable ICU backend
        }
        const fallback = function() {
          return this.toString();
        };
        try {
          Object.defineProperty(prototype, "toLocaleString", {
            configurable: true,
            value: fallback,
            writable: true,
          });
        } catch (_) {
          try {
            prototype.toLocaleString = fallback;
          } catch (_) {
            // ignore runtimes that do not allow prototype replacement
          }
        }
      };
      patchLocaleStringIfBroken(Number.prototype, 0, { style: "percent" });
      patchLocaleStringIfBroken(String.prototype, "0");
      patchLocaleStringIfBroken(Date.prototype, new Date(0));
      patchLocaleStringIfBroken(Array.prototype, [0]);
      patchLocaleStringIfBroken(Boolean.prototype, false);
      if (typeof BigInt === "function") {
        patchLocaleStringIfBroken(BigInt.prototype, BigInt(0));
      }
      if (typeof Symbol === "function") {
        patchLocaleStringIfBroken(Symbol.prototype, Symbol("locale"));
      }
    })();
""".trimIndent()

internal class YouTubeEjsChallengeSolver(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "YouTubeEjsChallengeSolver"
        private const val LIB_ASSET_PATH = "youtube/yt.solver.lib.min.js"
        private const val CORE_ASSET_PATH = "youtube/yt.solver.core.min.js"
        // a provider that cannot bind is not a 45-second playback prerequisite
        private const val SANDBOX_CONNECTION_TIMEOUT_SECONDS = 3L
        private const val SCRIPT_TIMEOUT_SECONDS = 45L
        // 一条队列每首要缓存 sig 和 n 两条，容量按 32 算连一张歌单都装不下，
        // 旧条目被挤掉后同一首歌会反复重解
        private const val CHALLENGE_CACHE_CAPACITY = 512
        private const val SANDBOX_FAILURE_COOLDOWN_MS = 10L * 60L * 1000L

        // JavaScriptSandbox 每进程只能绑定一次，播放和下载需要共用连接
        private val sharedSandboxHolder = SharedJavaScriptSandboxHolder()
        // player.js 很大，只保留一份已编译会话，脚本更新时替换旧实例
        private val sharedPlayerSessionHolder = YouTubeEjsPlayerSessionCache<JavaScriptIsolate> {
            isolate -> runCatching { isolate.close() }
        }
        private val sharedSolverLock = YouTubeJsSolveQueue()

        private fun obtainSharedSandbox(context: Context): JavaScriptSandbox {
            return sharedSandboxHolder.obtain(context, SANDBOX_CONNECTION_TIMEOUT_SECONDS)
        }

        /** 沙箱失效时连同已加载的 player.js 一起丢弃，下次重新绑定 */
        private fun invalidateSharedSandbox() {
            sharedPlayerSessionHolder.invalidate()
            sharedSandboxHolder.invalidate()
        }
    }

    private val appContext = context.applicationContext
    private val solverLock = sharedSolverLock
    private val warmupLock = Mutex()
    private val playerScriptCacheLock = Any()
    private val challengeCacheLock = Any()
    private val playerScriptCache = linkedMapOf<String, String>()
    private val playerScriptStore = runCatching { YouTubePlayerScriptStore(appContext) }.getOrNull()
    private val webViewFallbackSolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        YouTubeEjsWebViewFallbackSolver(appContext)
    }
    private val signatureCache = linkedMapOf<String, String>()
    private val throttlingCache = linkedMapOf<String, String>()
    @Volatile
    private var webViewFallbackPlayerJsUrl: String? = null
    @Volatile
    private var sandboxDisabledUntilMs: Long = 0L
    @Volatile
    private var sandboxDisabledReason: String = ""
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
        return solveDetailed(
            playerJsUrl = playerJsUrl,
            encryptedSignature = encryptedSignature,
            throttlingParameter = throttlingParameter
        ).solution.takeIf { solution ->
            solution.signature != null || solution.throttlingParameter != null
        } ?: if (encryptedSignature.isNullOrBlank() && throttlingParameter.isNullOrBlank()) {
            YouTubeJsChallengeSolution()
        } else {
            null
        }
    }

    @SuppressLint("RequiresFeature")
    fun solveDetailed(
        playerJsUrl: String,
        encryptedSignature: String? = null,
        throttlingParameter: String? = null
    ): YouTubeJsChallengeSolveResult {
        val resolvedPlayerJsUrl = playerJsUrl.trim()
        val requestedSignature = encryptedSignature?.takeIf { it.isNotBlank() }
        val requestedThrottling = throttlingParameter?.takeIf { it.isNotBlank() }
        if (resolvedPlayerJsUrl.isBlank()) {
            return YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.PLAYER_JS_URL_BLANK,
                detail = "playerJsUrl is blank"
            )
        }
        if (requestedSignature == null && requestedThrottling == null) {
            return YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.SUCCESS,
                solution = YouTubeJsChallengeSolution()
            )
        }

        cachedChallengeResult(
            playerJsUrl = resolvedPlayerJsUrl,
            encryptedSignature = requestedSignature,
            throttlingParameter = requestedThrottling
        )?.let { return it }

        val signatureKey = requestedSignature?.let { cacheKey(resolvedPlayerJsUrl, it) }
        val throttlingKey = requestedThrottling?.let { cacheKey(resolvedPlayerJsUrl, it) }

        val resolved = solverLock.withNewestFirst {
            val warmSignature = signatureKey?.let { getCached(signatureCache, it) }
            val warmThrottling = throttlingKey?.let { getCached(throttlingCache, it) }
            if ((requestedSignature == null || warmSignature != null) &&
                (requestedThrottling == null || warmThrottling != null)
            ) {
                return@withNewestFirst YouTubeJsChallengeSolveResult(
                    status = YouTubeJsChallengeSolveStatus.SUCCESS,
                    solution = YouTubeJsChallengeSolution(
                        signature = warmSignature,
                        throttlingParameter = warmThrottling
                    )
                )
            }

            val nowMs = System.currentTimeMillis()
            if (nowMs < sandboxDisabledUntilMs) {
                return@withNewestFirst YouTubeJsChallengeSolveResult(
                    status = YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TEMPORARILY_DISABLED,
                    detail = sandboxDisabledReason.ifBlank {
                        "JavaScriptSandbox disabled for ${sandboxDisabledUntilMs - nowMs}ms"
                    }
                )
            }

            if (!JavaScriptSandbox.isSupported()) {
                return@withNewestFirst sandboxFailureResult(
                    status = YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_UNSUPPORTED,
                    detail = "JavaScriptSandbox is not supported on this device"
                )
            }

            val sandbox = runCatching {
                obtainSharedSandbox(appContext)
            }.getOrElse { error ->
                // 丢弃可能半初始化的实例
                invalidateSharedSandbox()
                return@withNewestFirst sandboxFailureResult(
                    status = if (error.isTimeoutFailure()) {
                        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT
                    } else {
                        YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_CONNECTION_FAILED
                    },
                    detail = "Failed to connect JavaScriptSandbox",
                    cause = error
                )
            }
            try {
                val hasPromiseSupport = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)
                val hasArrayBufferSupport = sandbox.isFeatureSupported(
                    JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
                )
                if (!hasPromiseSupport || !hasArrayBufferSupport) {
                    return@withNewestFirst sandboxFailureResult(
                        status = YouTubeJsChallengeSolveStatus.MISSING_SANDBOX_FEATURES,
                        detail = "promise=$hasPromiseSupport, arrayBuffer=$hasArrayBufferSupport"
                    )
                }

                val playerScript = runCatching {
                    getPlayerScript(resolvedPlayerJsUrl)
                }.getOrElse { error ->
                    propagateYouTubeJsChallengeCancellation(error)
                    return@withNewestFirst YouTubeJsChallengeSolveResult(
                        status = YouTubeJsChallengeSolveStatus.PLAYER_SCRIPT_FETCH_FAILED,
                        detail = "playerJsUrl=$resolvedPlayerJsUrl",
                        cause = error
                    )
                }
                val responseJson = runCatching {
                    withPreparedPlayerSession(
                        sandbox = sandbox,
                        playerJsUrl = resolvedPlayerJsUrl,
                        playerScript = playerScript
                    ) { isolate ->
                        isolate.evaluateJavaScriptAsync(
                            buildYouTubeEjsLoadedPlayerSolveScript(
                                encryptedSignature = if (warmSignature == null) requestedSignature else null,
                                throttlingParameter = if (warmThrottling == null) requestedThrottling else null
                            )
                        ).get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                }.getOrElse { error ->
                    propagateYouTubeJsChallengeCancellation(error)
                    if (shouldDiscardYouTubeEjsPlayerSession(error)) {
                        invalidateSharedSandbox()
                    }
                    return@withNewestFirst sandboxFailureResult(
                        status = if (error.isTimeoutFailure()) {
                            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT
                        } else {
                            YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED
                        },
                        detail = "playerJsUrl=$resolvedPlayerJsUrl",
                        cause = error
                    )
                }

                val parsedResult = parseYouTubeJsChallengeSolveResponse(
                    responseJson = responseJson,
                    requestedSignature = if (warmSignature == null) requestedSignature else null,
                    requestedThrottling = if (warmThrottling == null) requestedThrottling else null
                )
                if (!parsedResult.isSuccess) {
                    return@withNewestFirst parsedResult
                }

                parsedResult.solution.signature?.let { solved ->
                    signatureKey?.let { putCached(signatureCache, it, solved) }
                }
                parsedResult.solution.throttlingParameter?.let { solved ->
                    throttlingKey?.let { putCached(throttlingCache, it, solved) }
                }

                return@withNewestFirst YouTubeJsChallengeSolveResult(
                    status = YouTubeJsChallengeSolveStatus.SUCCESS,
                    solution = YouTubeJsChallengeSolution(
                        signature = warmSignature ?: parsedResult.solution.signature,
                        throttlingParameter = warmThrottling ?: parsedResult.solution.throttlingParameter
                    )
                )
            } catch (error: Throwable) {
                // isolate 抛出一般意味着共享沙箱已死，丢弃后下次重新绑定
                invalidateSharedSandbox()
                throw error
            }
        }
        return resolved
    }

    suspend fun solveDetailedAsync(
        playerJsUrl: String,
        encryptedSignature: String? = null,
        throttlingParameter: String? = null
    ): YouTubeJsChallengeSolveResult {
        val resolvedPlayerJsUrl = playerJsUrl.trim()
        if (resolvedPlayerJsUrl.isNotBlank()) {
            cachedChallengeResult(
                playerJsUrl = resolvedPlayerJsUrl,
                encryptedSignature = encryptedSignature,
                throttlingParameter = throttlingParameter
            )?.let { return it }
        }
        if (resolvedPlayerJsUrl.isNotBlank() &&
            webViewFallbackPlayerJsUrl == resolvedPlayerJsUrl
        ) {
            return solveWithWebViewFallback(
                playerJsUrl = resolvedPlayerJsUrl,
                encryptedSignature = encryptedSignature,
                throttlingParameter = throttlingParameter
            )
        }
        val firstAttempt = solverLock.withNewestFirstCancellable {
            solveDetailed(
                playerJsUrl = resolvedPlayerJsUrl,
                encryptedSignature = encryptedSignature,
                throttlingParameter = throttlingParameter
            )
        }
        val result = if (shouldRetryYouTubeEjsSandboxAfterMemoryFailure(firstAttempt)) {
            // AndroidX documents that a memory exception can belong to an earlier evaluation.
            // The failed sandbox has already been discarded, so allow one clean replacement.
            solverLock.withNewestFirstCancellable {
                solveDetailed(
                    playerJsUrl = resolvedPlayerJsUrl,
                    encryptedSignature = encryptedSignature,
                    throttlingParameter = throttlingParameter
                )
            }
        } else {
            firstAttempt
        }
        if (resolvedPlayerJsUrl.isBlank() ||
            !shouldUseYouTubeEjsWebViewFallback(result)
        ) {
            return result
        }
        return solveWithWebViewFallback(
            playerJsUrl = resolvedPlayerJsUrl,
            encryptedSignature = encryptedSignature,
            throttlingParameter = throttlingParameter
        )
    }

    private fun cachedChallengeResult(
        playerJsUrl: String,
        encryptedSignature: String?,
        throttlingParameter: String?
    ): YouTubeJsChallengeSolveResult? {
        val requestedSignature = encryptedSignature?.takeIf { it.isNotBlank() }
        val requestedThrottling = throttlingParameter?.takeIf { it.isNotBlank() }
        val signatureKey = requestedSignature?.let { cacheKey(playerJsUrl, it) }
        val throttlingKey = requestedThrottling?.let { cacheKey(playerJsUrl, it) }
        return cachedYouTubeEjsChallengeResult(
            requestedSignature = requestedSignature,
            requestedThrottling = requestedThrottling,
            cachedSignature = signatureKey?.let { getCached(signatureCache, it) },
            cachedThrottling = throttlingKey?.let { getCached(throttlingCache, it) }
        )
    }

    fun warmPlayerScript(playerJsUrl: String): Boolean {
        val resolvedPlayerJsUrl = playerJsUrl.trim()
        if (resolvedPlayerJsUrl.isBlank()) {
            return false
        }
        return runCatching {
            solverLock.withNewestFirst {
                warmPlayerSession(resolvedPlayerJsUrl)
            }
        }.getOrElse { error ->
            propagateYouTubeJsChallengeCancellation(error)
            false
        }
    }

    suspend fun warmPlayerScriptAsync(playerJsUrl: String): Boolean {
        val resolvedPlayerJsUrl = playerJsUrl.trim()
        if (resolvedPlayerJsUrl.isBlank()) {
            return false
        }
        return warmupLock.withLock {
            val startedAtMs = System.currentTimeMillis()
            if (webViewFallbackPlayerJsUrl == resolvedPlayerJsUrl) {
                val warmed = warmWebViewFallbackPlayerSession(resolvedPlayerJsUrl)
                if (warmed) {
                    NPLogger.d(
                        TAG,
                        "Warm EJS player session ready: engine=WEBVIEW_FALLBACK, " +
                            "reused=true, elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                } else {
                    webViewFallbackPlayerJsUrl = null
                }
                return@withLock warmed
            }

            coroutineScope {
                val fallbackWarmup = async(start = CoroutineStart.LAZY) {
                    warmWebViewFallbackPlayerSession(resolvedPlayerJsUrl)
                }
                val sandboxWarmup = async(Dispatchers.IO) {
                    runCatching {
                        solverLock.withNewestFirstCancellable {
                            warmPlayerSession(resolvedPlayerJsUrl)
                        }
                    }.getOrElse { error ->
                        propagateYouTubeJsChallengeCancellation(error)
                        false
                    }
                }
                val fallbackHedge = async {
                    val sandboxSessionReadyWithinGracePeriod = withTimeoutOrNull(
                        YOUTUBE_EJS_WEBVIEW_WARMUP_HEDGE_DELAY_MS
                    ) {
                        sandboxWarmup.await()
                    } == true
                    if (
                        shouldStartYouTubeEjsWebViewWarmup(
                            sandboxSessionReadyWithinGracePeriod
                        )
                    ) {
                        fallbackWarmup.start()
                        if (fallbackWarmup.await() && !sandboxWarmup.isCompleted) {
                            // a ready fallback must be visible before a stalled Sandbox gives up
                            webViewFallbackPlayerJsUrl = resolvedPlayerJsUrl
                            NPLogger.d(
                                TAG,
                                "Warm EJS player session ready: engine=WEBVIEW_FALLBACK, " +
                                    "sandboxStillWarming=true, " +
                                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                            )
                        }
                    }
                }
                val sandboxWarmed = sandboxWarmup.await()

                if (sandboxWarmed) {
                    val fallbackWon = webViewFallbackPlayerJsUrl == resolvedPlayerJsUrl
                    if (!fallbackWon) {
                        fallbackHedge.cancelAndJoin()
                        fallbackWarmup.cancelAndJoin()
                        webViewFallbackSolver.discardSessionForPlayerJsUrl(resolvedPlayerJsUrl)
                        NPLogger.d(
                            TAG,
                            "Warm EJS player session ready: engine=JAVASCRIPT_SANDBOX, " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                        )
                    }
                    return@coroutineScope true
                }

                fallbackWarmup.start()
                val webViewWarmed = fallbackWarmup.await()
                fallbackHedge.cancelAndJoin()
                if (webViewWarmed) {
                    webViewFallbackPlayerJsUrl = resolvedPlayerJsUrl
                    NPLogger.d(
                        TAG,
                        "Warm EJS player session ready: engine=WEBVIEW_FALLBACK, " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                } else {
                    NPLogger.w(
                        TAG,
                        "Warm EJS player session unavailable: sandboxReady=false, " +
                            "webViewReady=false, elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                }
                webViewWarmed
            }
        }
    }

    private fun warmPlayerSession(playerJsUrl: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (nowMs < sandboxDisabledUntilMs) {
            return false
        }
        if (!JavaScriptSandbox.isSupported()) {
            sandboxFailureResult(
                status = YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_UNSUPPORTED,
                detail = "JavaScriptSandbox is not supported on this device"
            )
            return false
        }
        val sandbox = runCatching {
            obtainSharedSandbox(appContext)
        }.getOrElse { error ->
            invalidateSharedSandbox()
            sandboxFailureResult(
                status = if (error.isTimeoutFailure()) {
                    YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT
                } else {
                    YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_CONNECTION_FAILED
                },
                detail = "Failed to connect JavaScriptSandbox",
                cause = error
            )
            return false
        }
        val hasPromiseSupport = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)
        val hasArrayBufferSupport = sandbox.isFeatureSupported(
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
        )
        if (!hasPromiseSupport || !hasArrayBufferSupport) {
            sandboxFailureResult(
                status = YouTubeJsChallengeSolveStatus.MISSING_SANDBOX_FEATURES,
                detail = "promise=$hasPromiseSupport, arrayBuffer=$hasArrayBufferSupport"
            )
            return false
        }
        val playerScript = runCatching {
            getPlayerScript(playerJsUrl)
        }.getOrElse { error ->
            propagateYouTubeJsChallengeCancellation(error)
            return false
        }
        return runCatching {
            withPreparedPlayerSession(
                sandbox = sandbox,
                playerJsUrl = playerJsUrl,
                playerScript = playerScript
            ) { }
            true
        }.getOrElse { error ->
            propagateYouTubeJsChallengeCancellation(error)
            if (shouldDiscardYouTubeEjsPlayerSession(error)) {
                invalidateSharedSandbox()
            }
            sandboxFailureResult(
                status = if (error.isTimeoutFailure()) {
                    YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT
                } else {
                    YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED
                },
                detail = "playerJsUrl=$playerJsUrl",
                cause = error
            )
            false
        }
    }

    private suspend fun warmWebViewFallbackPlayerSession(playerJsUrl: String): Boolean {
        val playerScript = try {
            withContext(Dispatchers.IO) { getPlayerScript(playerJsUrl) }
        } catch (error: Throwable) {
            propagateYouTubeJsChallengeCancellation(error)
            NPLogger.w(TAG, "Warm EJS WebView player script failed", error)
            return false
        }
        return try {
            webViewFallbackSolver.warm(
                playerJsUrl = playerJsUrl,
                playerScript = playerScript
            )
            true
        } catch (error: Throwable) {
            propagateYouTubeJsChallengeCancellation(error)
            NPLogger.w(TAG, "Warm EJS WebView session failed", error)
            false
        }
    }

    private fun <T> withPreparedPlayerSession(
        sandbox: JavaScriptSandbox,
        playerJsUrl: String,
        playerScript: String,
        block: (JavaScriptIsolate) -> T
    ): T {
        return sharedPlayerSessionHolder.withSession(
            playerJsUrl = playerJsUrl,
            createSession = {
                val isolate = createIsolate(sandbox)
                try {
                    isolate.evaluateJavaScriptAsync(
                        buildYouTubeEjsSandboxBootstrapScript(
                            libScript = libScript,
                            coreScript = coreScript
                        )
                    ).get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val playerDataName = "player_js_${UUID.randomUUID().toString().replace("-", "")}"
                    if (sandbox.isFeatureSupported(
                            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
                        )
                    ) {
                        isolate.provideNamedData(
                            playerDataName,
                            playerScript.toByteArray(Charsets.UTF_8)
                        )
                    } else {
                        throw UnsupportedOperationException(
                            "JavaScriptSandbox does not support named ArrayBuffers"
                        )
                    }
                    val initializationResponse = isolate.evaluateJavaScriptAsync(
                        buildYouTubeEjsPlayerSessionInitializeScript(playerDataName)
                    ).get(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!isYouTubeEjsPlayerSessionReady(initializationResponse)) {
                        throw IOException("EJS player session did not initialize")
                    }
                    isolate
                } catch (error: Throwable) {
                    closeQuietly(isolate)
                    throw error
                }
            },
            block = block
        )
    }

    private fun createIsolate(sandbox: JavaScriptSandbox): JavaScriptIsolate {
        if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            return sandbox.createIsolate()
        }
        val maxHeapSizeBytes = youtubeEjsIsolateMaxHeapSizeBytes(
            supportsExplicitHeapLimit = true
        ) ?: return sandbox.createIsolate()
        return sandbox.createIsolate(
            IsolateStartupParameters().apply {
                setMaxHeapSizeBytes(maxHeapSizeBytes)
            }
        )
    }

    private suspend fun solveWithWebViewFallback(
        playerJsUrl: String,
        encryptedSignature: String?,
        throttlingParameter: String?
    ): YouTubeJsChallengeSolveResult {
        val playerScript = try {
            withContext(Dispatchers.IO) { getPlayerScript(playerJsUrl) }
        } catch (error: Throwable) {
            propagateYouTubeJsChallengeCancellation(error)
            return YouTubeJsChallengeSolveResult(
                status = YouTubeJsChallengeSolveStatus.PLAYER_SCRIPT_FETCH_FAILED,
                detail = "playerJsUrl=$playerJsUrl",
                cause = error
            )
        }
        val result = webViewFallbackSolver.solve(
            playerJsUrl = playerJsUrl,
            playerScript = playerScript,
            encryptedSignature = encryptedSignature,
            throttlingParameter = throttlingParameter
        )
        if (result.isSuccess) {
            webViewFallbackPlayerJsUrl = playerJsUrl
            encryptedSignature?.takeIf { it.isNotBlank() }?.let { challenge ->
                result.solution.signature?.let { solved ->
                    putCached(signatureCache, cacheKey(playerJsUrl, challenge), solved)
                }
            }
            throttlingParameter?.takeIf { it.isNotBlank() }?.let { challenge ->
                result.solution.throttlingParameter?.let { solved ->
                    putCached(throttlingCache, cacheKey(playerJsUrl, challenge), solved)
                }
            }
        }
        return result
    }

    private fun getPlayerScript(playerJsUrl: String): String {
        synchronized(playerScriptCacheLock) {
            playerScriptCache[playerJsUrl]?.let { cached ->
                playerScriptCache.remove(playerJsUrl)
                playerScriptCache[playerJsUrl] = cached
                return cached
            }
        }
        // 存档读取与网络请求都放在锁外, 不阻塞 sig/n 的缓存命中
        playerScriptStore?.read(playerJsUrl)?.let { persisted ->
            synchronized(playerScriptCacheLock) {
                putPlayerScriptCacheLocked(playerJsUrl, persisted)
            }
            return persisted
        }
        val request = Request.Builder()
            .url(playerJsUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val script = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch player JS: ${response.code}")
            }
            response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
        synchronized(playerScriptCacheLock) {
            putPlayerScriptCacheLocked(playerJsUrl, script)
        }
        playerScriptStore?.write(playerJsUrl, script)
        return script
    }

    private fun getCached(cache: LinkedHashMap<String, String>, key: String): String? {
        synchronized(challengeCacheLock) {
            val value = cache.remove(key) ?: return null
            cache[key] = value
            return value
        }
    }

    private fun putCached(cache: LinkedHashMap<String, String>, key: String, value: String) {
        synchronized(challengeCacheLock) {
            cache.remove(key)
            cache[key] = value
            while (cache.size > CHALLENGE_CACHE_CAPACITY) {
                val eldestKey = cache.entries.firstOrNull()?.key ?: break
                cache.remove(eldestKey)
            }
        }
    }

    private fun putPlayerScriptCacheLocked(playerJsUrl: String, script: String) {
        playerScriptCache.remove(playerJsUrl)
        playerScriptCache[playerJsUrl] = script
        while (playerScriptCache.size > YOUTUBE_EJS_PLAYER_SCRIPT_MEMORY_CACHE_CAPACITY) {
            val eldestKey = playerScriptCache.entries.firstOrNull()?.key ?: break
            playerScriptCache.remove(eldestKey)
        }
    }

    private fun cacheKey(playerJsUrl: String, challenge: String): String {
        return "$playerJsUrl::$challenge"
    }

    private fun sandboxFailureResult(
        status: YouTubeJsChallengeSolveStatus,
        detail: String,
        cause: Throwable? = null
    ): YouTubeJsChallengeSolveResult {
        val result = YouTubeJsChallengeSolveResult(
            status = status,
            detail = detail,
            cause = cause
        )
        if (shouldTemporarilyDisableSandbox(result)) {
            sandboxDisabledReason = result.summary()
            sandboxDisabledUntilMs = System.currentTimeMillis() + SANDBOX_FAILURE_COOLDOWN_MS
        }
        return result
    }

    private fun shouldTemporarilyDisableSandbox(result: YouTubeJsChallengeSolveResult): Boolean {
        // 冷却期内 EJS 全线不可用，NewPipe 又常因 player.js 变更被跳过，
        // 两者同时失效则 n/sig 无任何求解路径
        if (result.cause?.isRecoverableSandboxFailure() == true) {
            return false
        }
        // MemoryLimitExceededException terminates the entire sandbox and can be reported by a
        // later innocent evaluation. The caller has a bounded fresh-sandbox retry, so do not
        // turn one stale termination into a ten-minute playback outage.
        if (result.cause?.let(::shouldInvalidateYouTubeEjsSandbox) == true) {
            return false
        }
        return when (result.status) {
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_UNSUPPORTED,
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_CONNECTION_FAILED,
            YouTubeJsChallengeSolveStatus.JAVASCRIPT_SANDBOX_TIMEOUT,
            YouTubeJsChallengeSolveStatus.MISSING_SANDBOX_FEATURES -> true
            YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED -> {
                result.cause?.containsSandboxRuntimeFailure() == true
            }
            else -> false
        }
    }

    /** Binding to already bound service 是并发撞车，与设备能力无关，冷却它会误伤整条链 */
    private fun Throwable.isRecoverableSandboxFailure(): Boolean {
        val message = message.orEmpty()
        if (
            message.contains("already bound", ignoreCase = true) ||
            message.contains("Binding to already", ignoreCase = true)
        ) {
            return true
        }
        return cause?.isRecoverableSandboxFailure() == true
    }

    private fun Throwable.isTimeoutFailure(): Boolean {
        return this is TimeoutException || cause?.isTimeoutFailure() == true
    }

    private fun Throwable.containsSandboxRuntimeFailure(): Boolean {
        if (shouldInvalidateYouTubeEjsSandbox(this)) {
            return true
        }
        val message = buildString {
            append(javaClass.name)
            append(' ')
            append(this@containsSandboxRuntimeFailure.message.orEmpty())
        }
        if (
            message.contains("VMBridge", ignoreCase = true) ||
            message.contains("NoClassDefFoundError", ignoreCase = true) ||
            message.contains("JavaScriptSandbox", ignoreCase = true)
        ) {
            return true
        }
        return cause?.containsSandboxRuntimeFailure() == true
    }

    private fun closeQuietly(isolate: JavaScriptIsolate) {
        runCatching { isolate.close() }
    }

    private class SharedJavaScriptSandboxHolder {
        private val lock = Any()

        @Volatile
        private var sandbox: JavaScriptSandbox? = null

        fun obtain(context: Context, timeoutSeconds: Long): JavaScriptSandbox {
            sandbox?.let { return it }
            val appContext = context.applicationContext
            return synchronized(lock) {
                sandbox ?: JavaScriptSandbox
                    .createConnectedInstanceAsync(appContext)
                    .let { pendingSandbox ->
                        try {
                            pendingSandbox.get(timeoutSeconds, TimeUnit.SECONDS)
                        } catch (error: Throwable) {
                            pendingSandbox.cancel(true)
                            throw error
                        }
                    }
                    .also { sandbox = it }
            }
        }

        fun invalidate() {
            synchronized(lock) {
                val stale = sandbox
                sandbox = null
                runCatching { stale?.close() }
            }
        }
    }
}

internal fun propagateYouTubeJsChallengeCancellation(error: Throwable) {
    when (error) {
        is CancellationException -> throw error
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}

internal fun parseYouTubeJsChallengeSolveResponse(
    responseJson: String,
    requestedSignature: String?,
    requestedThrottling: String?
): YouTubeJsChallengeSolveResult {
    if (responseJson.isBlank()) {
        return YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.INVALID_RESPONSE,
            detail = "responseJson is blank"
        )
    }
    val root = runCatching { JSONObject(responseJson) }.getOrElse { error ->
        return YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.INVALID_RESPONSE,
            detail = "responseJson is not valid JSON",
            cause = error
        )
    }
    if (root.optString("type") != "result") {
        return YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.INVALID_RESPONSE,
            detail = "root.type=${root.optString("type")}"
        )
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
        return YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SIGNATURE_NOT_RESOLVED,
            detail = "missing signature result for requested challenge"
        )
    }
    if (requestedThrottling != null && resolvedThrottling == null) {
        return YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.THROTTLING_NOT_RESOLVED,
            detail = "missing throttling result for requested challenge"
        )
    }
    return YouTubeJsChallengeSolveResult(
        status = YouTubeJsChallengeSolveStatus.SUCCESS,
        solution = YouTubeJsChallengeSolution(
            signature = resolvedSignature,
            throttlingParameter = resolvedThrottling
        )
    )
}
