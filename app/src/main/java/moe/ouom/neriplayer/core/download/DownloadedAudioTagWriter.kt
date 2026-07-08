package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal object DownloadedAudioTagWriter {
    private const val TAG = "DownloadedAudioTagWriter"
    private const val FRONT_COVER_TYPE = "Front Cover"
    private val NETEASE_WORD_LINE_REGEX = Regex("""^\[(\d+),\s*\d+]\s*(.*)$""")
    private val NETEASE_WORD_TOKEN_REGEX = Regex("""[\(<]\d+,\s*\d+,\s*-?\d+[\)>]""")
    private val LRC_TIMED_LINE_REGEX = Regex("""^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val LRC_METADATA_LINE_REGEX = Regex("""^\[[A-Za-z][A-Za-z0-9_]*:.*]$""")

    suspend fun write(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val descriptor = openWritableDescriptor(context, audio) ?: return@withContext false
        descriptor.use { target ->
            val existingPropertyMap = loadExistingPropertyMap(target)
            val propertyMap = buildPropertyMap(
                context = context,
                audio = audio,
                existingPropertyMap = existingPropertyMap,
                song = song,
                sidecarReferences = sidecarReferences,
                standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
            )
            val coverPicture = buildFrontCoverPicture(
                context = context,
                descriptor = target,
                sidecarReferences = sidecarReferences
            )

            val propertyChanged = !propertyMapsEquivalent(existingPropertyMap, propertyMap)
            val propertySaved = if (propertyChanged) {
                runCatching {
                    TagLib.savePropertyMap(target.dup().detachFd(), propertyMap)
                }.getOrElse {
                    NPLogger.w(TAG, "写入标签属性失败: ${audio.name}, ${it.message}")
                    false
                }
            } else {
                true
            }
            val coverSaved = coverPicture?.let { picture ->
                runCatching {
                    TagLib.savePictures(target.dup().detachFd(), arrayOf(picture))
                }.getOrElse {
                    NPLogger.w(TAG, "写入封面标签失败: ${audio.name}, ${it.message}")
                    false
                }
            } ?: true

            val successful = propertySaved && coverSaved
            if (successful) {
                NPLogger.d(
                    TAG,
                    "音频内嵌标签写入完成: file=${audio.name}, propertyChanged=$propertyChanged, coverChanged=${coverPicture != null}"
                )
            } else {
                NPLogger.w(
                    TAG,
                    "音频内嵌标签写入未完成: file=${audio.name}, propertySaved=$propertySaved, coverSaved=$coverSaved"
                )
            }
            successful
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
        val audioExtension = audio.name.substringAfterLast('.', "").lowercase()
        val embeddedLyric = normalizeLyricForEmbedding(
            lyric = resolveEmbeddedLyric(
                context = context,
                explicitReference = sidecarReferences?.lyricReference,
                fallback = song.matchedLyric ?: song.originalLyric
            ),
            enabled = standardizedLyricEmbeddingEnabled
        )
        val embeddedTranslatedLyric = normalizeLyricForEmbedding(
            lyric = resolveEmbeddedLyric(
                context = context,
                explicitReference = sidecarReferences?.translatedLyricReference,
                fallback = song.matchedTranslatedLyric ?: song.originalTranslatedLyric
            ),
            enabled = standardizedLyricEmbeddingEnabled
        )

        putSingleValue(propertyMap, "TITLE", song.displayName())
        putSingleValue(propertyMap, "ARTIST", song.artist)
        putSingleValue(propertyMap, "ALBUM", normalizeEmbeddedAlbumName(song.album))
        putSingleValue(propertyMap, "ALBUMARTIST", song.artist)
        putSingleValue(propertyMap, "TRACKNUMBER", song.id.takeIf { it > 0L }?.toString())
        putPrimaryLyricValues(propertyMap, audioExtension, embeddedLyric)
        putTranslatedLyricValues(propertyMap, embeddedTranslatedLyric)
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

    private suspend fun resolveEmbeddedLyric(
        context: Context,
        explicitReference: String?,
        fallback: String?
    ): String? {
        explicitReference
            ?.takeIf(String::isNotBlank)
            ?.let { reference ->
                runCatching {
                    ManagedDownloadStorage.readText(context, reference)
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        return fallback?.takeIf { it.isNotBlank() }
    }

    internal fun normalizeEmbeddedAlbumName(album: String): String? {
        val normalized = album.trim()
        if (normalized.isBlank()) {
            return null
        }

        stripSourcePrefix(normalized, PlayerManager.NETEASE_SOURCE_TAG)?.let { return it }
        if (normalized.equals(PlayerManager.NETEASE_SOURCE_TAG, ignoreCase = true)) {
            return null
        }
        if (normalized.equals(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true) ||
            normalized.startsWith("${PlayerManager.BILI_SOURCE_TAG}|", ignoreCase = true)
        ) {
            return null
        }

        return normalized
    }

    private fun stripSourcePrefix(value: String, prefix: String): String? {
        if (!value.startsWith(prefix, ignoreCase = true)) {
            return null
        }
        return value.substring(prefix.length).trim().takeIf(String::isNotBlank)
    }

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

    private fun loadExistingPropertyMap(descriptor: ParcelFileDescriptor): PropertyMap? {
        return runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap
        }.getOrNull()
    }

    private fun copyPropertyMap(source: PropertyMap?): PropertyMap {
        val target: PropertyMap = hashMapOf()
        source?.forEach { (key, value) ->
            target[key] = value.copyOf()
        }
        return target
    }

    private fun putPrimaryLyricValues(
        propertyMap: PropertyMap,
        audioExtension: String,
        lyric: String?
    ) {
        putSingleValue(propertyMap, "LYRICS", lyric)
        when (audioExtension) {
            "mp3" -> putSingleValue(propertyMap, "UNSYNCEDLYRICS", lyric)
            "m4a", "mp4", "aac" -> putSingleValue(propertyMap, "DESCRIPTION", lyric)
        }
    }

    private fun putTranslatedLyricValues(
        propertyMap: PropertyMap,
        translatedLyric: String?
    ) {
        putSingleValue(propertyMap, "LYRICS_TRANSLATED", translatedLyric)
        putSingleValue(propertyMap, "NERI_LYRICS_TRANSLATED", translatedLyric)
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

    private fun buildFrontCoverPicture(
        context: Context,
        descriptor: ParcelFileDescriptor,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): Picture? {
        val coverReference = sidecarReferences?.coverReference ?: return null
        val coverBytes = readReferenceBytes(context, coverReference) ?: return null
        val mimeType = detectPictureMimeType(coverBytes)
        val existingPictures = runCatching {
            TagLib.getPictures(descriptor.dup().detachFd())
        }.getOrNull().orEmpty()
        if (existingPictures.any { it.pictureType == FRONT_COVER_TYPE && it.data.contentEquals(coverBytes) }) {
            return null
        }
        val remainingPictures = existingPictures.filterNot { it.pictureType == FRONT_COVER_TYPE }
        return Picture(
            data = coverBytes,
            description = "",
            pictureType = FRONT_COVER_TYPE,
            mimeType = mimeType
        ).let { picture ->
            if (remainingPictures.isEmpty()) {
                picture
            } else {
                // savePictures 由调用方统一覆盖写入，这里只返回首图
                picture
            }
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

        val audioUri = runCatching { audio.playbackUri.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(audioUri, "rw")
        }.getOrElse {
            NPLogger.w(TAG, "打开音频 Uri 失败: $audioUri, ${it.message}")
            null
        }
    }

    private fun readReferenceBytes(context: Context, reference: String): ByteArray? {
        val localFile = reference.takeIf { it.startsWith("/") }?.let(::File)
        if (localFile != null && localFile.exists()) {
            return runCatching { localFile.readBytes() }.getOrNull()
        }
        val uri = runCatching { reference.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun detectPictureMimeType(bytes: ByteArray): String {
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
        return "image/jpeg"
    }
}
