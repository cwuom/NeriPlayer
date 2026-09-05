package moe.ouom.neriplayer.core.player.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.util.io.readBytesLimited
import org.json.JSONObject

/**
 * 负责歌词来源选择和 sidecar 写入
 *
 * 核心音频提交与歌词增强解耦，歌词失败只保留已完成的部分，不会回滚正式音频
 */
internal object AudioDownloadLyricsCoordinator {
    private const val TAG = "NERI-Downloader"
    private const val MAX_INLINE_SIDECAR_LYRIC_BYTES = 512L * 1024L

    private data class DownloadedLyrics(
        val lyricText: String? = null,
        val translatedText: String? = null,
        val romanizedText: String? = null
    )

    internal suspend fun download(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        serializeWrites: Boolean = true,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true,
        operationId: String? = null,
        ensureNotCancelled: suspend (
            songKey: String,
            stage: String,
            batchSessionId: Long?,
            attemptId: Long?,
            requireActiveAttempt: Boolean
        ) -> Unit,
        rememberPartial: (
            songKey: String,
            operationId: String?,
            sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences
        ) -> Unit
    ): AudioDownloadManager.DownloadedSidecarReferences = withContext(Dispatchers.IO) {
        var lyricReference: String? = null
        var translatedLyricReference: String? = null
        var romanizedLyricReference: String? = null
        var expectedLyric = false
        var expectedTranslatedLyric = false
        var expectedRomanizedLyric = false
        var lyricText: String? = null
        var translatedText: String? = null
        var romanizedText: String? = null
        try {
            ensureNotCancelled(
                songKey,
                "lyrics_prepare",
                batchSessionId,
                attemptId,
                requireActiveAttempt
            )
            lyricText = resolveLocalLyric(song.matchedLyric)
            translatedText = resolveLocalLyric(song.matchedTranslatedLyric)
            val shouldFetchPrimaryLyric = shouldFetchRemoteLyric(song.matchedLyric)
            val shouldFetchTranslatedLyric =
                shouldFetchRemoteLyric(song.matchedTranslatedLyric)
            val shouldFetchRomanizedLyric = shouldFetchRomanizedLyric(
                shouldFetchPrimaryLyric,
                shouldFetchTranslatedLyric
            )
            if (lyricText != null) {
                NPLogger.d(TAG, context.getString(R.string.download_lyrics_matched, song.name))
            }
            val isYouTubeMusic = isYouTubeMusicSong(song)
            val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
            when {
                isYouTubeMusic -> {
                    if (lyricText == null && shouldFetchPrimaryLyric) {
                        lyricText = downloadYouTubeMusicLyrics(song)
                    }
                }
                isBili -> Unit
                else -> {
                    val downloaded = downloadNeteaseLyrics(
                        song = song,
                        shouldFetchPrimaryLyric = shouldFetchPrimaryLyric && lyricText == null,
                        shouldFetchTranslatedLyric =
                            shouldFetchTranslatedLyric && translatedText == null,
                        shouldFetchRomanizedLyric =
                            shouldFetchRomanizedLyric && romanizedText == null
                    )
                    if (lyricText == null && shouldFetchPrimaryLyric) {
                        lyricText = downloaded.lyricText
                    }
                    if (translatedText == null && shouldFetchTranslatedLyric) {
                        translatedText = downloaded.translatedText
                    }
                    if (romanizedText == null && shouldFetchRomanizedLyric) {
                        romanizedText = downloaded.romanizedText
                    }
                }
            }
            ensureNotCancelled(
                songKey,
                "lyrics_resolved",
                batchSessionId,
                attemptId,
                requireActiveAttempt
            )
            expectedLyric = !lyricText.isNullOrBlank()
            expectedTranslatedLyric = !translatedText.isNullOrBlank()
            expectedRomanizedLyric = !romanizedText.isNullOrBlank()

            suspend fun writePrimaryLyric(): String? {
                val lyric = lyricText?.takeIf(String::isNotBlank) ?: return null
                val reference = writeManagedLyrics(context, song, baseName, lyric, translated = false)
                reference?.let { storedReference ->
                    rememberPartial(
                        songKey,
                        operationId,
                        AudioDownloadManager.DownloadedSidecarReferences(
                            lyricReference = storedReference,
                            createdLyric = true,
                            lyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(TAG, "歌词写入完成: song=${song.name}, reference=$storedReference")
                }
                return reference
            }

            suspend fun writeTranslatedLyric(): String? {
                val lyric = translatedText?.takeIf(String::isNotBlank) ?: return null
                val reference = writeManagedLyrics(context, song, baseName, lyric, translated = true)
                reference?.let { storedReference ->
                    rememberPartial(
                        songKey,
                        operationId,
                        AudioDownloadManager.DownloadedSidecarReferences(
                            translatedLyricReference = storedReference,
                            createdTranslatedLyric = true,
                            translatedLyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(
                        TAG,
                        "翻译歌词写入完成: song=${song.name}, reference=$storedReference"
                    )
                }
                return reference
            }

            suspend fun writeRomanizedLyric(): String? {
                val lyric = romanizedText?.takeIf(String::isNotBlank) ?: return null
                ensureNotCancelled(
                    songKey,
                    "lyrics_romanized_write",
                    batchSessionId,
                    attemptId,
                    requireActiveAttempt
                )
                val reference = ManagedDownloadStorage.writeRomanizedLyrics(
                    context = context,
                    songId = song.id,
                    baseName = baseName,
                    content = lyric
                )
                reference?.let { storedReference ->
                    rememberPartial(
                        songKey,
                        operationId,
                        AudioDownloadManager.DownloadedSidecarReferences(
                            romanizedLyricReference = storedReference,
                            createdRomanizedLyric = true,
                            romanizedLyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(
                        TAG,
                        "音译歌词写入完成: song=${song.name}, reference=$storedReference"
                    )
                }
                return reference
            }

            if (serializeWrites) {
                lyricReference = writePrimaryLyric()
                ensureNotCancelled(
                    songKey,
                    "lyrics_primary_written",
                    batchSessionId,
                    attemptId,
                    requireActiveAttempt
                )
                translatedLyricReference = writeTranslatedLyric()
                romanizedLyricReference = writeRomanizedLyric()
            } else {
                coroutineScope {
                    val primaryJob = async(Dispatchers.IO) { writePrimaryLyric() }
                    val translatedJob = async(Dispatchers.IO) { writeTranslatedLyric() }
                    val romanizedJob = async(Dispatchers.IO) { writeRomanizedLyric() }
                    lyricReference = primaryJob.await()
                    translatedLyricReference = translatedJob.await()
                    romanizedLyricReference = romanizedJob.await()
                }
            }
            ensureNotCancelled(
                songKey,
                "lyrics_primary_written",
                batchSessionId,
                attemptId,
                requireActiveAttempt
            )
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(TAG, "歌词整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "歌词下载失败: ${song.name} - ${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }
        AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = lyricReference,
            translatedLyricReference = translatedLyricReference,
            romanizedLyricReference = romanizedLyricReference,
            expectedLyric = expectedLyric,
            expectedTranslatedLyric = expectedTranslatedLyric,
            expectedRomanizedLyric = expectedRomanizedLyric,
            createdLyric = !lyricReference.isNullOrBlank(),
            createdTranslatedLyric = !translatedLyricReference.isNullOrBlank(),
            createdRomanizedLyric = !romanizedLyricReference.isNullOrBlank(),
            lyricContent = lyricReference?.let { inlineLyricContent(lyricText) },
            translatedLyricContent = translatedLyricReference?.let {
                inlineLyricContent(translatedText)
            },
            romanizedLyricContent = romanizedLyricReference?.let {
                inlineLyricContent(romanizedText)
            }
        )
    }

    internal fun resolveLocalLyric(rawLyric: String?): String? {
        return rawLyric?.takeIf(String::isNotBlank)
    }

    internal fun shouldFetchRemoteLyric(rawLyric: String?): Boolean {
        return rawLyric == null
    }

    internal fun shouldFetchRomanizedLyric(
        shouldFetchPrimaryLyric: Boolean,
        shouldFetchTranslatedLyric: Boolean
    ): Boolean {
        return shouldFetchPrimaryLyric || shouldFetchTranslatedLyric
    }

    private fun inlineLyricContent(content: String?): String? {
        val normalized = content?.takeIf(String::isNotBlank) ?: return null
        return normalized.takeIf {
            it.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_INLINE_SIDECAR_LYRIC_BYTES
        }
    }

    /** 从 LRCLIB 获取歌词，失败后回退 YouTube Music API */
    private suspend fun downloadYouTubeMusicLyrics(song: SongItem): String? {
        if (!shouldFetchRemoteLyric(song.matchedLyric)) {
            return null
        }
        return try {
            val durationSec = song.durationMs / 1_000L
            val lrcLibResult = runCatching {
                AppContainer.lrcLibClient.getLyrics(
                    trackName = song.name,
                    artistName = song.artist,
                    durationSeconds = durationSec
                ) ?: AppContainer.lrcLibClient.searchLyrics(
                    trackName = song.name,
                    artistName = song.artist,
                    durationSeconds = durationSec
                )
            }.getOrNull()
            val syncedLyrics = lrcLibResult?.syncedLyrics?.takeIf(String::isNotBlank)
            val plainLyrics = lrcLibResult?.plainLyrics?.takeIf(String::isNotBlank)
            when {
                syncedLyrics != null -> {
                    NPLogger.d(TAG, "LRCLIB 同步歌词保存: ${song.name}")
                    syncedLyrics
                }
                plainLyrics != null -> {
                    NPLogger.d(TAG, "LRCLIB 纯文本歌词保存: ${song.name}")
                    plainLyrics
                }
                else -> {
                    val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
                    val result = AppContainer.youtubeMusicClient.getLyrics(videoId)
                    val text = result?.lyrics?.takeIf(String::isNotBlank) ?: return null
                    NPLogger.d(TAG, "YouTube Music API 歌词保存: ${song.name}")
                    text
                }
            }
        } catch (error: Exception) {
            NPLogger.w(TAG, "YouTube Music 歌词下载失败: ${song.name} - ${error.message}")
            null
        }
    }

    /** 从网易云 API 获取主歌词、翻译歌词和音译歌词 */
    private fun downloadNeteaseLyrics(
        song: SongItem,
        shouldFetchPrimaryLyric: Boolean,
        shouldFetchTranslatedLyric: Boolean,
        shouldFetchRomanizedLyric: Boolean
    ): DownloadedLyrics {
        if (!shouldFetchPrimaryLyric && !shouldFetchTranslatedLyric && !shouldFetchRomanizedLyric) {
            return DownloadedLyrics()
        }
        return try {
            val root = JSONObject(AppContainer.neteaseClient.getLyricNew(song.id))
            if (root.optInt("code") != 200) {
                return DownloadedLyrics()
            }
            val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            val translated = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            val romanized = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
            val preferred = if (shouldFetchPrimaryLyric) {
                yrc.takeIf(String::isNotBlank) ?: lrc.takeIf(String::isNotBlank)
            } else {
                null
            }
            if (shouldFetchPrimaryLyric && yrc.isNotBlank()) {
                NPLogger.d(TAG, "从 API 获取逐字歌词保存: ${song.name}")
            }
            if (shouldFetchPrimaryLyric && lrc.isNotBlank()) {
                NPLogger.d(TAG, "从 API 获取歌词保存: ${song.name}")
            }
            if (shouldFetchTranslatedLyric && translated.isNotBlank()) {
                NPLogger.d(TAG, "从 API 获取翻译歌词保存: ${song.name}")
            }
            DownloadedLyrics(
                lyricText = preferred,
                translatedText = translated.takeIf {
                    shouldFetchTranslatedLyric && it.isNotBlank()
                },
                romanizedText = romanized.takeIf {
                    shouldFetchRomanizedLyric && it.isNotBlank()
                }
            )
        } catch (error: Exception) {
            NPLogger.w(TAG, "网易云歌词下载失败: ${song.name} - ${error.message}")
            DownloadedLyrics()
        }
    }

    private fun writeManagedLyrics(
        context: Context,
        song: SongItem,
        baseName: String,
        content: String,
        translated: Boolean
    ): String? {
        return ManagedDownloadStorage.writeLyrics(
            context = context,
            songId = song.id,
            baseName = baseName,
            content = content,
            translated = translated
        )
    }
}
