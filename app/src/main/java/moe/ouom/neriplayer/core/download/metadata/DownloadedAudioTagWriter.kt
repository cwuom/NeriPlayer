package moe.ouom.neriplayer.core.download.metadata

import android.content.Context
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.normalizeManagedDownloadAlbumName
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
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.STANDARD_TRANSLATED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import org.json.JSONObject
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

    internal fun writableDescriptorReference(
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        return audio.mediaUri.trim().takeIf(String::isNotBlank)
            ?: audio.reference.trim().takeIf(String::isNotBlank)
    }

}
