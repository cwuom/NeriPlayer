package moe.ouom.neriplayer.platform.subsonic.api

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

enum class SubsonicFailureKind {
    AUTHENTICATION, FORBIDDEN, NOT_FOUND, RATE_LIMITED, SERVER, NETWORK, TIMEOUT,
    TLS, UNSUPPORTED, INVALID_RESPONSE, ACCOUNT_UNAVAILABLE, CONFIG_CHANGED
}

/** Safe fields only: no authenticated URL, response text or underlying transport cause. */
class SubsonicException(
    val code: Int,
    message: String,
    val kind: SubsonicFailureKind = SubsonicFailureKind.INVALID_RESPONSE,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null
) : IOException(message) {
    val retryable: Boolean get() = kind in setOf(SubsonicFailureKind.NETWORK,
        SubsonicFailureKind.TIMEOUT, SubsonicFailureKind.SERVER, SubsonicFailureKind.RATE_LIMITED)

    companion object {
        fun protocol(code: Int): SubsonicException = when (code) {
            40, 41 -> SubsonicException(code, "服务器账号或密码不正确，请重新登录", SubsonicFailureKind.AUTHENTICATION)
            50 -> SubsonicException(code, "该账号没有访问权限", SubsonicFailureKind.FORBIDDEN)
            70 -> SubsonicException(code, "服务器资源或接口不存在", SubsonicFailureKind.NOT_FOUND)
            20, 30 -> SubsonicException(code, "服务器协议版本不兼容", SubsonicFailureKind.UNSUPPORTED)
            else -> SubsonicException(code, "服务器请求失败（$code）")
        }

        fun http(status: Int, retryAfter: String?): SubsonicException {
            val (kind, text) = when (status) {
                401 -> SubsonicFailureKind.AUTHENTICATION to "服务器账号不可用，请重新登录"
                403 -> SubsonicFailureKind.FORBIDDEN to "该账号没有访问权限"
                404 -> SubsonicFailureKind.NOT_FOUND to "服务器资源或接口不存在"
                429 -> SubsonicFailureKind.RATE_LIMITED to "服务器请求过于频繁，请稍后重试"
                in 500..599 -> SubsonicFailureKind.SERVER to "音乐服务器暂时不可用"
                else -> SubsonicFailureKind.INVALID_RESPONSE to "服务器请求失败（$status）"
            }
            val delayMs = retryAfter?.trim()?.let { value ->
                value.toLongOrNull()?.coerceIn(0L, 86_400L)?.times(1000L)
                    ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0L, 86_400_000L) }.getOrNull()
            }
            return SubsonicException(status, text, kind, status, delayMs)
        }

        fun transport(error: IOException): SubsonicException = when (error) {
            is SubsonicException -> error
            is SocketTimeoutException -> SubsonicException(-1, "音乐服务器连接超时", SubsonicFailureKind.TIMEOUT)
            is SSLException -> SubsonicException(-1, "音乐服务器安全连接失败", SubsonicFailureKind.TLS)
            else -> SubsonicException(-1, "音乐服务器连接失败", SubsonicFailureKind.NETWORK)
        }

        fun accountUnavailable() = SubsonicException(-1, "音乐服务器账号不可用，请在设置中检查配置",
            SubsonicFailureKind.ACCOUNT_UNAVAILABLE)

        fun find(error: Throwable): SubsonicException? = generateSequence(error) { it.cause }
            .take(12).filterIsInstance<SubsonicException>().firstOrNull()
    }
}
