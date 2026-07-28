package moe.ouom.neriplayer.data.auth.youtube

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
 * File: moe.ouom.neriplayer.data.auth.youtube/YouTubeCookieRotator
 * Created: 2026/7/27
 */

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val ROTATE_COOKIES_URL = "https://accounts.google.com/RotateCookies"
private const val ROTATE_COOKIES_ORIGIN = "https://accounts.google.com"

/** 服务端只认这个 jspb 哨兵形状, 换成别的会直接 400 */
private const val ROTATE_COOKIES_PAYLOAD = "[000,\"-0000000000000000000\"]"

/** 连续两次轮换之间的最小间隔, 防止刷新风暴把端点打炮 */
internal const val ROTATION_MIN_INTERVAL_MS = 60_000L

/** 强制刷新也要留出间隔, 避免失败重试把轮换端点打成请求风暴 */
internal const val ROTATION_FORCE_MIN_INTERVAL_MS = 10_000L

/** 服务端没告诉我们周期时的兜底, 与它自己声明的 600 秒一致 */
internal const val ROTATION_DEFAULT_INTERVAL_MS = 600_000L

/** 允许几次连续失败再开始退避, 留够余量给网络抖动 */
internal const val ROTATION_REJECTIONS_BEFORE_BACKOFF = 3

/** 退避上限, 到这里基本等于放弃轮换, 保活交回 WebView 刷新那条路 */
internal const val ROTATION_MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L

private const val ROTATION_MAX_BACKOFF_EXPONENT = 16

internal data class YouTubeCookieRotationState(
    val lastRotatedAtMs: Long = 0L,
    val rotationIntervalMs: Long = ROTATION_DEFAULT_INTERVAL_MS,
    val consecutiveRejections: Int = 0
)

internal interface YouTubeCookieRotationStateStore {
    fun read(): YouTubeCookieRotationState

    fun write(state: YouTubeCookieRotationState)
}

internal object NoOpYouTubeCookieRotationStateStore : YouTubeCookieRotationStateStore {
    override fun read(): YouTubeCookieRotationState = YouTubeCookieRotationState()

    override fun write(state: YouTubeCookieRotationState) = Unit
}

internal class SharedPreferencesYouTubeCookieRotationStateStore(
    context: Context
) : YouTubeCookieRotationStateStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): YouTubeCookieRotationState {
        return YouTubeCookieRotationState(
            lastRotatedAtMs = preferences.getLong(LAST_ROTATED_AT_KEY, 0L)
                .coerceAtLeast(0L),
            rotationIntervalMs = preferences.getLong(
                ROTATION_INTERVAL_KEY,
                ROTATION_DEFAULT_INTERVAL_MS
            ).takeIf { it > 0L } ?: ROTATION_DEFAULT_INTERVAL_MS,
            consecutiveRejections = preferences.getInt(CONSECUTIVE_REJECTIONS_KEY, 0)
                .coerceAtLeast(0)
        )
    }

    override fun write(state: YouTubeCookieRotationState) {
        val committed = preferences.edit()
            .putLong(LAST_ROTATED_AT_KEY, state.lastRotatedAtMs.coerceAtLeast(0L))
            .putLong(
                ROTATION_INTERVAL_KEY,
                state.rotationIntervalMs.takeIf { it > 0L } ?: ROTATION_DEFAULT_INTERVAL_MS
            )
            .putInt(CONSECUTIVE_REJECTIONS_KEY, state.consecutiveRejections.coerceAtLeast(0))
            .commit()
        if (!committed) {
            NPLogger.w(TAG, "failed to persist cookie rotation state")
        }
    }

    private companion object {
        const val TAG = "YouTubeCookieRotator"
        const val PREFERENCES_NAME = "youtube_cookie_rotation_state"
        const val LAST_ROTATED_AT_KEY = "last_rotated_at_ms"
        const val ROTATION_INTERVAL_KEY = "rotation_interval_ms"
        const val CONSECUTIVE_REJECTIONS_KEY = "consecutive_rejections"
    }
}

/** 轮换真正会换掉的两项, 其余 Set-Cookie 一律不收 */
internal val ROTATED_COOKIE_KEYS = listOf("__Secure-1PSIDTS", "__Secure-3PSIDTS")

