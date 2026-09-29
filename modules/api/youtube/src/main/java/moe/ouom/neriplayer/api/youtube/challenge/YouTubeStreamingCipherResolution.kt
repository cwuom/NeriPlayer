package moe.ouom.neriplayer.api.youtube.challenge

import androidx.annotation.VisibleForTesting
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.model.youtube.cache.NewPipeFallbackSnapshot
import moe.ouom.neriplayer.api.youtube.fallback.YouTubeNewPipeFallbackStore
import moe.ouom.neriplayer.api.youtube.fallback.retainRecentNewPipeFallbackKeys
import moe.ouom.neriplayer.core.logging.NPLogger
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager

private const val NEWPIPE_FALLBACK_START_DELAY_MS = 40L
private const val CIPHER_RESOLVE_TIMEOUT_MS = 12_000L
private const val STREAMING_CIPHER_LOG_THRESHOLD_MS = 250L

fun playbackElapsedMs(startedAtMs: Long): Long = System.currentTimeMillis() - startedAtMs

internal fun shouldLogStreamingCipherResolution(elapsedMs: Long, logged: AtomicBoolean): Boolean =
    elapsedMs >= STREAMING_CIPHER_LOG_THRESHOLD_MS || logged.compareAndSet(false, true)

object NewPipeFallbackTracker {
    /**
     * 一次失败就够
     *
     * NewPipe 的反混淆是拿固定正则去套 player.js, 同一版地址要么匹配要么不匹配, 没有偶发;
     * 等第二次样本等于让冷启动的头两首各白付三秒多
     */
    private const val FAILURE_THRESHOLD = 1
    private val signatureFailures = ConcurrentHashMap<String, AtomicInteger>()

    private val throttlingFailures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var store: YouTubeNewPipeFallbackStore? = null

    /** 存档只在进程内挂一次, 之后每次记录都同步写回 */
    fun attachStore(newStore: YouTubeNewPipeFallbackStore) {
        if (store != null) return
        synchronized(this) {
            attachStoreLocked(newStore)
        }
    }

    private fun attachStoreLocked(newStore: YouTubeNewPipeFallbackStore) {
        if (store != null) return
        store = newStore
        restoreSnapshot(newStore)
    }

    private fun restoreSnapshot(newStore: YouTubeNewPipeFallbackStore) {
        val snapshot = newStore.load() ?: return
        restoreFailures(snapshot.signature, signatureFailures)
        restoreFailures(snapshot.throttling, throttlingFailures)
    }

    private fun restoreFailures(keys: List<String>, failures: ConcurrentHashMap<String, AtomicInteger>) {
        keys.forEach { key ->
            failures.computeIfAbsent(key) { AtomicInteger() }.set(FAILURE_THRESHOLD)
        }
    }

    fun maybeSkipSignature(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        return (signatureFailures[key]?.get() ?: 0) >= FAILURE_THRESHOLD
    }

    fun maybeSkipThrottling(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        return (throttlingFailures[key]?.get() ?: 0) >= FAILURE_THRESHOLD
    }

    fun recordSignatureFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        signatureFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        persist(signatureKey = key, throttlingKey = null)
    }

    fun recordThrottlingFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        throttlingFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        persist(signatureKey = null, throttlingKey = key)
    }

    private fun persist(signatureKey: String?, throttlingKey: String?) {
        val target = store ?: return
        synchronized(this) {
            val snapshot = target.load() ?: NewPipeFallbackSnapshot()
            target.save(snapshot.copy(
                signature = appendFallbackKey(snapshot.signature, signatureKey),
                throttling = appendFallbackKey(snapshot.throttling, throttlingKey)
            ))
        }
    }

    private fun appendFallbackKey(existing: List<String>, key: String?): List<String> {
        if (key == null) return existing
        return retainRecentNewPipeFallbackKeys(existing, key)
    }

    fun reset() {
        signatureFailures.clear()
        throttlingFailures.clear()
    }
}

internal data class ChallengeCandidateResult<T>(
    val source: String,
    val value: T?,
    val elapsedMs: Long
)

private class ChallengeRaceOutcome<T>(val winner: ChallengeCandidateResult<T>?)

