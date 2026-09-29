package moe.ouom.neriplayer.api.bilibili.sponsorblock

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

class BiliSponsorBlockClient(okHttpClient: OkHttpClient) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun loadSegments(bvid: String): String? {
        val request = Request.Builder()
            .url("$API_URL${biliSponsorBlockHashPrefix(bvid)}".toHttpUrl())
            .header("Origin", CLIENT_ORIGIN)
            .header("X-Ext-Version", CLIENT_VERSION)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> {
                    NPLogger.w(TAG, "segment query returned HTTP ${response.code}")
                    null
                }
                else -> response.body.readTextWithLimit(RESPONSE_MAX_BYTES)
            }
        }
    }

    private companion object {
        const val TAG = "BiliSponsorBlock"
        const val API_URL = "https://bsbsb.top/api/skipSegments/"
        const val CLIENT_ORIGIN = "https://github.com/cwuom/NeriPlayer"
        const val CLIENT_VERSION = "NeriPlayer-Android"
        const val USER_AGENT = "NeriPlayer Android"
        const val CALL_TIMEOUT_MS = 3_000L
        const val RESPONSE_MAX_BYTES = 512L * 1024L
    }
}

internal fun biliSponsorBlockHashPrefix(bvid: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bvid.toByteArray(Charsets.UTF_8))
    return buildString(4) {
        digest.take(2).forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }
}

private fun ResponseBody.readTextWithLimit(maxBytes: Long): String {
    val declaredBytes = contentLength()
    if (declaredBytes > maxBytes) {
        throw IOException("BilibiliSponsorBlock response exceeds $maxBytes bytes")
    }

    val sink = Buffer()
    val source = source()
    var totalBytes = 0L
    while (true) {
        val readBytes = source.read(sink, minOf(maxBytes - totalBytes + 1L, 64L * 1024L))
        if (readBytes == -1L) break
        totalBytes += readBytes
        if (totalBytes > maxBytes) {
            throw IOException("BilibiliSponsorBlock response exceeds $maxBytes bytes")
        }
    }
    return sink.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}

private const val HEX_DIGITS = "0123456789abcdef"