/**
 * 轮换请求要带上的 cookie
 *
 * 除了 1PSID/3PSID 本体, 服务端还要 APISID/SAPISID 这类绑定项来确认是同一个会话,
 * 只发 SID 会被判成未授权
 */
private val ROTATION_REQUEST_COOKIE_KEYS = listOf(
    "SID",
    "HSID",
    "SSID",
    "APISID",
    "SAPISID",
    "LSID",
    "OSID",
    "__Secure-1PSID",
    "__Secure-3PSID",
    "__Secure-1PAPISID",
    "__Secure-3PAPISID",
    "__Secure-1PSIDTS",
    "__Secure-3PSIDTS",
    "SIDCC",
    "__Secure-1PSIDCC",
    "__Secure-3PSIDCC"
)

/**
 * 手里这份 cookie 够不够发起轮换
 *
 * 缺主体或缺绑定项时请求必然被拒, 提前判掉省一次往返, 也免得把失败计入熔断
 */
internal fun hasYouTubeRotationPrerequisites(cookies: Map<String, String>): Boolean {
    val hasPrimarySession = !cookies["__Secure-1PSID"].isNullOrBlank() ||
        !cookies["__Secure-3PSID"].isNullOrBlank()
    val hasBindingCookie = !cookies["SAPISID"].isNullOrBlank() ||
        !cookies["APISID"].isNullOrBlank()
    return hasPrimarySession && hasBindingCookie
}

internal fun buildYouTubeRotationCookieHeader(cookies: Map<String, String>): String {
    return ROTATION_REQUEST_COOKIE_KEYS
        .mapNotNull { key ->
            cookies[key]?.takeIf { it.isNotBlank() }?.let { value -> "$key=$value" }
        }
        .joinToString("; ")
}

/**
 * 响应形如 )]}'[["identity.hfcr",600],["di",N]]
 *
 * 600 是服务端自己声明的下次轮换间隔, 跟着它排期比我们拍一个周期准
 */
internal fun parseYouTubeRotationIntervalMs(responseBody: String): Long {
    val seconds = Regex("\"identity\\.hfcr\"\\s*,\\s*(\\d+)")
        .find(responseBody)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
    return seconds?.takeIf { it > 0L }?.times(1000L) ?: ROTATION_DEFAULT_INTERVAL_MS
}

/**
 * 从 Set-Cookie 里挑出真正被轮换的两项
 *
 * 过期指令也走 Set-Cookie, 值为空或带 Max-Age=0 的一律不能当成新凭据收下
 */
internal fun collectRotatedYouTubeCookies(setCookieHeaders: List<String>): Map<String, String> {
    val rotated = linkedMapOf<String, String>()
    setCookieHeaders.forEach { header ->
        val update = parseSetCookieUpdate(header) ?: return@forEach
        if (update.name !in ROTATED_COOKIE_KEYS || update.shouldRemove) {
            return@forEach
        }
        val value = update.value.removeSurrounding("\"")
        if (value.isBlank()) {
            return@forEach
        }
        rotated[update.name] = value
    }
    return rotated
}

/**
 * 连续被拒之后要等多久再试
 *
 * 端点对某些账号会一直回 403, 这时每分钟重打一次只是白烧请求还容易被更严地限流;
 * 连续失败就指数退避, 一次成功立刻清零, 所以偶发抖动不会把正常轮换拖慢
 */
internal fun nextYouTubeRotationBackoffMs(
    consecutiveRejections: Int,
    minIntervalMs: Long = ROTATION_MIN_INTERVAL_MS,
    maxBackoffMs: Long = ROTATION_MAX_BACKOFF_MS
): Long {
    if (consecutiveRejections < ROTATION_REJECTIONS_BEFORE_BACKOFF) {
        return minIntervalMs
    }
    val exponent = (consecutiveRejections - ROTATION_REJECTIONS_BEFORE_BACKOFF + 1)
        .coerceAtMost(ROTATION_MAX_BACKOFF_EXPONENT)
    var backoffMs = minIntervalMs
    repeat(exponent) {
        backoffMs = backoffMs.saturatingDouble()
    }
    return backoffMs.coerceAtMost(maxBackoffMs)
}

private fun Long.saturatingDouble(): Long = if (this > Long.MAX_VALUE / 2) Long.MAX_VALUE else this * 2