internal class CipherPrewarmRequest(val signature: String, val throttling: String)

internal fun cipherPrewarmRequest(signature: String?, throttling: String?): CipherPrewarmRequest? {
    val usableSignature = signature?.takeIf(String::isNotBlank) ?: return null
    val usableThrottling = throttling?.takeIf(String::isNotBlank) ?: return null
    return CipherPrewarmRequest(usableSignature, usableThrottling)
}

interface YouTubeStreamingCipherResolver {
    fun resolveSignature(encryptedSignature: String): String?
    fun resolveStreamingUrl(url: String): String

    suspend fun resolveSignatureAsync(encryptedSignature: String): String? {
        return resolveSignature(encryptedSignature)
    }

    suspend fun resolveStreamingUrlAsync(url: String): String {
        return resolveStreamingUrl(url)
    }

    /**
     * 把 sig 和 n 一次算完填进求解器缓存
     *
     * 分开求解要各建一次 isolate 再各传一遍 player.js, 固定成本付两遍;
     * 合并算一次之后, 后面两步照原样跑但直接命中缓存
     * 这一步失败什么都不做, 完整退回原来的两步路径
     */
    fun prewarmChallenges(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) = Unit

    suspend fun prewarmChallengesAsync(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) {
        prewarmChallenges(encryptedSignature, obfuscatedThrottlingParameter)
    }
}

internal suspend fun <T> awaitFirstChallengeSuccess(
    candidates: List<Deferred<ChallengeCandidateResult<T>>>
): ChallengeCandidateResult<T>? = coroutineScope {
    val pending = candidates.toMutableList()
    while (pending.isNotEmpty()) {
        val (selected, candidate) = select {
            pending.forEach { deferred ->
                deferred.onAwait { deferred to it }
            }
        }
        pending.remove(selected)
        if (candidate.value != null) {
            pending.forEach { deferred -> deferred.cancel() }
            return@coroutineScope candidate
        }
    }
    null
}

/** 只读已完成且未取消的候选, 避免对被取消的 Deferred await 抛 CancellationException */
internal suspend fun <T> Deferred<ChallengeCandidateResult<T>>?.hasFailedChallenge(): Boolean {
    val deferred = this ?: return false
    if (!deferred.isCompleted || deferred.isCancelled) return false
    return runCatching { deferred.await().value }.getOrNull() == null
}

internal suspend fun <T> shouldRecordNewPipeFailure(
    skipNewPipe: Boolean,
    candidate: Deferred<ChallengeCandidateResult<T>>?
): Boolean = !skipNewPipe && candidate.hasFailedChallenge()

/** n 和 sig 是放进 URL 查询参数的短 token, 可能携带 Base64 填充字符 */
private val CIPHER_TOKEN_PATTERN = Regex("^[A-Za-z0-9._~+\\-/=]+$")

/** JS 求值失败时的假成功返回值, 非空且与原串不同, 能骗过朴素校验 */
private val CIPHER_TOKEN_JS_JUNK = setOf(
    "undefined",
    "null",
    "nan",
    "true",
    "false",
    "[object object]"
)

/** 拦截 JS 求值的假成功返回值 */
internal fun isPlausibleCipherToken(value: String?): Boolean {
    val token = value?.trim().orEmpty()
    if (token.isEmpty()) return false
    if (token.lowercase() in CIPHER_TOKEN_JS_JUNK) return false
    return CIPHER_TOKEN_PATTERN.matches(token)
}

internal fun validResolvedCipherToken(obfuscatedToken: String, candidate: String): String? {
    if (candidate == obfuscatedToken) return null
    return candidate.takeIf(::isPlausibleCipherToken)
}

@VisibleForTesting
internal fun describeCipherTokenShape(value: String?): String {
    val token = value?.trim().orEmpty()
    val invalidAsciiCount = token.count(::isInvalidCipherAscii)
    val nonAsciiCount = token.count { it.code >= 128 }
    val invalidAsciiCodes = token.asSequence()
        .filter(::isInvalidCipherAscii)
        .map { it.code }
        .distinct()
        .joinToString(",")
    return "length=${token.length},invalidAscii=$invalidAsciiCount," +
        "invalidAsciiCodes=$invalidAsciiCodes,nonAscii=$nonAsciiCount"
}

