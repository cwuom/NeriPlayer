package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

data class KugouSongSearchResult(
    val id: String,
    val hash: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long
)

data class KugouLyricCandidate(
    val id: String,
    val accessKey: String,
    val durationMs: Long,
    val score: Int
)

data class KugouLyricsPayload(
    val lyrics: String,
    val translatedLyrics: String? = null
)

class KugouLyricsClient(private val okHttpClient: OkHttpClient) {

    suspend fun searchSongs(keyword: String, limit: Int = SEARCH_LIMIT): List<KugouSongSearchResult> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            try {
                val url = "http://mobilecdn.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json")
                    .addQueryParameter("keyword", keyword)
                    .addQueryParameter("page", "1")
                    .addQueryParameter("pagesize", limit.coerceIn(1, SEARCH_LIMIT).toString())
                    .addQueryParameter("showtype", "1")
                    .build()
                val body = executeString(url.toString()) ?: return@withContext emptyList()
                parseKugouSearchResults(body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.d(TAG, "Kugou search failed: ${error.message}")
                emptyList()
            }
        }

    suspend fun getBestLyrics(song: KugouSongSearchResult): String? = getBestLyricPayload(song)?.lyrics

    suspend fun getBestLyricPayload(song: KugouSongSearchResult): KugouLyricsPayload? = withContext(Dispatchers.IO) {
        try {
            val candidates = searchLyricCandidates(song)
                .sortedWith(
                    compareByDescending<KugouLyricCandidate> { it.score }
                        .thenBy { kotlin.math.abs(it.durationMs - song.durationMs) }
                )
            for (candidate in candidates) {
                downloadKrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            for (candidate in candidates) {
                downloadLrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "Kugou lyric lookup failed: ${error.message}")
            null
        }
    }

    private suspend fun searchLyricCandidates(song: KugouSongSearchResult): List<KugouLyricCandidate> {
        val url = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
            .addQueryParameter("keyword", "${song.artist} - ${song.title}")
            .addQueryParameter("duration", song.durationMs.toString())
            .addQueryParameter("hash", song.hash)
            .build()
        val body = executeString(url.toString()) ?: return emptyList()
        return parseKugouLyricCandidates(body)
    }

    private suspend fun downloadKrcLyric(candidate: KugouLyricCandidate): KugouLyricsPayload? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "mobi")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("accesskey", candidate.accessKey)
            .addQueryParameter("fmt", "krc")
            .addQueryParameter("charset", "utf8")
            .build()
        val body = executeString(url.toString()) ?: return null
        return decodeKugouKrcDownloadPayload(body)
    }

    private suspend fun downloadLrcLyric(candidate: KugouLyricCandidate): KugouLyricsPayload? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("accesskey", candidate.accessKey)
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .build()
        val body = executeString(url.toString()) ?: return null
        return decodeKugouLyricDownload(body)?.let(::KugouLyricsPayload)
    }

    private suspend fun executeString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                NPLogger.d(TAG, "Kugou request returned ${response.code} for ${request.url.host}")
                return null
            }
            response.body.string().takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val TAG = "KugouLyricsClient"
        private const val SEARCH_LIMIT = 8
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
    }
}

internal fun parseKugouSearchResults(body: String): List<KugouSongSearchResult> {
    val root = JSONObject(body)
    val items = root.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val hash = item.optString("hash").takeIf { it.isNotBlank() } ?: continue
            val title = item.optString("songname").takeIf { it.isNotBlank() } ?: continue
            val artist = item.optString("singername").takeIf { it.isNotBlank() } ?: continue
            val durationSeconds = item.optLong("duration", 0L)
            add(
                KugouSongSearchResult(
                    id = item.optLong("album_audio_id", item.optLong("audio_id", 0L)).toString(),
                    hash = hash,
                    title = title,
                    artist = artist,
                    album = item.optString("album_name").takeIf { it.isNotBlank() },
                    durationMs = durationSeconds.takeIf { it > 0L }?.times(1_000L) ?: 0L
                )
            )
        }
    }
}

internal fun parseKugouLyricCandidates(body: String): List<KugouLyricCandidate> {
    val root = JSONObject(body)
    val candidates = root.optJSONArray("candidates") ?: return emptyList()
    return buildList {
        for (index in 0 until candidates.length()) {
            val item = candidates.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val accessKey = item.optString("accesskey").takeIf { it.isNotBlank() } ?: continue
            add(
                KugouLyricCandidate(
                    id = id,
                    accessKey = accessKey,
                    durationMs = item.optLong("duration", 0L),
                    score = item.optInt("score", 0)
                )
            )
        }
    }
}

