package moe.ouom.neriplayer.core.player.download

import androidx.core.net.toUri
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeWebRemixDirectMissingPoToken
import java.net.URLConnection
import org.json.JSONObject

/**
 * 统一处理各平台来源解析
 *
 * 解析阶段不持有下载 permit，也不修改 staging 文件，避免慢来源把传输槽位长期占住
 */
internal object AudioDownloadSourceResolver {
    private const val TAG = "NERI-Downloader"

    internal suspend fun resolveNetease(
        songId: Long,
        preferredQuality: String
    ): AudioDownloadManager.ResolvedDownloadSource? {
        val raw = AppContainer.neteaseClient.getSongDownloadUrl(
            songId,
            level = preferredQuality
        )
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code") != 200) {
                return tryWeapiFallback(songId, preferredQuality)
            }
            val data = NeteasePlaybackResponseParser.parseDownloadInfo(raw)
                ?: return tryWeapiFallback(songId, preferredQuality)
            val url = data.url
            val type = data.type.orEmpty()
            AudioDownloadManager.ResolvedDownloadSource(
                url = ensureHttps(url),
                mimeType = guessMimeFromUrl(url),
                fileExtensionHint = type.lowercase().ifBlank { extFromUrl(url) },
                contentLength = data.contentLength
            )
        } catch (_: Exception) {
            tryWeapiFallback(songId, preferredQuality)
        }
    }

    private fun bitrateForQuality(level: String): Int = when (level.lowercase()) {
        "standard" -> 128000
        "higher" -> 192000
        "exhigh" -> 320000
        "lossless", "hires", "jyeffect", "sky", "jymaster" -> 1411200
        else -> 320000
    }

    private fun tryWeapiFallback(
        songId: Long,
        level: String
    ): AudioDownloadManager.ResolvedDownloadSource? {
        return try {
            val raw = AppContainer.neteaseClient.getSongUrl(
                songId,
                bitrate = bitrateForQuality(level)
            )
            val data = NeteasePlaybackResponseParser.parseDownloadInfo(raw) ?: return null
            val finalUrl = ensureHttps(data.url)
            AudioDownloadManager.ResolvedDownloadSource(
                url = finalUrl,
                mimeType = guessMimeFromUrl(finalUrl),
                fileExtensionHint = extFromUrl(finalUrl),
                contentLength = data.contentLength
            )
        } catch (_: Exception) {
            null
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
        if (!song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
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
            fileExtensionHint = mimeToExt(chosen.mimeType)
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