private fun isInvalidCipherAscii(character: Char): Boolean =
    character.code < 128 && !CIPHER_TOKEN_PATTERN.matches(character.toString())

/** 解密后的流地址必须带合法 n */
internal fun hasPlausibleThrottlingParameter(url: String): Boolean =
    isPlausibleCipherToken(extractStreamQueryParameter(url, "n"))

internal enum class NewPipeThrottlingCandidateStatus { UNCHANGED, INVALID, VALID }

internal fun classifyNewPipeThrottlingUrl(
    originalUrl: String,
    candidateUrl: String?
): NewPipeThrottlingCandidateStatus {
    if (candidateUrl.isNullOrBlank() || candidateUrl == originalUrl) {
        return NewPipeThrottlingCandidateStatus.UNCHANGED
    }
    return if (hasPlausibleThrottlingParameter(candidateUrl)) {
        NewPipeThrottlingCandidateStatus.VALID
    } else {
        NewPipeThrottlingCandidateStatus.INVALID
    }
}

fun extractStreamQueryParameter(url: String, key: String): String? {
    val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
    return rawQuery.split('&')
        .asSequence()
        .mapNotNull { segment ->
            val resolvedKey = URLDecoder.decode(
                segment.substringBefore('='),
                Charsets.UTF_8.name()
            )
            if (resolvedKey.isBlank()) {
                null
            } else {
                resolvedKey to URLDecoder.decode(
                    segment.substringAfter('=', ""),
                    Charsets.UTF_8.name()
                )
            }
        }
        .firstOrNull { (resolvedKey, _) -> resolvedKey == key }
        ?.second
}

internal fun replaceStreamQueryParameter(url: String, key: String, value: String): String {
    val pattern = Regex("([?&])${Regex.escape(key)}=[^&]*")
    return if (pattern.containsMatchIn(url)) {
        val match = pattern.find(url) ?: return url
        buildString(url.length + value.length) {
            append(url, 0, match.range.first)
            append(match.groupValues[1])
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            append(url, match.range.last + 1, url.length)
        }
    } else {
        val separator = if (url.contains('?')) '&' else '?'
        buildString(url.length + key.length + value.length + 2) {
            append(url)
            append(separator)
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
        }
    }
}

internal interface YouTubeNewPipeCipherBackend {
    suspend fun resolveSignature(videoId: String, encryptedSignature: String): String?
    suspend fun resolveStreamingUrl(videoId: String, url: String): String?
}

private object DefaultNewPipeCipherBackend : YouTubeNewPipeCipherBackend {
    override suspend fun resolveSignature(videoId: String, encryptedSignature: String): String? =
        runInterruptible(Dispatchers.IO) {
            YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encryptedSignature)
        }

    override suspend fun resolveStreamingUrl(videoId: String, url: String): String? =
        runInterruptible(Dispatchers.IO) {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }
}


fun createDefaultStreamingCipherResolver(
    videoId: String,
    playerJsUrl: String,
    fallbackPlayerJsUrl: () -> String,
    ejsChallengeSolver: YouTubeEjsChallengeSolver?
): YouTubeStreamingCipherResolver = DefaultYouTubeStreamingCipherResolver(
    videoId = videoId,
    playerJsUrl = playerJsUrl,
    fallbackPlayerJsUrl = fallbackPlayerJsUrl,
    ejsChallengeSolver = ejsChallengeSolver
)

