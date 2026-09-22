package moe.ouom.neriplayer.core.download.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.kyant.taglib.Metadata
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.normalizeManagedDownloadAlbumName
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.EmbeddedMetadataPropertyPlan
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.normalizeWritableComments
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.STANDARD_TRANSLATED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/** 音频内嵌标签写入结果, 用于区分"可重试的失败"与"容器天生不支持标签" */
internal enum class DownloadedAudioTagWriteOutcome {
    SUCCESS,

    /**
     * 容器不支持内嵌标签 (如 WebM/Matroska) ; 重试没有意义
     * 调用方应当保留已下载的音频, 仅依赖 sidecar 封面与歌词文件
     */
    UNSUPPORTED_CONTAINER,

    FAILED
}

internal object DownloadedAudioTagWriter {
    private const val TAG = "DownloadedAudioTagWriter"
    private const val FRONT_COVER_TYPE = "Front Cover"
    private val ROLELESS_COVER_PICTURE_EXTENSIONS = setOf(
        "3g2", "m4a", "m4b", "m4p", "m4r", "m4v", "mp4"
    )

    /**
     * TagLib 无法承载标签的容器; YouTube 的 opus 音频落盘为 .webm
     * 属于 Matroska 家族, TagLib 既解析不了也写不进去
     */
    private val TAG_UNSUPPORTED_EXTENSIONS = setOf(
        "aac", "webm", "mkv", "mka", "ts", "flv", "m3u8", "m3u"
    )
    private val NETEASE_WORD_LINE_REGEX = Regex("""^\[(\d+),\s*\d+]\s*(.*)$""")
    private val NETEASE_WORD_TOKEN_REGEX = Regex("""[\(<]\d+,\s*\d+,\s*-?\d+[\)>]""")
    private val LRC_TIMED_LINE_REGEX = Regex("""^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val LRC_METADATA_LINE_REGEX = Regex("""^\[[A-Za-z][A-Za-z0-9_]*:.*]$""")

    private sealed interface CoverPreparation {
        data object NotRequested : CoverPreparation

        data class Ready(val pictures: Array<Picture>) : CoverPreparation

        data object Unchanged : CoverPreparation

        data class Unavailable(val reason: String) : CoverPreparation
    }

    private fun logWriteFailure(
        stage: String,
        audio: ManagedDownloadStorage.StoredEntry,
        error: Throwable,
        metrics: String? = null
    ) {
        val causes = buildList {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                add(
                    "${current.javaClass.simpleName}:" +
                        (current.message?.take(240) ?: "<no-message>")
                )
                current = current.cause
                depth++
            }
        }.joinToString(" <- ")
        val metricSuffix = metrics?.let { ", $it" }.orEmpty()
        NPLogger.w(
            TAG,
            "音频元数据回写失败: stage=$stage, file=${audio.name}, " +
                "reference=${audio.reference}, sizeBytes=${audio.sizeBytes}, " +
                "causes=$causes$metricSuffix",
            error
        )
    }

    /** 判断该文件名对应的容器能否承载内嵌标签 */
    internal fun supportsEmbeddedTags(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return extension.isNotEmpty() && extension !in TAG_UNSUPPORTED_EXTENSIONS
    }

    /** 通过持久恢复记录和暂存副本写入，避免进程退出截断成品音频 */
    suspend fun write(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): DownloadedAudioTagWriteOutcome {
        if (!supportsEmbeddedTags(audio.logicalName)) {
            NPLogger.d(TAG, "容器不支持内嵌标签，跳过写入: file=${audio.name}")
            return DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER
        }
        val stagedOutcome = tryTransactionalStagedWrite(
            context = context,
            audio = audio,
            song = song,
            sidecarReferences = sidecarReferences,
            standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
        )
        if (stagedOutcome == DownloadedAudioTagWriteOutcome.SUCCESS) {
            NPLogger.i(
                TAG,
                "音频已通过可恢复暂存替换完成元信息写入: file=${audio.name}"
            )
        }
        return stagedOutcome
    }

    /** 对普通文件和只支持顺序写入的 DocumentsProvider 使用同一套可恢复替换事务 */
    private suspend fun tryTransactionalStagedWrite(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): DownloadedAudioTagWriteOutcome = withContext(Dispatchers.IO) {
        val sourceReference = writableDescriptorReference(audio)
            ?: return@withContext DownloadedAudioTagWriteOutcome.FAILED
        if (sidecarReferences?.expectedCover == true && sidecarReferences.coverReference.isNullOrBlank()) {
            NPLogger.w(TAG, "音频封面回写失败: stage=cover_reference, missing expected cover")
            return@withContext DownloadedAudioTagWriteOutcome.FAILED
        }
        val stagedSong = enrichSongWithSidecarLyrics(
            context = context,
            song = song,
            sidecarReferences = sidecarReferences
        ).copy(
            mediaUri = sourceReference,
            localFilePath = null,
            localFileName = audio.logicalName
        )
        val writeLyrics = sidecarReferences?.expectedLyric == true ||
            sidecarReferences?.expectedTranslatedLyric == true ||
            sidecarReferences?.expectedRomanizedLyric == true ||
            listOf(
                stagedSong.matchedLyric,
                stagedSong.matchedTranslatedLyric,
                stagedSong.matchedRomanizedLyric,
                stagedSong.originalLyric,
                stagedSong.originalTranslatedLyric,
                stagedSong.originalRomanizedLyric
            ).any { !it.isNullOrBlank() }
        val audioExtension = audio.logicalName.substringAfterLast('.', "").lowercase()
        val outcome = try {
            LocalMediaSupport.writeEditableMetadata(
                context = context,
                song = stagedSong,
                coverReference = sidecarReferences?.coverReference,
                writeCover = !sidecarReferences?.coverReference.isNullOrBlank() ||
                    sidecarReferences?.expectedCover == true,
                writeLyrics = writeLyrics,
                persistCompanionSidecars = false,
                embeddedPropertyPlanFactory = { existing ->
                    val properties = buildPropertyMap(
                        audio = audio,
                        existingPropertyMap = existing,
                        song = stagedSong.copy(mediaUri = song.mediaUri),
                        standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
                    )
                    EmbeddedMetadataPropertyPlan(properties, requiredEmbeddedPropertyKeys(audioExtension, properties))
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "暂存元信息写入失败: file=${audio.name}, error=${error.message}",
                error
            )
            null
        }
        if (outcome != LocalMediaMetadataWriteOutcome.SUCCESS) {
            return@withContext DownloadedAudioTagWriteOutcome.FAILED
        }
        // 暂存标签读回和目标字节摘要已经同时验证，原目标可能只能顺序读取
        DownloadedAudioTagWriteOutcome.SUCCESS
    }

    /** 暂存替换路径要把本次刚下载的歌词带入 SongItem，避免只写出空歌词标签 */
    private suspend fun enrichSongWithSidecarLyrics(
        context: Context,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): SongItem {
        if (sidecarReferences == null) return song
        val resolved = resolveEmbeddedLyrics(
            context = context,
            explicitReferences = listOf(
                sidecarReferences.lyricReference,
                sidecarReferences.translatedLyricReference,
                sidecarReferences.romanizedLyricReference
            ),
            cachedContents = listOf(
                sidecarReferences.lyricContent,
                sidecarReferences.translatedLyricContent,
                sidecarReferences.romanizedLyricContent
            ),
            fallbacks = listOf(
                song.matchedLyric ?: song.originalLyric,
                song.matchedTranslatedLyric ?: song.originalTranslatedLyric,
                song.matchedRomanizedLyric ?: song.originalRomanizedLyric
            )
        )
        fun prefer(existing: String?, resolvedValue: String?): String? {
            return resolvedValue?.takeIf(String::isNotBlank)
                ?: existing?.takeIf(String::isNotBlank)
        }
        return song.copy(
            matchedLyric = prefer(song.matchedLyric, resolved.getOrNull(0)),
            matchedTranslatedLyric = prefer(
                song.matchedTranslatedLyric,
                resolved.getOrNull(1)
            ),
            matchedRomanizedLyric = prefer(
                song.matchedRomanizedLyric,
                resolved.getOrNull(2)
            ),
            originalLyric = prefer(song.originalLyric, resolved.getOrNull(0)),
            originalTranslatedLyric = prefer(
                song.originalTranslatedLyric,
                resolved.getOrNull(1)
            ),
            originalRomanizedLyric = prefer(
                song.originalRomanizedLyric,
                resolved.getOrNull(2)
            )
        )
    }

    private fun buildPropertyMap(
        audio: ManagedDownloadStorage.StoredEntry,
        existingPropertyMap: PropertyMap?,
        song: SongItem,
        standardizedLyricEmbeddingEnabled: Boolean
    ): PropertyMap {
        val propertyMap = copyPropertyMap(existingPropertyMap)
        normalizeWritableComments(propertyMap)
        val audioExtension = audio.logicalName.substringAfterLast('.', "").lowercase()
        val embeddedLyrics = listOf(
            song.matchedLyric ?: song.originalLyric,
            song.matchedTranslatedLyric ?: song.originalTranslatedLyric,
            song.matchedRomanizedLyric ?: song.originalRomanizedLyric
        )
        val embeddedLyric = normalizeLyricForEmbedding(
            lyric = embeddedLyrics.getOrNull(0),
            enabled = standardizedLyricEmbeddingEnabled
        )
        val embeddedTranslatedLyric = normalizeLyricForEmbedding(
            lyric = embeddedLyrics.getOrNull(1),
            enabled = standardizedLyricEmbeddingEnabled
        )
        val embeddedRomanizedLyric = normalizeLyricForEmbedding(
            lyric = embeddedLyrics.getOrNull(2),
            enabled = standardizedLyricEmbeddingEnabled
        )

        putSingleValue(propertyMap, "TITLE", song.displayName())
        putSingleValue(propertyMap, "ARTIST", song.displayArtist())
        putSingleValue(propertyMap, "ALBUM", normalizeEmbeddedAlbumName(song.album))
        putSingleValue(propertyMap, "ALBUMARTIST", song.displayArtist())
        // 来源平台的 SongItem.id 不是唱片音轨序号，保留容器已有 TRACKNUMBER
        applyEmbeddedLyricValues(
            propertyMap = propertyMap,
            audioExtension = audioExtension,
            lyrics = embeddedLyric,
            translatedLyrics = embeddedTranslatedLyric,
            romanizedLyrics = embeddedRomanizedLyric
        )
        putSingleValue(propertyMap, "NERI_STABLE_KEY", song.stableKey())
        putSingleValue(propertyMap, "NERI_MEDIA_URI", song.mediaUri)
        putSingleValue(propertyMap, "NERI_SOURCE", song.matchedLyricSource?.name)
        if (!propertyMap.containsKey("COMMENT")) {
            putSingleValue(
                propertyMap,
                "COMMENT",
                JSONObject().apply {
                    put("app", "NeriPlayer")
                    put("stableKey", song.stableKey())
                    put("mediaUri", song.mediaUri)
                }.toString()
            )
        }
        return propertyMap
    }

    private suspend fun resolveEmbeddedLyrics(
        context: Context,
        explicitReferences: List<String?>,
        cachedContents: List<String?>,
        fallbacks: List<String?>
    ): List<String?> {
        val referencesToRead = explicitReferences.indices.map { index ->
            explicitReferences.getOrNull(index).takeIf {
                cachedContents.getOrNull(index).isNullOrBlank() &&
                shouldReadEmbeddedLyricReference(
                    reference = it,
                    fallback = fallbacks.getOrNull(index)
                )
            }
        }
        val resolved = readRestorableSidecarLyricsConcurrently(
            references = referencesToRead,
            parallelism = 2
        ) { reference ->
            try {
                ManagedDownloadStorage.readText(context, reference)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        return explicitReferences.indices.map { index ->
            selectEmbeddedLyricContent(
                cachedContent = cachedContents.getOrNull(index),
                resolvedContent = resolved.getOrNull(index),
                fallback = fallbacks.getOrNull(index)
            )
        }
    }

    /** 本次下载刚拿到的歌词应优先于可能滞后的目录快照 */
    internal fun selectEmbeddedLyricContent(
        cachedContent: String?,
        resolvedContent: String?,
        fallback: String?
    ): String? {
        return cachedContent?.takeIf(String::isNotBlank)
            ?: resolvedContent?.takeIf(String::isNotBlank)
            ?: fallback
    }

    internal fun shouldReadEmbeddedLyricReference(
        reference: String?,
        fallback: String?
    ): Boolean {
        return !reference.isNullOrBlank() && fallback.isNullOrBlank()
    }

    internal fun normalizeEmbeddedAlbumName(album: String): String? =
        normalizeManagedDownloadAlbumName(album)

    internal fun normalizeLyricForEmbedding(lyric: String?, enabled: Boolean): String? {
        if (!enabled || lyric.isNullOrBlank()) {
            return lyric
        }
        return convertNeteaseWordLyricToLrc(lyric).takeIf(String::isNotBlank) ?: lyric
    }

    internal fun convertNeteaseWordLyricToLrc(lyric: String): String {
        val output = mutableListOf<String>()
        var convertedLineCount = 0

        lyric.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEach
            }

            val wordLine = NETEASE_WORD_LINE_REGEX.find(line)
            if (wordLine != null) {
                val startMs = wordLine.groupValues[1].toLongOrNull() ?: return@forEach
                val text = NETEASE_WORD_TOKEN_REGEX
                    .replace(wordLine.groupValues[2], "")
                    .trim()
                if (text.isNotBlank()) {
                    output += "${formatLrcTimestamp(startMs)}$text"
                    convertedLineCount++
                }
                return@forEach
            }

            if (LRC_TIMED_LINE_REGEX.containsMatchIn(line) || LRC_METADATA_LINE_REGEX.matches(line)) {
                output += line
                return@forEach
            }

            if (!looksLikeStructuredLyricPayload(line)) {
                output += line
            }
        }

        return if (convertedLineCount > 0) {
            output.joinToString("\n")
        } else {
            lyric
        }
    }

    private fun formatLrcTimestamp(timeMs: Long): String {
        val safeTimeMs = timeMs.coerceAtLeast(0L)
        val totalSeconds = safeTimeMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centiseconds = (safeTimeMs % 1_000L) / 10L
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds)
    }

    private fun looksLikeStructuredLyricPayload(line: String): Boolean {
        return line.startsWith("{") ||
            line.startsWith("[{") ||
            line.startsWith("[\"") ||
            line.contains("\"tx\"") ||
            line.contains("\"t\"")
    }

    internal fun shouldLoadEmbeddedPictures(
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        audioExtension: String? = null
    ): Boolean {
        if (sidecarReferences?.coverReference.isNullOrBlank()) {
            return false
        }
        // 没有 role 的 covr 容器会整体替换图片列表，不必为读取旧图片增加额外开销
        return audioExtension.isNullOrBlank() ||
            !usesRolelessCoverPictures(audioExtension)
    }

    internal fun canSkipEmbeddedMetadataVerification(
        existingPropertyMap: PropertyMap?,
        propertyChanged: Boolean,
        coverChanged: Boolean,
        song: SongItem
    ): Boolean {
        return !propertyChanged &&
            !coverChanged &&
            existingPropertyMap != null &&
            hasRequiredEmbeddedMetadata(existingPropertyMap, song)
    }

    /**
     * 一次解析属性和图片, 避免在同一个 SAF 文件上重复进入 TagLib
     * 解析失败时退回无图片模式, 保持旧容器的可写判断
     */
    private fun loadExistingTagMetadata(
        descriptor: ParcelFileDescriptor,
        includePictures: Boolean
    ): Metadata? {
        val metadata = runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), includePictures)
        }.getOrNull()
        if (metadata != null || !includePictures) {
            return metadata
        }
        return runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), false)
        }.getOrNull()
    }

    private fun loadExistingPropertyMap(descriptor: ParcelFileDescriptor): PropertyMap? {
        return runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap
        }.getOrNull()
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun copyPropertyMap(source: PropertyMap?): PropertyMap {
        val target: PropertyMap = hashMapOf()
        source?.forEach { (key, value) ->
            target[key] = value.copyOf()
        }
        return target
    }

    internal fun applyEmbeddedLyricValues(
        propertyMap: PropertyMap,
        audioExtension: String,
        lyrics: String?,
        translatedLyrics: String?,
        romanizedLyrics: String? = null
    ) {
        val externalLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics)
        standardLyricsMetadataKeys(audioExtension).forEach { key ->
            putSingleValue(propertyMap, key, externalLyrics)
        }
        putSingleValue(propertyMap, NERI_ORIGINAL_LYRICS_METADATA_KEY, lyrics)
        val roundTrippableTranslationKeys = roundTrippableTranslationKeys(audioExtension)
        translatedLyricsMetadataKeys.forEach { key ->
            putSingleValue(
                propertyMap,
                key,
                translatedLyrics.takeIf { key in roundTrippableTranslationKeys }
            )
        }
        putSingleValue(propertyMap, NERI_ROMANIZED_LYRICS_METADATA_KEY, romanizedLyrics)
    }

    private fun propertyMapsEquivalent(
        left: PropertyMap?,
        right: PropertyMap
    ): Boolean {
        if (left == null) {
            return right.isEmpty()
        }
        if (left.size != right.size) {
            return false
        }
        return left.all { (key, leftValue) ->
            val rightValue = right[key] ?: return@all false
            leftValue.contentEquals(rightValue)
        }
    }

    private fun verifyRequiredEmbeddedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        expectedPropertyMap: PropertyMap,
        coverPreparation: CoverPreparation
    ): Boolean {
        val descriptor = openReadableDescriptor(context, audio) ?: return false
        return descriptor.use { target ->
            val metadata = runCatching {
                TagLib.getMetadata(target.dup().detachFd(), false)
            }.getOrNull() ?: return false
            if (!hasExpectedEmbeddedPropertyValues(
                    actual = metadata.propertyMap,
                    expected = expectedPropertyMap,
                    audioExtension = audio.logicalName.substringAfterLast('.', "")
                )
            ) {
                return@use false
            }
            when (coverPreparation) {
                CoverPreparation.NotRequested -> true
                is CoverPreparation.Unavailable -> false
                CoverPreparation.Unchanged -> hasReadableEmbeddedCover(target)
                is CoverPreparation.Ready -> hasExpectedEmbeddedCover(
                    descriptor = target,
                    expectedPictures = coverPreparation.pictures
                )
            }
        }
    }

    private fun hasReadableEmbeddedCover(descriptor: ParcelFileDescriptor): Boolean {
        return runCatching {
            TagLib.getPictures(descriptor.dup().detachFd()).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasExpectedEmbeddedCover(
        descriptor: ParcelFileDescriptor,
        expectedPictures: Array<Picture>
    ): Boolean {
        val expectedFrontCover = expectedPictures.firstOrNull { picture ->
            picture.pictureType == FRONT_COVER_TYPE
        } ?: return false
        return runCatching {
            TagLib.getPictures(descriptor.dup().detachFd()).any { picture ->
                picture.data.contentEquals(expectedFrontCover.data)
            }
        }.getOrDefault(false)
    }

    internal fun hasRequiredEmbeddedMetadata(
        propertyMap: PropertyMap,
        song: SongItem
    ): Boolean {
        val expectedTitle = song.displayName().trim()
        val expectedArtist = song.displayArtist().trim()
        return hasExpectedPropertyValue(propertyMap, "TITLE", expectedTitle) &&
            (expectedArtist.isBlank() || hasExpectedPropertyValue(propertyMap, "ARTIST", expectedArtist))
    }

    /** 标签写入开启时，标题作者之外的预期资产也必须能从音频读回 */
    internal fun hasRequiredEmbeddedMetadata(
        propertyMap: PropertyMap,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        audioExtension: String
    ): Boolean {
        if (!hasRequiredEmbeddedMetadata(propertyMap, song)) return false
        val expectedOriginal = sidecarReferences?.expectedLyric == true ||
            !resolveSongOriginalLyric(song).isNullOrBlank()
        val expectedTranslated = sidecarReferences?.expectedTranslatedLyric == true ||
            !resolveSongTranslatedLyric(song).isNullOrBlank()
        val expectedRomanized = sidecarReferences?.expectedRomanizedLyric == true ||
            !resolveSongRomanizedLyric(song).isNullOrBlank()
        return (!expectedOriginal || hasEmbeddedOriginalLyric(propertyMap, audioExtension)) &&
            (!expectedTranslated || hasEmbeddedTranslatedLyric(propertyMap)) &&
            (!expectedRomanized || hasNonBlankProperty(
                propertyMap,
                NERI_ROMANIZED_LYRICS_METADATA_KEY
            ))
    }

    internal fun hasExpectedEmbeddedPropertyValues(
        actual: PropertyMap,
        expected: PropertyMap,
        audioExtension: String
    ): Boolean {
        return LocalMediaSupport.hasExpectedPropertyMapValues(
            actual = actual,
            expected = expected,
            requiredKeys = requiredEmbeddedPropertyKeys(audioExtension, expected)
        )
    }

    private fun requiredEmbeddedPropertyKeys(
        audioExtension: String,
        expected: PropertyMap
    ): Set<String> = buildSet {
        addAll(
            listOf(
                "TITLE",
                "ARTIST",
                "ALBUM",
                "ALBUMARTIST",
                "TRACKNUMBER",
                "NERI_STABLE_KEY",
                "NERI_MEDIA_URI",
                "NERI_SOURCE",
                "COMMENT",
                NERI_ORIGINAL_LYRICS_METADATA_KEY,
                NERI_ROMANIZED_LYRICS_METADATA_KEY
            )
        )
        addAll(standardLyricsMetadataKeys(audioExtension))
        addAll(roundTrippableTranslationKeys(audioExtension))
        addAll(expected.keys.filter { it.startsWith("COMMENT:", ignoreCase = true) })
    }

    /** MP4 自由格式字段无法稳定往返带冒号的 key，保留两个可读回的翻译字段 */
    private fun roundTrippableTranslationKeys(audioExtension: String): Set<String> {
        val dropsColonKey = usesRolelessCoverPictures(audioExtension)
        return translatedLyricsMetadataKeys
            .filterNot { key ->
                dropsColonKey && key == STANDARD_TRANSLATED_LYRICS_METADATA_KEY
            }
            .toSet()
    }

    private fun hasExtendedEmbeddedMetadataRequirements(
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        coverPreparation: CoverPreparation
    ): Boolean {
        return coverPreparation !is CoverPreparation.NotRequested ||
            sidecarReferences?.expectedCover == true ||
            sidecarReferences?.expectedLyric == true ||
            sidecarReferences?.expectedTranslatedLyric == true ||
            sidecarReferences?.expectedRomanizedLyric == true ||
            !resolveSongOriginalLyric(song).isNullOrBlank() ||
            !resolveSongTranslatedLyric(song).isNullOrBlank() ||
            !resolveSongRomanizedLyric(song).isNullOrBlank()
    }

    private fun resolveSongOriginalLyric(song: SongItem): String? {
        return song.matchedLyric ?: song.originalLyric
    }

    private fun resolveSongTranslatedLyric(song: SongItem): String? {
        return song.matchedTranslatedLyric ?: song.originalTranslatedLyric
    }

    private fun resolveSongRomanizedLyric(song: SongItem): String? {
        return song.matchedRomanizedLyric ?: song.originalRomanizedLyric
    }

    private fun hasEmbeddedOriginalLyric(
        propertyMap: PropertyMap,
        audioExtension: String
    ): Boolean {
        return hasNonBlankProperty(propertyMap, NERI_ORIGINAL_LYRICS_METADATA_KEY) ||
            standardLyricsMetadataKeys(audioExtension).any { key ->
                hasNonBlankProperty(propertyMap, key)
            }
    }

    private fun hasEmbeddedTranslatedLyric(propertyMap: PropertyMap): Boolean {
        return translatedLyricsMetadataKeys.any { key ->
            hasNonBlankProperty(propertyMap, key)
        }
    }

    private fun hasNonBlankProperty(propertyMap: PropertyMap, key: String): Boolean {
        return propertyMap[key]?.any { value -> value.trim().isNotBlank() } == true
    }

    private fun hasExpectedPropertyValue(
        propertyMap: PropertyMap,
        key: String,
        expectedValue: String
    ): Boolean {
        if (expectedValue.isBlank()) {
            return true
        }
        return propertyMap[key]?.any { value -> value.trim() == expectedValue } == true
    }

    private fun buildPicturesWithFrontCover(
        context: Context,
        existingPictures: Array<Picture>,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        audioExtension: String
    ): CoverPreparation {
        val coverReference = sidecarReferences?.coverReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (coverReference == null) {
            return if (sidecarReferences?.expectedCover == true) {
                CoverPreparation.Unavailable("cover sidecar reference is missing")
            } else {
                CoverPreparation.NotRequested
            }
        }
        val coverBytes = readReferenceBytes(context, coverReference) ?: run {
            NPLogger.w(
                TAG,
                "封面侧载不可读，不能确认内嵌结果: " +
                    "stage=cover_read, reference=$coverReference"
            )
            return CoverPreparation.Unavailable("cover sidecar is unreadable")
        }
        if (!isCoverPixelBudgetWithinBytes(coverBytes)) {
            NPLogger.w(
                TAG,
                "封面像素预算超限，跳过内嵌: " +
                    "stage=cover_decode_bounds, extension=$audioExtension, bytes=${coverBytes.size}"
            )
            return CoverPreparation.Unavailable("cover pixel budget exceeded")
        }
        val normalizedCover = LocalMediaSupport.normalizeEmbeddedCoverForContainer(
            sourceBytes = coverBytes,
            sourceMimeType = detectPictureMimeType(coverBytes),
            audioExtension = audioExtension
        ) ?: run {
            NPLogger.w(
                TAG,
                "封面格式不适合当前容器，不能确认内嵌结果: " +
                    "stage=cover_normalize, extension=$audioExtension, bytes=${coverBytes.size}"
            )
            return CoverPreparation.Unavailable("cover format is unsupported")
        }
        val replacementPicture = Picture(
            data = normalizedCover.first,
            description = "",
            pictureType = FRONT_COVER_TYPE,
            mimeType = normalizedCover.second
        )
        val updatedPictures = replaceCoverPictures(
            existingPictures = existingPictures,
            replacementPicture = replacementPicture,
            audioExtension = audioExtension
        )
        return if (coverPictureListsEquivalent(
                left = existingPictures,
                right = updatedPictures,
                audioExtension = audioExtension
            )
        ) {
            CoverPreparation.Unchanged
        } else {
            CoverPreparation.Ready(updatedPictures)
        }
    }

    internal fun usesRolelessCoverPictures(audioExtension: String): Boolean {
        return audioExtension.trim().lowercase(Locale.ROOT) in ROLELESS_COVER_PICTURE_EXTENSIONS
    }

    internal fun shouldRestorePropertyMapAfterCoverWrite(
        audioExtension: String,
        writesCover: Boolean
    ): Boolean {
        return writesCover && usesRolelessCoverPictures(audioExtension)
    }

    internal fun replaceCoverPictures(
        existingPictures: Array<Picture>,
        replacementPicture: Picture,
        audioExtension: String
    ): Array<Picture> {
        if (usesRolelessCoverPictures(audioExtension)) {
            return arrayOf(replacementPicture)
        }
        val remainingPictures = existingPictures.filterNot { it.pictureType == FRONT_COVER_TYPE }
        return (remainingPictures + replacementPicture).toTypedArray()
    }

    private fun coverPictureListsEquivalent(
        left: Array<Picture>,
        right: Array<Picture>,
        audioExtension: String
    ): Boolean {
        if (left.size != right.size) return false
        val rolelessPictureContainer = usesRolelessCoverPictures(audioExtension)
        return left.indices.all { index ->
            val actual = left[index]
            val expected = right[index]
            actual.data.contentEquals(expected.data) && (
                rolelessPictureContainer ||
                    actual.description == expected.description &&
                    actual.pictureType == expected.pictureType &&
                    actual.mimeType == expected.mimeType
                )
        }
    }

    private fun putSingleValue(
        propertyMap: PropertyMap,
        key: String,
        value: String?
    ) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            propertyMap.remove(key)
            return
        }
        propertyMap[key] = arrayOf(normalized)
    }

    private fun openWritableDescriptor(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry
    ): ParcelFileDescriptor? {
        audio.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.let { file ->
                return runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
                }.getOrElse {
                    NPLogger.w(TAG, "打开本地音频文件失败: ${file.absolutePath}, ${it.message}")
                    null
                }
            }

        val writableReference = writableDescriptorReference(audio) ?: return null
        val audioUri = runCatching { writableReference.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(audioUri, "rw")
        }.getOrElse {
            NPLogger.w(TAG, "打开音频 Uri 失败: $audioUri, ${it.message}")
            null
        }
    }

    /** 读回校验只需要只读权限，兼容拒绝 rw 但允许 r 的 DocumentsProvider */
    private fun openReadableDescriptor(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry
    ): ParcelFileDescriptor? {
        audio.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.let { file ->
                return runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }.getOrNull()
            }

        val readableReference = writableDescriptorReference(audio) ?: return null
        val audioUri = runCatching { readableReference.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(audioUri, "r")
        }.getOrElse {
            NPLogger.w(TAG, "只读打开音频 Uri 失败: $audioUri, ${it.message}")
            null
        }
    }

    internal fun writableDescriptorReference(
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        return audio.mediaUri.trim().takeIf(String::isNotBlank)
            ?: audio.reference.trim().takeIf(String::isNotBlank)
    }

    private fun readReferenceBytes(context: Context, reference: String): ByteArray? {
        val localFile = reference.takeIf { it.startsWith("/") }?.let(::File)
        if (localFile != null && localFile.exists()) {
            return runCatching {
                // source 读取上限统一为 16 MiB，容器独立的输出策略在 normalize 中处理
                localFile.inputStream().use { it.readBytesLimited(MAX_SOURCE_COVER_BYTES) }
            }.onFailure {
                NPLogger.w(
                    TAG,
                    "读取本地封面侧载失败: stage=cover_read, reference=$reference",
                    it
                )
            }.getOrNull()
        }
        val uri = runCatching { reference.toUri() }.onFailure {
            NPLogger.w(
                TAG,
                "解析封面侧载引用失败: stage=cover_reference, reference=$reference",
                it
            )
        }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytesLimited(MAX_SOURCE_COVER_BYTES)
            }
        }.onFailure {
            NPLogger.w(
                TAG,
                "读取 SAF 封面侧载失败: stage=cover_read, uri=$uri",
                it
            )
        }.getOrNull()
    }

    private fun isCoverPixelBudgetWithinBytes(bytes: ByteArray): Boolean {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull() ?: return true
        // 非图片内容交给既有格式归一化逻辑处理；只对明确读出的尺寸执行像素上限
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return true
        return isCoverPixelBudgetWithin(bounds.outWidth, bounds.outHeight)
    }

    private fun detectPictureMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            return "image/bmp"
        }
        return null
    }
}