internal fun decodeKugouLyricDownload(body: String): String? {
    val root = JSONObject(body)
    if (root.optInt("status") != 200 && root.optInt("error_code") != 0) return null
    val content = root.optString("content").takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        String(Base64.getDecoder().decode(content), Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal fun decodeKugouKrcDownload(body: String): String? {
    return decodeKugouKrcDownloadPayload(body)?.lyrics
}

internal fun decodeKugouKrcDownloadPayload(body: String): KugouLyricsPayload? {
    val root = JSONObject(body)
    if (root.optInt("status") != 200 && root.optInt("error_code") != 0) return null
    val content = root.optString("content").takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val encryptedBytes = Base64.getDecoder().decode(content)
        val decrypted = decryptKugouKrcPayload(encryptedBytes) ?: return@runCatching null
        val lyric = convertKugouKrcToEditableYrc(decrypted)
            .takeIf(::hasEditableLyricWordTiming)
            ?: return@runCatching null
        KugouLyricsPayload(
            lyrics = lyric,
            translatedLyrics = runCatching {
                extractKugouKrcTranslatedLyrics(decrypted)
            }.getOrNull()
        )
    }.getOrNull()
}

internal fun decryptKugouKrcPayload(encryptedBytes: ByteArray): String? {
    if (encryptedBytes.size <= KUGOU_KRC_HEADER_BYTES) {
        return null
    }
    val payload = encryptedBytes.copyOfRange(KUGOU_KRC_HEADER_BYTES, encryptedBytes.size)
    val decrypted = ByteArray(payload.size) { index ->
        (payload[index].toInt() xor KUGOU_KRC_KEY[index % KUGOU_KRC_KEY.size].toInt()).toByte()
    }
    return InflaterInputStream(ByteArrayInputStream(decrypted)).use { inflater ->
        inflater.readBytes().toString(Charsets.UTF_8)
    }.takeIf { it.isNotBlank() }
}

internal fun convertKugouKrcToEditableYrc(krc: String): String {
    return krc.lineSequence()
        .mapNotNull(::convertKugouKrcLineToEditableYrc)
        .joinToString("\n")
}

private fun convertKugouKrcLineToEditableYrc(rawLine: String): String? {
    val line = rawLine.trim()
    val header = KUGOU_KRC_LINE_REGEX.find(line) ?: return null
    val lineStartMs = header.groupValues[1].toLongOrNull() ?: return null
    val lineDurationMs = header.groupValues[2].toLongOrNull() ?: return null
    val content = header.groupValues[3]
    val segments = KUGOU_KRC_SEGMENT_REGEX.findAll(content).toList()
    if (segments.isEmpty()) {
        return null
    }
    return buildString {
        append("[")
        append(lineStartMs)
        append(",")
        append(lineDurationMs)
        append("]")
        for (segment in segments) {
            val wordOffsetMs = segment.groupValues[1].toLongOrNull() ?: continue
            val wordDurationMs = segment.groupValues[2].toLongOrNull() ?: continue
            append("(")
            append(lineStartMs + wordOffsetMs)
            append(",")
            append(wordDurationMs)
            append(",0)")
            append(segment.groupValues[3])
        }
    }
}

private data class KugouKrcTimedLine(
    val startMs: Long,
    val durationMs: Long
)

private fun extractKugouKrcTranslatedLyrics(krc: String): String? {
    val timedLines = krc.lineSequence()
        .mapNotNull(::parseKugouKrcTimedLine)
        .toList()
    if (timedLines.isEmpty()) {
        return null
    }
    val languageTag = krc.lineSequence()
        .mapNotNull { rawLine ->
            val match = KUGOU_KRC_TAG_REGEX.matchEntire(rawLine.trim()) ?: return@mapNotNull null
            match.takeIf { it.groupValues[1] == "language" }?.groupValues?.get(2)
        }
        .firstOrNull()
        ?: return null
    val languageJson = decodeKugouBase64Utf8(languageTag) ?: return null
    val content = JSONObject(languageJson).optJSONArray("content") ?: return null
    for (index in 0 until content.length()) {
        val language = content.optJSONObject(index) ?: continue
        if (language.optInt("type", -1) != 1) {
            continue
        }
        val lyricContent = language.optJSONArray("lyricContent") ?: continue
        return buildKugouTranslatedLrc(timedLines, lyricContent)
    }
    return null
}

private fun parseKugouKrcTimedLine(rawLine: String): KugouKrcTimedLine? {
    val match = KUGOU_KRC_LINE_REGEX.find(rawLine.trim()) ?: return null
    val startMs = match.groupValues[1].toLongOrNull() ?: return null
    val durationMs = match.groupValues[2].toLongOrNull() ?: return null
    return KugouKrcTimedLine(startMs = startMs, durationMs = durationMs)
}

private fun buildKugouTranslatedLrc(
    timedLines: List<KugouKrcTimedLine>,
    lyricContent: JSONArray
): String? {
    return buildList {
        timedLines.forEachIndexed { index, line ->
            val translation = extractKugouTranslationText(lyricContent.opt(index))
            if (translation.isBlank()) {
                return@forEachIndexed
            }
            add("${formatKugouLrcTimestamp(line.startMs)}$translation")
        }
    }
        .joinToString("\n")
        .takeIf { it.isNotBlank() }
}

private fun extractKugouTranslationText(value: Any?): String {
    return when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                value.optString(index).trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }.joinToString(" ")
        is String -> value.trim()
        else -> ""
    }
}

private fun decodeKugouBase64Utf8(value: String): String? {
    return runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrElse {
        runCatching {
            String(Base64.getMimeDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()
    }
}

private fun formatKugouLrcTimestamp(startMs: Long): String {
    val minutes = startMs / 60_000L
    val seconds = startMs % 60_000L / 1_000L
    val millis = startMs % 1_000L / 10L
    return "[%02d:%02d.%02d]".format(minutes, seconds, millis)
}

private const val KUGOU_KRC_HEADER_BYTES = 4
private val KUGOU_KRC_KEY = byteArrayOf(
    0x40,
    0x47,
    0x61,
    0x77,
    0x5e,
    0x32,
    0x74,
    0x47,
    0x51,
    0x36,
    0x31,
    0x2d,
    0xce.toByte(),
    0xd2.toByte(),
    0x6e,
    0x69
)
private val KUGOU_KRC_TAG_REGEX = Regex("""^\[(\w+):([^\]]*)\]$""")
private val KUGOU_KRC_LINE_REGEX = Regex("""^\[(\d+),\s*(\d+)](.*)$""")
private val KUGOU_KRC_SEGMENT_REGEX = Regex("""<(\d+),\s*(\d+),\s*[-\d]+>([^<\n\r]*)""")