internal fun shouldRotateYouTubeCookies(
    lastRotatedAtMs: Long,
    nowMs: Long,
    minIntervalMs: Long = ROTATION_MIN_INTERVAL_MS
): Boolean {
    if (lastRotatedAtMs <= 0L) {
        return true
    }
    return nowMs - lastRotatedAtMs >= minIntervalMs
}

internal fun shouldThrottleYouTubeCookieRotation(
    force: Boolean,
    lastRotatedAtMs: Long,
    nowMs: Long,
    minIntervalMs: Long
): Boolean {
    // force 只放宽正常节流, 不能绕过已经生效的指数退避
    val effectiveMinIntervalMs = if (force && minIntervalMs <= ROTATION_MIN_INTERVAL_MS) {
        minOf(minIntervalMs, ROTATION_FORCE_MIN_INTERVAL_MS)
    } else {
        minIntervalMs
    }
    return !shouldRotateYouTubeCookies(lastRotatedAtMs, nowMs, effectiveMinIntervalMs)
}

/**
 * 只有值真的变了才算轮换成功
 *
 * 服务端可能把原值原样回给我们, 那种情况落盘没有意义, 也不该重置失败计数
 */
internal fun collectChangedRotatedCookies(
    currentCookies: Map<String, String>,
    rotatedCookies: Map<String, String>
): Map<String, String> {
    return rotatedCookies.filter { (key, value) -> currentCookies[key] != value }
}

internal sealed interface YouTubeCookieRotationOutcome {
    data class Rotated(val cookies: Map<String, String>) : YouTubeCookieRotationOutcome

    data object Unchanged : YouTubeCookieRotationOutcome

    data object Throttled : YouTubeCookieRotationOutcome

    data class Rejected(val code: Int) : YouTubeCookieRotationOutcome

    data object NetworkError : YouTubeCookieRotationOutcome

    data object Skipped : YouTubeCookieRotationOutcome
}

/** 轮换请求和凭据合并必须共用同一把锁, 否则前台和 Worker 会互相覆盖新值 */
internal val youtubeAuthRotationMutex = Mutex()

/**
 * 显式向 Google 申请轮换 *PSIDTS
 *
 * 这两项每十分钟换一次, 过期后所有请求都会退化成匿名; 靠加载页面顺带刷新是碰运气,
 * 后台待久了必然错过, 所以这里改成主动打端点
 */
