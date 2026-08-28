package moe.ouom.neriplayer.core.download.metadata

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.kyant.taglib.Metadata
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.normalizeManagedDownloadAlbumName
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import org.json.JSONObject
import java.io.File
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
    private const val MAX_EMBEDDED_COVER_BYTES = 8L * 1024L * 1024L
    private val ROLELESS_COVER_PICTURE_EXTENSIONS = setOf(
        "3g2", "m4a", "m4b", "m4p", "m4r", "m4v", "mp4"
    )

    /**
     * TagLib 无法承载标签的容器; YouTube 的 opus 音频落盘为 .webm
     * 属于 Matroska 家族, TagLib 既解析不了也写不进去
     */
    private val TAG_UNSUPPORTED_EXTENSIONS = setOf(
        "webm", "mkv", "mka", "ts", "flv", "m3u8", "m3u"
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

    suspend fun write(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): DownloadedAudioTagWriteOutcome = withContext(Dispatchers.IO) {
        val startedAtNs = System.nanoTime()
        if (!supportsEmbeddedTags(audio.logicalName)) {
            NPLogger.d(TAG, "容器不支持内嵌标签，跳过写入: file=${audio.name}")
            return@withContext DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER
        }
        val descriptor = openWritableDescriptor(context, audio)
            ?: return@withContext DownloadedAudioTagWriteOutcome.FAILED
        descriptor.use { target ->
            // 没有封面侧载时不读取已有图片, 避免 SAF 上载入大封面
            val existingTagMetadata = loadExistingTagMetadata(
                descriptor = target,
                includePictures = shouldLoadEmbeddedPictures(sidecarReferences)
            )
            val existingPropertyMap = existingTagMetadata?.propertyMap
            val metadataReadMs = elapsedMs(startedAtNs)
            val (propertyMap, coverPictures) = coroutineScope {
                val propertyMapDeferred = async {
                    buildPropertyMap(
                        context = context,
                        audio = audio,
                        existingPropertyMap = existingPropertyMap,
                        song = song,
                        sidecarReferences = sidecarReferences,
                        standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
                    )
                }
                val coverPicturesDeferred = async {
                    buildPicturesWithFrontCover(
                        context = context,
                        existingPictures = existingTagMetadata?.pictures ?: emptyArray(),
                        sidecarReferences = sidecarReferences,
                        audioExtension = audio.logicalName.substringAfterLast('.', "")
                    )
                }
                propertyMapDeferred.await() to coverPicturesDeferred.await()
            }
            val preparationMs = elapsedMs(startedAtNs) - metadataReadMs

            val propertyChanged = !propertyMapsEquivalent(existingPropertyMap, propertyMap)
            val audioExtension = audio.logicalName.substringAfterLast('.', "")
            val writeStartedAtNs = System.nanoTime()
            val coverSaved = coverPictures?.let { pictures ->
                runCatching {
                    TagLib.savePictures(target.dup().detachFd(), pictures)
                }.getOrElse {
                    NPLogger.w(TAG, "写入封面标签失败: ${audio.name}, ${it.message}")
                    false
                }
            } ?: true
            val shouldSaveProperties = propertyChanged || shouldRestorePropertyMapAfterCoverWrite(
                audioExtension = audioExtension,
                writesCover = coverPictures != null
            )
            val propertySaved = if (coverSaved && shouldSaveProperties) {
                runCatching {
                    TagLib.savePropertyMap(target.dup().detachFd(), propertyMap)
                }.getOrElse {
                    NPLogger.w(TAG, "写入标签属性失败: ${audio.name}, ${it.message}")
                    false
                }
            } else {
                coverSaved
            }

            val writeMs = elapsedMs(writeStartedAtNs)
            val verifyStartedAtNs = System.nanoTime()
            val metadataVerified = if (propertySaved) {
                canSkipEmbeddedMetadataVerification(
                    existingPropertyMap = existingPropertyMap,
                    propertyChanged = propertyChanged,
                    coverChanged = coverPictures != null,
                    song = song
                ) || verifyRequiredEmbeddedMetadata(target, song)
            } else {
                false
            }
            val verifyMs = elapsedMs(verifyStartedAtNs)
            val successful = propertySaved && coverSaved && metadataVerified
            if (successful) {
                NPLogger.d(
                    TAG,
                    "音频内嵌标签写入完成: file=${audio.name}, propertyChanged=$propertyChanged, coverChanged=${coverPictures != null}, metadataReadMs=$metadataReadMs, preparationMs=$preparationMs, writeMs=$writeMs, verifyMs=$verifyMs, totalMs=${elapsedMs(startedAtNs)}"
                )
                return@use DownloadedAudioTagWriteOutcome.SUCCESS
            }

            // TagLib 连既有标签都读不出来, 说明这个容器它根本不认识, 重试无意义
            if (!propertySaved && existingPropertyMap == null) {
                NPLogger.w(
                    TAG,
                    "TagLib 无法解析该音频容器，跳过内嵌标签: file=${audio.name}"
                )
                return@use DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER
            }

            NPLogger.w(
                TAG,
                "音频内嵌标签写入未完成: file=${audio.name}, propertySaved=$propertySaved, coverSaved=$coverSaved, metadataVerified=$metadataVerified, metadataReadMs=$metadataReadMs, preparationMs=$preparationMs, writeMs=$writeMs, verifyMs=$verifyMs, totalMs=${elapsedMs(startedAtNs)}"
            )
            DownloadedAudioTagWriteOutcome.FAILED
        }
    }

    private suspend fun buildPropertyMap(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        existingPropertyMap: PropertyMap?,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): PropertyMap {
        val propertyMap = copyPropertyMap(existingPropertyMap)
        val audioExtension = audio.logicalName.substringAfterLast('.', "").lowercase()
        val embeddedLyrics = resolveEmbeddedLyrics(
            context = context,
            explicitReferences = listOf(
                sidecarReferences?.lyricReference,
                sidecarReferences?.translatedLyricReference,
                sidecarReferences?.romanizedLyricReference
            ),
            fallbacks = listOf(
                song.matchedLyric ?: song.originalLyric,
                song.matchedTranslatedLyric ?: song.originalTranslatedLyric,
                song.matchedRomanizedLyric ?: song.originalRomanizedLyric
            )
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
        putSingleValue(propertyMap, "ARTIST", song.artist)
        putSingleValue(propertyMap, "ALBUM", normalizeEmbeddedAlbumName(song.album))
        putSingleValue(propertyMap, "ALBUMARTIST", song.artist)
        putSingleValue(propertyMap, "TRACKNUMBER", song.id.takeIf { it > 0L }?.toString())
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
        fallbacks: List<String?>
    ): List<String?> {
        val resolved = readRestorableSidecarLyricsConcurrently(
            references = explicitReferences,
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
            resolved.getOrNull(index) ?: fallbacks.getOrNull(index)
        }
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
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): Boolean {
        return !sidecarReferences?.coverReference.isNullOrBlank()
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
        translatedLyricsMetadataKeys.forEach { key ->
            putSingleValue(propertyMap, key, translatedLyrics)
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
        descriptor: ParcelFileDescriptor,
        song: SongItem
    ): Boolean {
        val propertyMap = loadExistingPropertyMap(descriptor) ?: return false
        return hasRequiredEmbeddedMetadata(propertyMap, song)
    }

    internal fun hasRequiredEmbeddedMetadata(
        propertyMap: PropertyMap,
        song: SongItem
    ): Boolean {
        val expectedTitle = song.displayName().trim()
        val expectedArtist = song.artist.trim()
        return hasExpectedPropertyValue(propertyMap, "TITLE", expectedTitle) &&
            (expectedArtist.isBlank() || hasExpectedPropertyValue(propertyMap, "ARTIST", expectedArtist))
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
    ): Array<Picture>? {
        val coverReference = sidecarReferences?.coverReference ?: return null
        val coverBytes = readReferenceBytes(context, coverReference) ?: return null
        val normalizedCover = LocalMediaSupport.normalizeEmbeddedCoverForContainer(
            sourceBytes = coverBytes,
            sourceMimeType = detectPictureMimeType(coverBytes),
            audioExtension = audioExtension
        ) ?: return null
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
        return updatedPictures.takeUnless {
            coverPictureListsEquivalent(
                left = existingPictures,
                right = updatedPictures,
                audioExtension = audioExtension
            )
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
                localFile.inputStream().use { it.readBytesLimited(MAX_EMBEDDED_COVER_BYTES) }
            }.getOrNull()
        }
        val uri = runCatching { reference.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytesLimited(MAX_EMBEDDED_COVER_BYTES)
            }
        }.getOrNull()
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
