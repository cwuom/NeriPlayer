package moe.ouom.neriplayer.api.sync.github

import okhttp3.Response
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.TimeZone

class GitHubRateLimitException(
    statusCode: Int,
    val retryAtMillis: Long,
    val automaticRetryAllowed: Boolean,
    message: String
) : GitHubApiException(statusCode, message)

internal object GitHubRateLimitPolicy {
    const val MIN_RETRY_DELAY_MS = 60_000L
    const val MAX_AUTOMATIC_RETRIES = 3

    fun fromResponse(response: Response, body: String, nowMillis: Long): GitHubRateLimitException? {
        if (!isRateLimited(response, body)) return null
        val retryAfter = futureDeadline(retryAfterMillis(response.header("Retry-After"), nowMillis), nowMillis)
        val resetAt = futureDeadline(resetAtMillis(response), nowMillis)
        val retryAt = maxOf(preferredDeadline(retryAfter, resetAt, nowMillis), resetAt ?: 0L, addDelay(nowMillis, 1_000L))
        return exception(response.code, retryAt, false, nowMillis)
    }

    fun exception(statusCode: Int, retryAt: Long, automatic: Boolean, nowMillis: Long): GitHubRateLimitException {
        val seconds = (retryAt.coerceAtLeast(0L) - nowMillis.coerceAtLeast(0L)).coerceAtLeast(0L) / 1_000L + 1L
        val continuation = if (automatic) "稍后重试会从已有进度继续" else "自动续传已暂停，请稍后重试同步"
        return GitHubRateLimitException(statusCode, retryAt, automatic,
            "GitHub 请求被限流，已保留同步进度。约 $seconds 秒后可重试，$continuation")
    }

    private fun isRateLimited(response: Response, body: String): Boolean {
        if (response.code == 429) return true
        if (response.code != 403) return false
        return response.header("Retry-After") != null || response.header("X-RateLimit-Remaining") == "0" ||
            body.contains("rate limit", ignoreCase = true) || body.contains("abuse detection", ignoreCase = true)
    }

    private fun futureDeadline(candidate: Long?, nowMillis: Long): Long? {
        if (candidate == null) return null
        return if (candidate > nowMillis) candidate else null
    }

    private fun preferredDeadline(retryAfter: Long?, resetAt: Long?, nowMillis: Long): Long =
        retryAfter ?: resetAt ?: addDelay(nowMillis, MIN_RETRY_DELAY_MS)

    private fun resetAtMillis(response: Response): Long? {
        if (response.header("X-RateLimit-Remaining") != "0") return null
        return secondsToMillis(response.header("X-RateLimit-Reset")?.toLongOrNull())
    }

    private fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { return secondsToMillis(it)?.let { delay -> addDelay(nowMillis, delay) } }
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val position = ParsePosition(0)
        val date = format.parse(value, position)
        return date?.time?.takeIf { position.index == value.length }
    }

    private fun secondsToMillis(seconds: Long?): Long? {
        if (seconds == null || seconds < 0L || seconds > Long.MAX_VALUE / 1_000L) return null
        return seconds * 1_000L
    }

    fun addDelay(nowMillis: Long, delayMillis: Long): Long {
        val start = nowMillis.coerceAtLeast(0L)
        val delay = delayMillis.coerceAtLeast(0L)
        return if (delay > Long.MAX_VALUE - start) Long.MAX_VALUE else start + delay
    }
}
