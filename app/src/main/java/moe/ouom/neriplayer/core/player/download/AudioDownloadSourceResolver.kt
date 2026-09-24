package moe.ouom.neriplayer.core.player.download

import androidx.core.net.toUri
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeWebRemixDirectMissingPoToken
import java.io.IOException
import java.net.URLConnection

internal class DownloadSourceUnavailableException(message: String) : IOException(message)

/** 将临时失败交给持久下载队列，保留工作文件和已有恢复预算 */
internal class RetryableDownloadFailureException(
    message: String,
    val networkUnavailable: Boolean,
    cause: Throwable? = null,
    val errorCode: String? = null
) : IOException(message, cause)

/**
 * 统一处理各平台来源解析
 *
 * 解析阶段不持有下载 permit，也不修改 staging 文件，避免慢来源把传输槽位长期占住
 */
internal object AudioDownloadSourceResolver {
    private const val TAG = "NERI-Downloader"

    internal fun isBiliSource(song: SongItem): Boolean = song.identity().album == "bilibili"

    internal sealed interface NeteaseDownloadLookup {
        data class Resolved(
            val source: AudioDownloadManager.ResolvedDownloadSource
        ) : NeteaseDownloadLookup

        data object ExplicitlyUnavailable : NeteaseDownloadLookup
        data object Missing : NeteaseDownloadLookup
    }

    internal suspend fun resolveNetease(
        songId: Long,
        preferredQuality: String
    ): AudioDownloadManager.ResolvedDownloadSource? = resolveNeteaseWithLookups(
        songId = songId,
        preferredQuality = preferredQuality,
        eapiLookup = { id, level ->
            parseNeteaseDownloadLookup(
                AppContainer.neteaseClient.getSongDownloadUrl(id, level = level),
                expectedSongId = id
            )
        },
        weapiLookup = { id, bitrate ->
            parseNeteaseDownloadLookup(
                AppContainer.neteaseClient.getSongUrl(id, bitrate = bitrate),
                expectedSongId = id
            )
        }
    )

    internal suspend fun resolveNeteaseWithLookups(
        songId: Long,
        preferredQuality: String,
        eapiLookup: suspend (Long, String) -> NeteaseDownloadLookup,
        weapiLookup: (Long, Int) -> NeteaseDownloadLookup
    ): AudioDownloadManager.ResolvedDownloadSource? {
        var explicitlyUnavailable = false
        for (level in downloadQualityFallbacks(preferredQuality)) {
            val primary = eapiLookup(songId, level)
            if (primary is NeteaseDownloadLookup.Resolved) {
                logNeteaseQualityFallback(songId, preferredQuality, level)
                return primary.source
            }
            val fallback = weapiLookup(songId, bitrateForQuality(level))
            if (fallback is NeteaseDownloadLookup.Resolved) {
                logNeteaseQualityFallback(songId, preferredQuality, level)
                return fallback.source
            }
            explicitlyUnavailable = explicitlyUnavailable ||
                primary == NeteaseDownloadLookup.ExplicitlyUnavailable ||
                fallback == NeteaseDownloadLookup.ExplicitlyUnavailable
        }
        if (explicitlyUnavailable) {
            throw DownloadSourceUnavailableException(
                "netease download source is explicitly unavailable: songId=$songId"
            )
        }
        return null
    }

    private fun logNeteaseQualityFallback(songId: Long, preferred: String, resolved: String) {
        if (!preferred.equals(resolved, ignoreCase = true)) {
            NPLogger.w(TAG, "网易云下载音质降级: songId=$songId, preferred=$preferred, resolved=$resolved")
        }
    }

    private fun downloadQualityFallbacks(preferredQuality: String): List<String> {
        val preferred = preferredQuality.lowercase()
        val levels = listOf("lossless", "exhigh", "standard")
        if (preferred == "higher") return listOf(preferred, "standard")
        val position = levels.indexOf(preferred)
        return if (position >= 0) levels.drop(position) else
            (listOf(preferred, "lossless") + levels.drop(1)).distinct()
    }

    private fun bitrateForQuality(level: String): Int = when (level.lowercase()) {
        "standard" -> 128000
        "higher" -> 192000
        "exhigh" -> 320000
        "lossless", "hires", "jyeffect", "sky", "jymaster" -> 1411200
        else -> 320000
    }

