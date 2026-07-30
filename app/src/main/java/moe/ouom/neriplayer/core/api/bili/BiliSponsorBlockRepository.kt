package moe.ouom.neriplayer.core.api.bili

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

internal const val BILI_SPONSOR_BLOCK_DURATION_TOLERANCE_MS = 2_000L

internal val BILI_SPONSOR_BLOCK_AUTO_SKIP_CATEGORIES = setOf(
    "sponsor",
    "intro",
    "outro",
    "music_offtopic",
    "filler",
    "padding"
)

internal data class BiliSponsorBlockTarget(
    val bvid: String,
    val cid: Long,
    val durationMs: Long
)

internal data class BiliSponsorBlockSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long
)

internal class BiliSponsorBlockRepository(
    okHttpClient: OkHttpClient
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun loadAutoSkipSegments(target: BiliSponsorBlockTarget): List<BiliSponsorBlockSegment> =
        withContext(Dispatchers.IO) {
            val bvid = target.bvid.trim()
            if (!BVID_REGEX.matches(bvid) || target.cid <= 0L) {
                return@withContext emptyList()
            }

            val request = Request.Builder()
                .url("$API_URL${biliSponsorBlockHashPrefix(bvid)}".toHttpUrl())
                .header("Origin", CLIENT_ORIGIN)
                .header("X-Ext-Version", CLIENT_VERSION)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> emptyList()
                        !response.isSuccessful -> {
                            NPLogger.w(TAG, "segment query returned HTTP ${response.code}")
                            emptyList()
                        }
                        else -> parseBiliSponsorBlockSegments(
                            responseBody = response.body.readTextWithLimit(RESPONSE_MAX_BYTES),
                            target = target.copy(bvid = bvid)
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "segment query failed", error)
                emptyList()
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
        val BVID_REGEX = Regex("^BV[0-9A-Za-z]{10}$")
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

internal fun parseBiliSponsorBlockSegments(
    responseBody: String,
    target: BiliSponsorBlockTarget
): List<BiliSponsorBlockSegment> {
    val videos = runCatching {
        BILI_SPONSOR_BLOCK_JSON.parseToJsonElement(responseBody).jsonArray
    }.getOrElse {
        return emptyList()
    }
    val segments = mutableListOf<BiliSponsorBlockSegment>()
    videos.forEach { videoElement ->
        val video = videoElement.asObjectOrNull() ?: return@forEach
        if (video.stringValue("videoID") != target.bvid) return@forEach
        val submittedSegments = video["segments"].asArrayOrNull() ?: return@forEach
        submittedSegments.forEach { segmentElement ->
            val segment = segmentElement.asObjectOrNull() ?: return@forEach
            val candidate = segment.toAutoSkipSegmentOrNull(target) ?: return@forEach
            segments += candidate
        }
    }
    return segments.sortedWith(
        compareBy<BiliSponsorBlockSegment> { it.startMs }
            .thenBy { it.endMs }
            .thenBy { it.uuid }
    )
}

private fun JsonObject.toAutoSkipSegmentOrNull(
    target: BiliSponsorBlockTarget
): BiliSponsorBlockSegment? {
    val cid = stringValue("cid")?.toLongOrNull() ?: return null
    if (cid != target.cid) return null

    val category = stringValue("category")?.lowercase() ?: return null
    if (category !in BILI_SPONSOR_BLOCK_AUTO_SKIP_CATEGORIES) return null
    if (stringValue("actionType") != "skip") return null

    val range = this["segment"].asArrayOrNull() ?: return null
    if (range.size != 2) return null
    val startMs = range.getOrNull(0).asMillisecondsOrNull() ?: return null
    val endMs = range.getOrNull(1).asMillisecondsOrNull() ?: return null
    val compatibleEndMs = if (target.durationMs > 0L) {
        endMs.coerceAtMost(target.durationMs)
    } else {
        endMs
    }
    if (compatibleEndMs <= startMs) return null
    if (!isBiliSponsorBlockDurationCompatible(stringValue("videoDuration"), target.durationMs)) {
        return null
    }

    val uuid = stringValue("UUID")?.takeIf { it.isNotBlank() } ?: return null
    return BiliSponsorBlockSegment(
        uuid = uuid,
        category = category,
        startMs = startMs,
        endMs = compatibleEndMs
    )
}

internal fun isBiliSponsorBlockDurationCompatible(
    submittedDurationSeconds: String?,
    targetDurationMs: Long
): Boolean {
    if (targetDurationMs <= 0L) return true
    val submittedDurationMs = submittedDurationSeconds
        ?.toDoubleOrNull()
        ?.toMillisecondsOrNull()
        ?: return true
    if (submittedDurationMs <= 0L) return true
    return abs(submittedDurationMs - targetDurationMs) <= BILI_SPONSOR_BLOCK_DURATION_TOLERANCE_MS
}

private fun kotlinx.serialization.json.JsonElement?.asArrayOrNull(): JsonArray? = runCatching {
    this?.jsonArray
}.getOrNull()

private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? = runCatching {
    jsonObject
}.getOrNull()

private fun JsonObject.stringValue(key: String): String? = this[key].primitiveContentOrNull()

private fun kotlinx.serialization.json.JsonElement?.asMillisecondsOrNull(): Long? {
    val seconds = primitiveContentOrNull()?.toDoubleOrNull() ?: return null
    return seconds.toMillisecondsOrNull()
}

private fun kotlinx.serialization.json.JsonElement?.primitiveContentOrNull(): String? = runCatching {
    this?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun Double.toMillisecondsOrNull(): Long? {
    if (!isFinite() || this < 0.0 || this > Long.MAX_VALUE / 1_000.0) return null
    return (this * 1_000.0).roundToLong()
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

private val BILI_SPONSOR_BLOCK_JSON = Json {
    ignoreUnknownKeys = true
}

private const val HEX_DIGITS = "0123456789abcdef"