internal class DefaultYouTubeStreamingCipherResolver(
    private val videoId: String,
    private val playerJsUrl: String,
    private val fallbackPlayerJsUrl: () -> String,
    private val ejsChallengeSolver: YouTubeEjsChallengeSolver?,
    private val newPipeBackend: YouTubeNewPipeCipherBackend = DefaultNewPipeCipherBackend
) : YouTubeStreamingCipherResolver {
    private val signatureErrorLogged = AtomicBoolean(false)
    private val throttlingErrorLogged = AtomicBoolean(false)
    private val signatureEjsFallbackLogged = AtomicBoolean(false)
    private val throttlingEjsFallbackLogged = AtomicBoolean(false)
    private val signatureResolutionLogged = AtomicBoolean(false)
    private val throttlingResolutionLogged = AtomicBoolean(false)
    private val throttlingUnresolvedDropLogged = AtomicBoolean(false)
    private val prewarmedSignatures = ConcurrentHashMap<String, String>()
    private val prewarmedThrottlingParameters = ConcurrentHashMap<String, String>()

    private fun maybeLogResolution(
        challengeType: String,
        source: String,
        elapsedMs: Long,
        logged: AtomicBoolean
    ) {
        if (shouldLogStreamingCipherResolution(elapsedMs, logged)) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "Resolved $challengeType via $source for $videoId elapsedMs=$elapsedMs"
            )
        }
    }

    private fun logOnce(logged: AtomicBoolean, log: () -> Unit) {
        if (logged.compareAndSet(false, true)) log()
    }

    private fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    private suspend fun solveEjsChallenge(
        resolvedPlayerJsUrl: String,
        encryptedSignature: String? = null,
        throttlingParameter: String? = null
    ): YouTubeJsChallengeSolveResult = runCatching {
        ejsChallengeSolver?.solveDetailedAsync(
            playerJsUrl = resolvedPlayerJsUrl,
            encryptedSignature = encryptedSignature,
            throttlingParameter = throttlingParameter
        )
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        YouTubeJsChallengeSolveResult(
            status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
            detail = "solveDetailed threw unexpectedly",
            cause = error
        )
    } ?: YouTubeJsChallengeSolveResult(
        status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
        detail = "ejsChallengeSolver is unavailable"
    )

    private suspend fun resolveSignatureWithNewPipe(
        encryptedSignature: String
    ): ChallengeCandidateResult<String> {
        delay(NEWPIPE_FALLBACK_START_DELAY_MS.milliseconds)
        val startedAtMs = System.currentTimeMillis()
        val resolved = runCatching {
            newPipeBackend.resolveSignature(videoId, encryptedSignature)
        }.onFailure { error ->
            logNewPipeSignatureFailure(error, startedAtMs)
        }.getOrNull()?.let { candidate ->
            validResolvedCipherToken(encryptedSignature, candidate)
        }
        return ChallengeCandidateResult(
            source = "NEWPIPE",
            value = resolved,
            elapsedMs = playbackElapsedMs(startedAtMs)
        )
    }

    private fun logNewPipeSignatureFailure(error: Throwable, startedAtMs: Long) {
        rethrowCancellation(error)
        logOnce(signatureErrorLogged) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Failed to deobfuscate streaming signature for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                error
            )
        }
    }

    private suspend fun resolveSignatureWithEjs(
        resolvedPlayerJsUrl: String,
        encryptedSignature: String
    ): ChallengeCandidateResult<String> {
        val startedAtMs = System.currentTimeMillis()
        val result = solveEjsChallenge(
            resolvedPlayerJsUrl = resolvedPlayerJsUrl,
            encryptedSignature = encryptedSignature
        )
        val elapsedMs = playbackElapsedMs(startedAtMs)
        val resolved = result.solution.signature
            ?.let { validResolvedCipherToken(encryptedSignature, it) }
        if (resolved == null) logRejectedEjsSignature(result, elapsedMs)
        return ChallengeCandidateResult(
            source = "EJS_FALLBACK",
            value = resolved,
            elapsedMs = elapsedMs
        )
    }

    private fun logRejectedEjsSignature(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        if (result.status == YouTubeJsChallengeSolveStatus.SUCCESS) {
            logInvalidEjsSignature(result, elapsedMs)
        } else {
            logFailedEjsSignature(result, elapsedMs)
        }
    }

    private fun logInvalidEjsSignature(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        val rejected = result.solution.signature ?: return
        logOnce(signatureEjsFallbackLogged) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "EJS signature result rejected by token validation for " +
                    "$videoId: status=${result.status}, " +
                    "hasValue=true, " +
                    "shape=${describeCipherTokenShape(rejected)}, " +
                    "elapsedMs=$elapsedMs"
            )
        }
    }

    private fun logFailedEjsSignature(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        if (signatureEjsFallbackLogged.compareAndSet(false, true)) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "EJS signature fallback failed for $videoId: ${result.summary()}, elapsedMs=$elapsedMs",
                result.cause
            )
        }
    }

    private suspend fun resolveThrottlingWithNewPipe(url: String): ChallengeCandidateResult<String> {
        delay(NEWPIPE_FALLBACK_START_DELAY_MS.milliseconds)
        val startedAtMs = System.currentTimeMillis()
        val candidateUrl = runCatching {
            newPipeBackend.resolveStreamingUrl(videoId, url)
        }.onFailure { error ->
            logNewPipeThrottlingFailure(error, startedAtMs)
        }.getOrNull()
        val status = classifyNewPipeThrottlingUrl(url, candidateUrl)
        if (status == NewPipeThrottlingCandidateStatus.INVALID) {
            logRejectedNewPipeThrottling(candidateUrl)
        }
        return ChallengeCandidateResult(
            source = "NEWPIPE",
            value = candidateUrl.takeIf { status == NewPipeThrottlingCandidateStatus.VALID },
            elapsedMs = playbackElapsedMs(startedAtMs)
        )
    }

    private fun logNewPipeThrottlingFailure(error: Throwable, startedAtMs: Long) {
        rethrowCancellation(error)
        logOnce(throttlingErrorLogged) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Failed to deobfuscate throttling parameter for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                error
            )
        }
    }

    private fun logRejectedNewPipeThrottling(candidateUrl: String?) {
        if (throttlingErrorLogged.compareAndSet(false, true)) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Reject NewPipe throttling result for $videoId: " +
                    "n=${rejectedThrottlingToken(candidateUrl)}"
            )
        }
    }

    private fun rejectedThrottlingToken(candidateUrl: String?): String? =
        candidateUrl?.let { extractStreamQueryParameter(it, "n") }

    private suspend fun resolveThrottlingWithEjs(
        resolvedPlayerJsUrl: String,
        obfuscatedN: String,
        url: String
    ): ChallengeCandidateResult<String> {
        val startedAtMs = System.currentTimeMillis()
        val result = solveEjsChallenge(
            resolvedPlayerJsUrl = resolvedPlayerJsUrl,
            throttlingParameter = obfuscatedN
        )
        val elapsedMs = playbackElapsedMs(startedAtMs)
        val resolved = result.solution.throttlingParameter
            ?.let { validResolvedCipherToken(obfuscatedN, it) }
            ?.let { replaceStreamQueryParameter(url, "n", it) }
        if (resolved == null) logRejectedEjsThrottling(result, elapsedMs)
        return ChallengeCandidateResult(
            source = "EJS_FALLBACK",
            value = resolved,
            elapsedMs = elapsedMs
        )
    }

    private fun logRejectedEjsThrottling(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        if (result.status == YouTubeJsChallengeSolveStatus.SUCCESS) {
            logInvalidEjsThrottling(result, elapsedMs)
        } else {
            logFailedEjsThrottling(result, elapsedMs)
        }
    }

    private fun logInvalidEjsThrottling(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        val rejected = result.solution.throttlingParameter ?: return
        logOnce(throttlingEjsFallbackLogged) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "EJS throttling result rejected by token validation for " +
                    "$videoId: status=${result.status}, " +
                    "hasValue=true, " +
                    "shape=${describeCipherTokenShape(rejected)}, " +
                    "elapsedMs=$elapsedMs"
            )
        }
    }

    private fun logFailedEjsThrottling(result: YouTubeJsChallengeSolveResult, elapsedMs: Long) {
        if (throttlingEjsFallbackLogged.compareAndSet(false, true)) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "EJS throttling fallback failed for $videoId: ${result.summary()}, elapsedMs=$elapsedMs",
                result.cause
            )
        }
    }

    private suspend fun <T> raceChallenges(
        resolvedPlayerJsUrl: String,
        skipNewPipe: Boolean,
        newPipeCandidate: suspend () -> ChallengeCandidateResult<T>,
        ejsCandidate: suspend () -> ChallengeCandidateResult<T>,
        recordNewPipeFailure: () -> Unit
    ): ChallengeRaceOutcome<T>? = coroutineScope {
        withTimeoutOrNull(CIPHER_RESOLVE_TIMEOUT_MS.milliseconds) {
            val newPipe = if (skipNewPipe) null else async(Dispatchers.Default) { newPipeCandidate() }
            val ejs = if (resolvedPlayerJsUrl.isBlank()) null else async(Dispatchers.IO) { ejsCandidate() }
            val winner = awaitFirstChallengeSuccess(listOfNotNull(newPipe, ejs))
            if (shouldRecordNewPipeFailure(skipNewPipe, newPipe)) recordNewPipeFailure()
            ChallengeRaceOutcome(winner)
        }
    }

    override fun prewarmChallenges(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) {
        // 同步解析入口只允许读取已有缓存, 不能再次阻塞调用线程
    }

    override suspend fun prewarmChallengesAsync(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) {
        // 只有两个都在才值得合并, 单个的话走原路径一样是一次求解
        val request = cipherPrewarmRequest(encryptedSignature, obfuscatedThrottlingParameter) ?: return
        val resolvedPlayerJsUrl = prewarmPlayerJsUrl() ?: return
        prewarm(request, resolvedPlayerJsUrl)
    }

    private fun prewarmPlayerJsUrl(): String? {
        if (ejsChallengeSolver == null) return null
        return playerJsUrl.ifBlank { fallbackPlayerJsUrl() }.takeIf(String::isNotBlank)
    }

    private suspend fun prewarm(request: CipherPrewarmRequest, resolvedPlayerJsUrl: String) {
        val startedAtMs = System.currentTimeMillis()
        val prewarmed = solveEjsChallenge(
            resolvedPlayerJsUrl = resolvedPlayerJsUrl,
            encryptedSignature = request.signature,
            throttlingParameter = request.throttling
        )
        if (prewarmed.status != YouTubeJsChallengeSolveStatus.SUCCESS) return
        cachePrewarmed(request, prewarmed.solution, startedAtMs)
    }

    private fun cachePrewarmed(
        request: CipherPrewarmRequest,
        solution: YouTubeJsChallengeSolution,
        startedAtMs: Long
    ) {
        val storedSignature = cacheCipherToken(solution.signature, request.signature, prewarmedSignatures)
        val storedThrottling = cacheCipherToken(
            solution.throttlingParameter,
            request.throttling,
            prewarmedThrottlingParameters
        )
        NPLogger.d(
            "YouTubeMusicPlayback",
            "prewarmed sig and n together for $videoId " +
                "hasSignature=${solution.signature != null} " +
                "storedSignature=${storedSignature != null} " +
                "hasThrottling=${solution.throttlingParameter != null} " +
                "storedThrottling=${storedThrottling != null} " +
                "elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
    }

    private fun cacheCipherToken(
        candidate: String?,
        obfuscated: String,
        cache: ConcurrentHashMap<String, String>
    ): String? {
        val valid = candidate?.takeIf(::isPlausibleCipherToken) ?: return null
        cache[obfuscated] = valid
        return valid
    }

    override fun resolveSignature(encryptedSignature: String): String? {
        return prewarmedSignatures[encryptedSignature]
    }

    override suspend fun resolveSignatureAsync(encryptedSignature: String): String? {
        cachedSignature(encryptedSignature)?.let { return it }
        return resolveUncachedSignature(encryptedSignature)
    }

    private suspend fun resolveUncachedSignature(encryptedSignature: String): String? {
        val resolvedPlayerJsUrl = playerJsUrl.ifBlank { fallbackPlayerJsUrl() }
        val skipNewPipe = NewPipeFallbackTracker.maybeSkipSignature(resolvedPlayerJsUrl)
        maybeLogSkippedSignatureNewPipe(skipNewPipe)
        val winner = raceChallenges(
            resolvedPlayerJsUrl = resolvedPlayerJsUrl,
            skipNewPipe = skipNewPipe,
            newPipeCandidate = { resolveSignatureWithNewPipe(encryptedSignature) },
            ejsCandidate = { resolveSignatureWithEjs(resolvedPlayerJsUrl, encryptedSignature) },
            recordNewPipeFailure = { NewPipeFallbackTracker.recordSignatureFailure(resolvedPlayerJsUrl) }
        )?.winner
        return resolvedSignatureRaceValue(winner)
    }

    private fun resolvedSignatureRaceValue(winner: ChallengeCandidateResult<String>?): String? {
        if (winner == null) return null
        maybeLogResolution("signature", winner.source, winner.elapsedMs, signatureResolutionLogged)
        return winner.value
    }

    private fun cachedSignature(encryptedSignature: String): String? {
        val cached = prewarmedSignatures[encryptedSignature] ?: return null
        maybeLogResolution("signature", "PREWARM_CACHE", 0L, signatureResolutionLogged)
        return cached
    }

    private fun maybeLogSkippedSignatureNewPipe(skipNewPipe: Boolean) {
        if (!skipNewPipe) return
        logOnce(signatureErrorLogged) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "Skip NewPipe signature for $videoId because player.js is already flagged"
            )
        }
    }

    override fun resolveStreamingUrl(url: String): String {
        val obfuscatedN = extractStreamQueryParameter(url, "n") ?: return url
        return cachedThrottlingUrl(url, obfuscatedN).orEmpty()
    }

    override suspend fun resolveStreamingUrlAsync(url: String): String {
        val obfuscatedN = extractStreamQueryParameter(url, "n") ?: return url
        cachedThrottlingUrl(url, obfuscatedN)?.let { return it }
        return resolveUncachedThrottlingUrl(url, obfuscatedN)
    }

    private suspend fun resolveUncachedThrottlingUrl(url: String, obfuscatedN: String): String {
        val resolvedPlayerJsUrl = playerJsUrl.ifBlank { fallbackPlayerJsUrl() }
        val skipNewPipe = NewPipeFallbackTracker.maybeSkipThrottling(resolvedPlayerJsUrl)
        maybeLogSkippedThrottlingNewPipe(skipNewPipe)
        val outcome = raceChallenges(
            resolvedPlayerJsUrl = resolvedPlayerJsUrl,
            skipNewPipe = skipNewPipe,
            newPipeCandidate = { resolveThrottlingWithNewPipe(url) },
            ejsCandidate = { resolveThrottlingWithEjs(resolvedPlayerJsUrl, obfuscatedN, url) },
            recordNewPipeFailure = { NewPipeFallbackTracker.recordThrottlingFailure(resolvedPlayerJsUrl) }
        )
        return resolvedThrottlingRaceUrl(url, outcome)
    }

    private fun resolvedThrottlingRaceUrl(url: String, outcome: ChallengeRaceOutcome<String>?): String {
        val winner = outcome?.winner
        if (winner != null) {
            maybeLogResolution("throttling", winner.source, winner.elapsedMs, throttlingResolutionLogged)
            return winner.streamingUrlOrOriginal(url)
        }
        if (outcome != null) logUnresolvedThrottling()
        return ""
    }

    private fun ChallengeCandidateResult<String>.streamingUrlOrOriginal(originalUrl: String): String =
        value ?: originalUrl

    private fun cachedThrottlingUrl(url: String, obfuscatedN: String): String? {
        val cached = prewarmedThrottlingParameters[obfuscatedN] ?: return null
        maybeLogResolution("throttling", "PREWARM_CACHE", 0L, throttlingResolutionLogged)
        return replaceStreamQueryParameter(url, "n", cached)
    }

    private fun maybeLogSkippedThrottlingNewPipe(skipNewPipe: Boolean) {
        if (!skipNewPipe) return
        logOnce(throttlingErrorLogged) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "Skip NewPipe throttling for $videoId because player.js is already flagged"
            )
        }
    }

    private fun logUnresolvedThrottling() {
        if (throttlingUnresolvedDropLogged.compareAndSet(false, true)) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "drop stream candidate: throttling n unresolved for $videoId, skip to next candidate/client"
            )
        }
    }

}