internal class YouTubeCookieRotator(
    private val httpClientProvider: () -> OkHttpClient = { AppContainer.sharedOkHttpClient },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val stateStore: YouTubeCookieRotationStateStore =
        NoOpYouTubeCookieRotationStateStore
) {
    companion object {
        private const val TAG = "YouTubeCookieRotator"

        private val rotationMutex = Mutex()
    }

    @Volatile
    private var lastRotatedAtMs: Long = 0L

    @Volatile
    private var rotationIntervalMs: Long = ROTATION_DEFAULT_INTERVAL_MS

    @Volatile
    private var consecutiveRejections: Int = 0

    init {
        restoreRotationState()
    }

    /** 轮换请求自带 Cookie 头, 客户端再插一手只会把两份 cookie 混在一起 */
    private val rotationClient: OkHttpClient by lazy {
        httpClientProvider()
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .callTimeout(10L, TimeUnit.SECONDS)
            .build()
    }

    fun nextRotationIntervalMs(): Long = rotationIntervalMs

    /**
     * 返回真正发生变化的 cookie, 没换到就是空表
     *
     * force 只跳过节流, 不跳过前置条件检查
     */
    suspend fun rotate(
        cookies: Map<String, String>,
        userAgent: String,
        force: Boolean = false
    ): Map<String, String> {
        return when (
            val outcome = rotateDetailed(
                cookies = cookies,
                userAgent = userAgent,
                force = force
            )
        ) {
            is YouTubeCookieRotationOutcome.Rotated -> outcome.cookies
            else -> emptyMap()
        }
    }

    suspend fun rotateDetailed(
        cookies: Map<String, String>,
        userAgent: String,
        force: Boolean = false
    ): YouTubeCookieRotationOutcome = withContext(Dispatchers.IO) {
        rotationMutex.withLock {
            rotateLocked(cookies, userAgent, force)
        }
    }

    private suspend fun rotateLocked(
        cookies: Map<String, String>,
        userAgent: String,
        force: Boolean
    ): YouTubeCookieRotationOutcome {
        if (!hasYouTubeRotationPrerequisites(cookies)) {
            NPLogger.d(TAG, "rotate skipped: missing session or binding cookie")
            return YouTubeCookieRotationOutcome.Skipped
        }
        restoreRotationState()
        val now = nowMsProvider()
        val retryIntervalMs = nextYouTubeRotationBackoffMs(consecutiveRejections)
        if (shouldThrottleYouTubeCookieRotation(force, lastRotatedAtMs, now, retryIntervalMs)) {
            NPLogger.d(
                TAG,
                "rotate throttled: sinceLastMs=${now - lastRotatedAtMs} " +
                    "retryIntervalMs=$retryIntervalMs rejections=$consecutiveRejections " +
                    "force=$force"
            )
            return YouTubeCookieRotationOutcome.Throttled
        }

        val cookieHeader = buildYouTubeRotationCookieHeader(cookies)
        if (cookieHeader.isBlank()) {
            return YouTubeCookieRotationOutcome.Skipped
        }

        val request = Request.Builder()
            .url(ROTATE_COOKIES_URL)
            .post(ROTATE_COOKIES_PAYLOAD.toRequestBody("application/json".toMediaType()))
            .header("Origin", ROTATE_COOKIES_ORIGIN)
            .header("Referer", ROTATE_COOKIES_ORIGIN)
            .header("Cookie", cookieHeader)
            .apply {
                if (userAgent.isNotBlank()) {
                    header("User-Agent", userAgent)
                }
            }
            .build()

        return try {
            val outcome = rotationClient.newCall(request).awaitResponse { response ->
                val setCookieHeaders = response.headers("Set-Cookie")
                val body = response.body.string()
                Triple(response.code, setCookieHeaders, body)
            }
            val (code, setCookieHeaders, body) = outcome
            lastRotatedAtMs = nowMsProvider()
            if (code != 200) {
                consecutiveRejections += 1
                persistRotationState()
                NPLogger.w(
                    TAG,
                    "rotate rejected: code=$code rejections=$consecutiveRejections nextRetryMs=${nextYouTubeRotationBackoffMs(consecutiveRejections)}"
                )
                return YouTubeCookieRotationOutcome.Rejected(code)
            }
            consecutiveRejections = 0
            rotationIntervalMs = parseYouTubeRotationIntervalMs(body)
            persistRotationState()
            val rotated = collectRotatedYouTubeCookies(setCookieHeaders)
            val changed = collectChangedRotatedCookies(cookies, rotated)
            NPLogger.i(
                TAG,
                "rotate ok: rotated=${rotated.keys.joinToString()} changed=${changed.keys.joinToString()} nextIntervalMs=$rotationIntervalMs"
            )
            if (changed.isEmpty()) {
                YouTubeCookieRotationOutcome.Unchanged
            } else {
                YouTubeCookieRotationOutcome.Rotated(changed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastRotatedAtMs = nowMsProvider()
            consecutiveRejections += 1
            persistRotationState()
            NPLogger.w(TAG, "rotate failed: ${error.message} rejections=$consecutiveRejections")
            YouTubeCookieRotationOutcome.NetworkError
        }
    }

    private fun restoreRotationState() {
        val state = runCatching { stateStore.read() }
            .onFailure { error ->
                NPLogger.w(TAG, "failed to read cookie rotation state", error)
            }
            .getOrDefault(YouTubeCookieRotationState())
        lastRotatedAtMs = state.lastRotatedAtMs.coerceAtLeast(0L)
        rotationIntervalMs = state.rotationIntervalMs.takeIf { it > 0L }
            ?: ROTATION_DEFAULT_INTERVAL_MS
        consecutiveRejections = state.consecutiveRejections.coerceAtLeast(0)
    }

    private fun persistRotationState() {
        runCatching {
            stateStore.write(
                YouTubeCookieRotationState(
                    lastRotatedAtMs = lastRotatedAtMs,
                    rotationIntervalMs = rotationIntervalMs,
                    consecutiveRejections = consecutiveRejections
                )
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "failed to write cookie rotation state", error)
        }
    }
}
