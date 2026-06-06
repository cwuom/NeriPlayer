package moe.ouom.neriplayer.core.api.youtube

import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import org.json.JSONObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class YouTubeMusicPlaybackLocalSmokeTest {

    private data class RequestTrace(
        val order: Int,
        val startedAtMs: Long,
        val durationMs: Long,
        val kind: String,
        val host: String,
        val path: String,
        val clientName: String,
        val code: Int,
        val playabilityStatus: String,
        val playabilityReason: String,
        val responseSummary: String
    ) {
        fun toLogLine(): String {
            val clientSuffix = clientName.takeIf(String::isNotBlank)?.let { " client=$it" }.orEmpty()
            val playabilitySuffix = playabilityStatus.takeIf(String::isNotBlank)?.let { status ->
                val reasonSuffix = playabilityReason.takeIf(String::isNotBlank)
                    ?.let { reason -> " reason=${reason.take(80)}" }
                    .orEmpty()
                " playability=$status$reasonSuffix"
            }.orEmpty()
            val summarySuffix = responseSummary.takeIf(String::isNotBlank)
                ?.let { " summary=${it.take(120)}" }
                .orEmpty()
            return "#$order +${startedAtMs}ms ${kind.uppercase()} ${code} ${durationMs}ms ${host}${path}$clientSuffix$playabilitySuffix$summarySuffix"
        }
    }

    @Test
    fun `local cookie probes player api without ios fallback`() = runBlocking {
        val smokeEnabled = System.getProperty("runYouTubePlaybackSmoke") == "true"
        assumeTrue(
            "Local YouTube playback smoke test disabled. Pass -DrunYouTubePlaybackSmoke=true to enable.",
            smokeEnabled
        )

        val videoId = System.getProperty("youtubeSmokeVideoId")
            .orEmpty()
            .ifBlank { "fbvvS8e1KgI" }
        val forceRefresh = System.getProperty("youtubeSmokeForceRefresh")
            ?.toBooleanStrictOrNull()
            ?: false
        val cookieFile = resolveCookieFile()
        assumeTrue("YouTube cookie file not found.", cookieFile != null)

        val authBundle = YouTubeAuthBundle(
            cookieHeader = cookieFile!!.readText().trim()
        ).normalized()
        assumeTrue("YouTube cookie missing login cookies.", authBundle.hasLoginCookies())

        val testStartedAtMs = System.currentTimeMillis()
        val orderCounter = AtomicInteger(0)
        val traces = CopyOnWriteArrayList<RequestTrace>()
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val requestStartedAtMs = System.currentTimeMillis()
                    val response = chain.proceed(request)
                    traces += buildTrace(
                        request = request,
                        responseBody = response.peekBody(1024L * 1024L).string(),
                        responseContentType = response.header("Content-Type").orEmpty(),
                        code = response.code,
                        order = orderCounter.incrementAndGet(),
                        testStartedAtMs = testStartedAtMs,
                        requestStartedAtMs = requestStartedAtMs,
                        requestFinishedAtMs = System.currentTimeMillis()
                    )
                    response
                }
            )
            .build()

        val repository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val resolveStartedAtMs = System.currentTimeMillis()
        val playableAudio = repository.getBestPlayableAudio(
            videoId = videoId,
            forceRefresh = forceRefresh
        )
        val totalMs = System.currentTimeMillis() - resolveStartedAtMs
        val playerClientIds = traces.mapNotNull { trace ->
            trace.clientName.takeIf(String::isNotBlank)
        }

        println(
            "YOUTUBE_PLAYBACK_SMOKE summary videoId=$videoId forceRefresh=$forceRefresh totalMs=$totalMs " +
                "streamType=${playableAudio?.streamType} host=${playableAudio?.url?.let(::safeHost).orEmpty()}"
        )
        traces.sortedBy(RequestTrace::order).forEach { trace ->
            println("YOUTUBE_PLAYBACK_SMOKE ${trace.toLogLine()}")
        }

        assertTrue(
            "Expected WEB_REMIX to be the first player client. clients=$playerClientIds",
            playerClientIds.firstOrNull() == "67"
        )
        assertFalse(
            "Unexpected IOS fallback request detected. clients=$playerClientIds",
            playerClientIds.contains("5")
        )
        assertTrue(
            "Unexpected player client set. clients=$playerClientIds",
            playerClientIds.all { it == "67" || it == "7" || it == "1" }
        )
        assertTrue(
            "Expected at least one WEB_REMIX player response with OK playability. traces=$traces",
            traces.any { trace ->
                trace.clientName == "67" && trace.playabilityStatus == "OK"
            }
        )
        assertNotNull(
            "Expected smoke test to observe at least one player request.",
            traces.firstOrNull { it.kind == "player" }
        )
    }

    private fun resolveCookieFile(): File? {
        val configured = System.getProperty("youtubeSmokeCookieFile")
            .orEmpty()
            .takeIf(String::isNotBlank)
            ?.let(::File)
        return listOfNotNull(
            configured,
            File(".ck/youtube-cookie.txt"),
            File("../.ck/youtube-cookie.txt"),
            File("E:/AndroidProject/NeriPlayer/.ck/youtube-cookie.txt")
        ).firstOrNull(File::exists)
    }

    private fun buildTrace(
        request: Request,
        responseBody: String,
        responseContentType: String,
        code: Int,
        order: Int,
        testStartedAtMs: Long,
        requestStartedAtMs: Long,
        requestFinishedAtMs: Long
    ): RequestTrace {
        val path = request.url.encodedPath
        val playability = if (path.contains("/youtubei/v1/player")) {
            parsePlayability(responseBody)
        } else {
            "" to ""
        }
        return RequestTrace(
            order = order,
            startedAtMs = requestStartedAtMs - testStartedAtMs,
            durationMs = requestFinishedAtMs - requestStartedAtMs,
            kind = resolveKind(request),
            host = request.url.host,
            path = path,
            clientName = request.header("X-YouTube-Client-Name").orEmpty(),
            code = code,
            playabilityStatus = playability.first,
            playabilityReason = playability.second,
            responseSummary = summarizeResponse(
                request = request,
                responseBody = responseBody,
                responseContentType = responseContentType,
                playabilityStatus = playability.first
            )
        )
    }

    private fun resolveKind(request: Request): String {
        val host = request.url.host
        val path = request.url.encodedPath
        return when {
            host == "music.youtube.com" && path == "/" -> "bootstrap"
            path.contains("/youtubei/v1/player") -> "player"
            host.endsWith("googlevideo.com") && path.contains("/manifest/") -> "manifest"
            path.contains("/base.js") -> "player_js"
            else -> "other"
        }
    }

    private fun parsePlayability(body: String): Pair<String, String> {
        return runCatching {
            val playability = JSONObject(body).optJSONObject("playabilityStatus")
            playability?.optString("status").orEmpty() to playability?.optString("reason").orEmpty()
        }.getOrDefault("" to "")
    }

    private fun summarizeResponse(
        request: Request,
        responseBody: String,
        responseContentType: String,
        playabilityStatus: String
    ): String {
        val path = request.url.encodedPath
        if (!path.contains("/youtubei/v1/player")) {
            return responseContentType
        }
        if (playabilityStatus.isNotBlank()) {
            return responseContentType
        }
        val compactBody = responseBody
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val jsonSummary = runCatching {
            val root = JSONObject(responseBody)
            val keys = buildList {
                val iterator = root.keys()
                while (iterator.hasNext()) {
                    add(iterator.next())
                }
            }.joinToString(",")
            val nestedPlayerResponse = root.optJSONObject("playerResponse")
            val nestedKeys = nestedPlayerResponse?.let { nested ->
                buildList {
                    val iterator = nested.keys()
                    while (iterator.hasNext()) {
                        add(iterator.next())
                    }
                }.joinToString(",")
            }.orEmpty()
            buildString {
                append("keys=")
                append(keys)
                if (nestedKeys.isNotBlank()) {
                    append(" playerResponseKeys=")
                    append(nestedKeys)
                }
            }
        }.getOrNull().orEmpty()
        return buildString {
            append(responseContentType.ifBlank { "unknown" })
            if (jsonSummary.isNotBlank()) {
                append(' ')
                append(jsonSummary)
            }
            if (compactBody.isNotBlank()) {
                append(" body=")
                append(compactBody.take(160))
            }
        }
    }

    private fun safeHost(url: String): String {
        return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    }
}