    internal fun parseNeteaseDownloadLookup(
        rawResponse: String,
        expectedSongId: Long? = null
    ): NeteaseDownloadLookup {
        return when (
            val parsed = NeteasePlaybackResponseParser.parsePlayback(
                rawResponse = rawResponse,
                originalDurationMs = 0L
            )
        ) {
            is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                if (expectedSongId != null && parsed.songId != expectedSongId) {
                    return NeteaseDownloadLookup.Missing
                }
                if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                    return NeteaseDownloadLookup.ExplicitlyUnavailable
                }
                val finalUrl = ensureHttps(parsed.url)
                NeteaseDownloadLookup.Resolved(
                    AudioDownloadManager.ResolvedDownloadSource(
                        url = finalUrl,
                        mimeType = guessMimeFromUrl(finalUrl),
                        fileExtensionHint = parsed.type
                            ?.lowercase()
                            ?.takeIf(String::isNotBlank)
                            ?: extFromUrl(finalUrl),
                        contentLength = parsed.contentLength,
                        durationMs = parsed.durationMs,
                        contentMd5 = parsed.contentMd5
                    )
                )
            }

            is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                if (parsed.reason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION) {
                    NeteaseDownloadLookup.ExplicitlyUnavailable
                } else {
                    NeteaseDownloadLookup.Missing
                }
            }

            NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin ->
                NeteaseDownloadLookup.Missing
        }
    }

    internal suspend fun resolveYouTubeMusic(
        song: SongItem,
        preferredQuality: String,
        forceRefresh: Boolean = false,
        avoidDirect: Boolean = false
    ): AudioDownloadManager.ResolvedDownloadSource? {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
        var directPlayableAudio: YouTubePlayableAudio? = null
        var fallbackPlayableAudio: YouTubePlayableAudio? = null
        val attempts = AudioDownloadTransferPolicy
            .resolveYouTubeDownloadResolveAttempts(forceRefresh)
            .let { list -> if (avoidDirect) list.filterNot { it.requireDirect } else list }
        for (attempt in attempts) {
            val candidate = resolveYouTubeMusicDownloadAudio(
                videoId = videoId,
                attempt = attempt,
                preferredQuality = preferredQuality,
                avoidDirect = avoidDirect
            ) ?: continue
            if (candidate.streamType == YouTubePlayableStreamType.DIRECT) {
                if (avoidDirect) {
                    NPLogger.w(
                        TAG,
                        "直链下载曾 403，改走 HLS：跳过直链候选 " +
                            "videoId=$videoId, mode=${attempt.logLabel}"
                    )
                    continue
                }
                if (isYouTubeWebRemixDirectMissingPoToken(candidate.url)) {
                    NPLogger.w(
                        TAG,
                        "YouTube Music 直链缺少 pot，继续降级: " +
                            "videoId=$videoId, mode=${attempt.logLabel}"
                    )
                    continue
                }
                directPlayableAudio = candidate
                break
            }
            if (!attempt.requireDirect && fallbackPlayableAudio == null) {
                fallbackPlayableAudio = candidate
                break
            }
            NPLogger.w(
                TAG,
                "YouTube Music 来源不是直链，继续降级: " +
                    "videoId=$videoId, mode=${attempt.logLabel}, type=${candidate.streamType}"
            )
        }
        val playableAudio = directPlayableAudio ?: fallbackPlayableAudio ?: return null
        if (directPlayableAudio == null && playableAudio.streamType == YouTubePlayableStreamType.HLS) {
            NPLogger.w(TAG, "YouTube Music 未拿到直链，回退 HLS: videoId=$videoId")
        }
        if (playableAudio.streamType == YouTubePlayableStreamType.HLS) {
            return AudioDownloadManager.ResolvedDownloadSource(
                url = playableAudio.url,
                mimeType = "audio/aac",
                fileExtensionHint = "aac",
                streamType = YouTubePlayableStreamType.HLS,
                contentLength = playableAudio.contentLength
            )
        }
        return AudioDownloadManager.ResolvedDownloadSource(
            url = playableAudio.url,
            mimeType = playableAudio.mimeType ?: guessMimeFromUrl(playableAudio.url),
            fileExtensionHint = extFromUrl(playableAudio.url),
            contentLength = playableAudio.contentLength,
            durationMs = playableAudio.durationMs
        )
    }

    private suspend fun resolveYouTubeMusicDownloadAudio(
        videoId: String,
        attempt: AudioDownloadManager.YouTubeDownloadResolveAttempt,
        preferredQuality: String,
        avoidDirect: Boolean
    ): YouTubePlayableAudio? {
        val startedAtMs = System.currentTimeMillis()
        return try {
            val playableAudio = withTimeoutOrNull(attempt.timeoutMs) {
                val repository = if (attempt.shareInFlight) {
                    AppContainer.youtubeMusicPlaybackRepository
                } else {
                    AppContainer.youtubeMusicDownloadPlaybackRepository
                }
                repository.getBestPlayableAudio(
                    videoId = videoId,
                    preferredQualityOverride = preferredQuality,
                    forceRefresh = attempt.forceRefresh,
                    requireDirect = attempt.requireDirect,
                    preferM4a = true,
                    shareInFlight = attempt.shareInFlight,
                    avoidDirect = avoidDirect
                )
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (playableAudio == null) {
                NPLogger.w(
                    TAG,
                    "YouTube Music 解析超时或未命中: videoId=$videoId, " +
                        "mode=${attempt.logLabel}, elapsedMs=$elapsedMs"
                )
            } else {
                NPLogger.d(
                    TAG,
                    "YouTube Music 解析命中: videoId=$videoId, " +
                        "mode=${attempt.logLabel}, type=${playableAudio.streamType}, " +
                        "elapsedMs=$elapsedMs"
                )
            }
            playableAudio
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) {
                throw error
            }
            NPLogger.w(
                TAG,
                "YouTube Music 解析失败，切换下一策略: videoId=$videoId, " +
                    "mode=${attempt.logLabel}, ${error.javaClass.simpleName} - ${error.message}"
            )
            null
        }
    }

    /** 兼容 facade 的来源解析入口，实际逻辑仍留在 resolver 内 */
    internal suspend fun resolveYouTubeMusicDownloadAudioForManager(
        videoId: String,
        attempt: AudioDownloadManager.YouTubeDownloadResolveAttempt,
        preferredQuality: String,
        avoidDirect: Boolean
    ): YouTubePlayableAudio? = resolveYouTubeMusicDownloadAudio(
        videoId = videoId,
        attempt = attempt,
        preferredQuality = preferredQuality,
        avoidDirect = avoidDirect
    )

    internal suspend fun resolveBili(
        song: SongItem,
        preferredQuality: String
    ): AudioDownloadManager.ResolvedDownloadSource? {
        if (!isBiliSource(song)) {
            return null
        }
        val resolved = resolveBiliSong(song, AppContainer.biliClient) ?: return null
        val chosen: BiliAudioStreamInfo? = AppContainer.biliPlaybackRepository
            .getBestPlayableAudio(
                bvid = resolved.videoInfo.bvid,
                cid = resolved.cid,
                preferredKeyOverride = preferredQuality
            )
        val url = chosen?.url ?: return null
        return AudioDownloadManager.ResolvedDownloadSource(
            url = url,
            mimeType = chosen.mimeType,
            fileExtensionHint = mimeToExt(chosen.mimeType),
            durationMs = resolved.pageInfo?.durationSec?.takeIf { it > 0 }?.times(1_000L)
        )
    }

    internal fun ensureHttps(url: String): String {
        return if (url.startsWith("http://")) {
            url.replaceFirst("http://", "https://")
        } else {
            url
        }
    }

    internal fun mimeToExt(mime: String): String? = when (mime.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/eac3", "audio/e-ac-3" -> "eac3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "video/mp4" -> "mp4"
        "audio/webm" -> "webm"
        "audio/ogg" -> "ogg"
        "audio/mpeg" -> "mp3"
        else -> null
    }

    internal fun guessMimeFromUrl(url: String): String? {
        return runCatching {
            URLConnection.guessContentTypeFromName(url.toUri().lastPathSegment)
        }.getOrNull()
    }

    internal fun extFromUrl(url: String): String? {
        val path = url.toUri().lastPathSegment ?: return null
        val dot = path.lastIndexOf('.')
        if (dot <= 0 || dot == path.length - 1) {
            return null
        }
        return path.substring(dot + 1).lowercase().take(6)
    }
}
