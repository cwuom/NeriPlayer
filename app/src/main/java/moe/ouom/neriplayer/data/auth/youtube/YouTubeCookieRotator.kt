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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val ROTATE_COOKIES_URL = "https://accounts.google.com/RotateCookies"
private const val ROTATE_COOKIES_ORIGIN = "https://accounts.google.com"

/** 服务端只认这个 jspb 哨兵形状, 换成别的会直接 400 */
private const val ROTATE_COOKIES_PAYLOAD = "[000,\"-0000000000000000000\"]"

/** 连续两次轮换之间的最小间隔, 防止刷新风暴把端点打炮 */
internal const val ROTATION_MIN_INTERVAL_MS = 60_000L

/** 服务端没告诉我们周期时的兜底, 与它自己声明的 600 秒一致 */
internal const val ROTATION_DEFAULT_INTERVAL_MS = 600_000L

/** 允许几次连续失败再开始退避, 留够余量给网络抖动 */
internal const val ROTATION_REJECTIONS_BEFORE_BACKOFF = 3

/** 退避上限, 到这里基本等于放弃轮换, 保活交回 WebView 刷新那条路 */
internal const val ROTATION_MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L

private const val ROTATION_MAX_BACKOFF_EXPONENT = 16

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
        val attributes = header.split(';')
        val pair = attributes.firstOrNull().orEmpty()
        val key = pair.substringBefore('=').trim()
        if (key !in ROTATED_COOKIE_KEYS) {
            return@forEach
        }
        val value = pair.substringAfter('=', "").trim().removeSurrounding("\"")
        if (value.isBlank()) {
            return@forEach
        }
        val isExpiryInstruction = attributes.drop(1).any { attribute ->
            val normalized = attribute.trim().lowercase()
            normalized == "max-age=0" || normalized.startsWith("expires=thu, 01 jan 1970")
        }
        if (isExpiryInstruction) {
            return@forEach
        }
        rotated[key] = value
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

/**
 * 显式向 Google 申请轮换 *PSIDTS
 *
 * 这两项每十分钟换一次, 过期后所有请求都会退化成匿名; 靠加载页面顺带刷新是碰运气,
 * 后台待久了必然错过, 所以这里改成主动打端点
 */
internal class YouTubeCookieRotator(
    private val httpClientProvider: () -> OkHttpClient = { AppContainer.sharedOkHttpClient },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "YouTubeCookieRotator"
    }

    @Volatile
    private var lastRotatedAtMs: Long = 0L

    @Volatile
    private var rotationIntervalMs: Long = ROTATION_DEFAULT_INTERVAL_MS

    /** 端点对部分账号恒回 403, 记着连续失败次数好把重试拉开 */
    @Volatile
    private var consecutiveRejections: Int = 0

    /** 轮换请求自带 Cookie 头, 客户端再插一手只会把两份 cookie 混在一起 */
    private val rotationClient: OkHttpClient by lazy {
        httpClientProvider()
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
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
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (!hasYouTubeRotationPrerequisites(cookies)) {
            NPLogger.d(TAG, "rotate skipped: missing session or binding cookie")
            return@withContext emptyMap()
        }
        val now = nowMsProvider()
        val retryIntervalMs = nextYouTubeRotationBackoffMs(consecutiveRejections)
        // 退避期内连 force 也不放行, 否则后台任务每次醒来都会去撞同一堵墙
        if (!shouldRotateYouTubeCookies(lastRotatedAtMs, now, retryIntervalMs) &&
            (!force || retryIntervalMs > ROTATION_MIN_INTERVAL_MS)
        ) {
            NPLogger.d(
                TAG,
                "rotate throttled: sinceLastMs=${now - lastRotatedAtMs} retryIntervalMs=$retryIntervalMs rejections=$consecutiveRejections"
            )
            return@withContext emptyMap()
        }

        val cookieHeader = buildYouTubeRotationCookieHeader(cookies)
        if (cookieHeader.isBlank()) {
            return@withContext emptyMap()
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

        try {
            val outcome = rotationClient.newCall(request).awaitResponse { response ->
                val setCookieHeaders = response.headers("Set-Cookie")
                val body = response.body.string()
                Triple(response.code, setCookieHeaders, body)
            }
            val (code, setCookieHeaders, body) = outcome
            lastRotatedAtMs = nowMsProvider()
            if (code != 200) {
                consecutiveRejections += 1
                NPLogger.w(
                    TAG,
                    "rotate rejected: code=$code rejections=$consecutiveRejections nextRetryMs=${nextYouTubeRotationBackoffMs(consecutiveRejections)}"
                )
                return@withContext emptyMap()
            }
            consecutiveRejections = 0
            rotationIntervalMs = parseYouTubeRotationIntervalMs(body)
            val rotated = collectRotatedYouTubeCookies(setCookieHeaders)
            val changed = collectChangedRotatedCookies(cookies, rotated)
            NPLogger.i(
                TAG,
                "rotate ok: rotated=${rotated.keys.joinToString()} changed=${changed.keys.joinToString()} nextIntervalMs=$rotationIntervalMs"
            )
            changed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastRotatedAtMs = nowMsProvider()
            consecutiveRejections += 1
            NPLogger.w(TAG, "rotate failed: ${error.message} rejections=$consecutiveRejections")
            emptyMap()
        }
    }
}
