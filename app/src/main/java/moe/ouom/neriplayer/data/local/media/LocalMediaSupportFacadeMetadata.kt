package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import androidx.core.net.toUri

internal fun LocalMediaSupport.toSongItemImpl(details: LocalMediaDetails): SongItem {
    val stableSource = details.filePath?.takeIf { it.isNotBlank() } ?: details.sourceUri.toString()
    val playbackSource = preferredLocalMediaReference(
        localFilePath = details.filePath,
        mediaUri = details.sourceUri.toString()
    ) ?: stableSource
    val stableId = computeStableSongId(stableSource)
    return SongItem(
        id = stableId,
        name = details.title,
        artist = details.artist,
        album = normalizeLocalAlbumIdentity(details.album, details.usesFallbackAlbum),
        albumId = 0L,
        durationMs = details.durationMs,
        coverUrl = details.coverUri,
        mediaUri = playbackSource,
        matchedLyric = details.lyricContent,
        matchedTranslatedLyric = details.translatedLyricContent,
        matchedRomanizedLyric = details.romanizedLyricContent,
        originalLyric = details.lyricContent,
        originalTranslatedLyric = details.translatedLyricContent,
        originalRomanizedLyric = details.romanizedLyricContent,
        originalName = details.originalTitle ?: details.title,
        originalArtist = details.originalArtist ?: details.artist,
        originalCoverUrl = details.coverUri,
        localFileName = details.displayName,
        localFilePath = details.filePath,
        channelId = "local",
        audioId = stableId.toString(),
        sourceStableKey = details.sourceStableKey
    )
}

internal suspend fun LocalMediaSupport.shareSongFileImpl(context: Context, song: SongItem): Boolean {
    val uri = song.toShareableLocalUri(context) ?: return false
    val shareLabel = song.localFileName
        ?.takeIf { it.isNotBlank() }
        ?: song.localFilePath?.let(::File)?.name
        ?: song.name
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = when {
            song.localMediaUri()?.scheme.equals("content", ignoreCase = true) -> {
                context.contentResolver.getType(uri) ?: "audio/*"
            }
            else -> "audio/*"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, shareLabel)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newUri(context.contentResolver, shareLabel, uri)
    }
    return withContext(Dispatchers.Main.immediate) {
        context.startActivity(
            Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }
}

internal fun LocalMediaSupport.prepareShareableContentFileImpl(
    context: Context,
    sourceUri: Uri,
    suggestedName: String
): File? {
    val shareDir = File(context.cacheDir, SHARED_LOCAL_MEDIA_DIR).apply { mkdirs() }
    val extension = suggestedName.substringAfterLast('.', "")
        .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        ?.let { ".${it.lowercase()}" }
        .orEmpty()
    val target = File(
        shareDir,
        "content-${stableKey(sourceUri.toString())}$extension"
    )
    val partial = File(shareDir, ".${target.name}.partial")
    partial.delete()
    return runCatching {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Unable to open content URI for sharing: $sourceUri")
        input.use { source ->
            partial.outputStream().use { output ->
                source.copyTo(output)
            }
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Unable to replace staged share file: ${target.name}")
        }
        if (!partial.renameTo(target)) {
            throw IOException("Unable to commit staged share file: ${target.name}")
        }
        target
    }.onFailure { error ->
        partial.delete()
        NPLogger.w(
            LOCAL_MEDIA_SHARE_TAG,
            "Failed to stage content URI for sharing: $sourceUri: ${error.message}"
        )
    }.getOrNull()
}

internal fun LocalMediaSupport.prepareShareableFileInDirectoryImpl(sourceFile: File, shareDir: File): File {
    require(sourceFile.exists()) { "Source file does not exist: ${sourceFile.absolutePath}" }
    require(sourceFile.isFile) { "Source file is not a regular file: ${sourceFile.absolutePath}" }
    shareDir.mkdirs()
    if (isFileInsideDirectory(sourceFile, shareDir)) {
        return sourceFile
    }
    val stagedFile = File(shareDir, shareableStageFileName(sourceFile))
    if (shouldRestageShareCopy(stagedFile, sourceFile)) {
        sourceFile.inputStream().use { input ->
            stagedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        stagedFile.setLastModified(sourceFile.lastModified())
    }
    return stagedFile
}

internal fun LocalMediaSupport.readTextContentImpl(context: Context, reference: String): String? {
    val bytes = when {
        reference.startsWith("/") -> try {
            readLimitedTextFile(File(reference))
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "read bytes failed for $reference: ${error.message}")
            null
        }
        else -> try {
            context.contentResolver.openInputStream(reference.toUri())
                ?.use(::readLimitedTextStream)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "read stream failed for $reference: ${error.message}")
            null
        }
    } ?: return null

    return decodeTextBytes(bytes)
}

internal fun LocalMediaSupport.readTextFileImpl(file: File): String? {
    val bytes = runCatching { readLimitedTextFile(file) }
        .onFailure { NPLogger.w(TAG, "read bytes failed for ${file.absolutePath}: ${it.message}") }
        .getOrNull()
        ?: return null

    return decodeTextBytes(bytes)
}

internal fun LocalMediaSupport.readLocalMetadataSidecarFastImpl(
    context: Context,
    song: SongItem,
    metadataReference: String? = null
): LocalMetadataSidecar? {
    return try {
        val explicitReference = (
            metadataReference
                ?.trim()
                ?.takeIf(String::isNotBlank)
        )
        if (explicitReference != null && !isMediaStoreSidecarReference(explicitReference)) {
            readTextContent(context, explicitReference)
                ?.let { raw -> parseLocalMetadataSidecar(explicitReference, raw) }
                ?.let { return it }
        }

        val sourceUri = song.localMediaUri()
        val file = song.localFilePath
            ?.takeIf { it.isNotBlank() && !it.startsWith("content://", ignoreCase = true) }
            ?.let(::File)
            ?.takeIf(File::isFile)
        if (file != null) {
            val metadataFile = File(
                file.parentFile ?: return null,
                file.name + LOCAL_METADATA_SUFFIX
            )
            val reference = metadataFile
                .takeIf { shouldProbeAbsoluteMetadataSidecar(sourceUri, it) }
                ?.absolutePath
            if (reference != null) {
                readTextContent(context, reference)
                    ?.let { raw -> parseLocalMetadataSidecar(reference, raw) }
                    ?.let { return it }
            }
        }

        val resolvedSourceUri = sourceUri ?: return null
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = resolvedSourceUri,
                allowDescriptorFallback = false
            )
        }.getOrNull() ?: return null
        readLocalMetadataSidecar(
            context = context,
            sourceUri = resolvedSourceUri,
            file = resolved.file,
            displayName = resolved.displayName
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        invalidateSafReadCaches()
        NPLogger.w(
            TAG,
            "SAF 本地 metadata sidecar 权限不可用，降级为空: " +
                "song=${song.songStableKey()}, message=${error.message}"
        )
        null
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "本地 metadata sidecar 读取失败，降级为空: " +
                "song=${song.songStableKey()}, " +
                "type=${error::class.simpleName}, message=${error.message}"
        )
        null
    }
}

internal fun LocalMediaSupport.shouldProbeAbsoluteMetadataSidecarImpl(
    sourceUri: Uri?,
    metadataFile: File
): Boolean {
    if (sourceUri?.let(::isExternalStorageDocumentUri) == true) return false
    if (!isReadableLocalFile(metadataFile)) return false
    if (sourceUri?.let(::isMediaStoreUri) != true) return true
    val legacyRoot = LEGACY_DOWNLOAD_ROOT.trimEnd(File.separatorChar)
    val path = metadataFile.absolutePath
    return path != legacyRoot && !path.startsWith(legacyRoot + File.separator)
}

internal fun LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl(
    sourceReference: String?,
    file: File?
): Boolean {
    val normalized = sourceReference?.trim()?.lowercase(Locale.ROOT) ?: return true
    val isMediaStoreReference = normalized.startsWith("content://media/") ||
        normalized.startsWith("content://com.android.providers.media.documents/")
    if (!isMediaStoreReference) return true
    return file?.let(::isReadableLocalFile) == true
}

internal fun LocalMediaSupport.parseLocalMetadataSidecarImpl(
    reference: String,
    raw: String
): LocalMetadataSidecar? {
    return runCatching {
        val root = JSONObject(raw)
        LocalMetadataSidecar(
            reference = reference,
            sourceModifiedAtMs = root.optLong("sourceModifiedAtMs").takeIf { it > 0L },
            name = root.optPresentLocalMetadataString("name"),
            artist = root.optPresentLocalMetadataString("artist"),
            album = root.optPresentLocalMetadataString("album")
                ?: root.optPresentLocalMetadataString("identityAlbum"),
            customName = root.optPresentLocalMetadataString("customName"),
            customArtist = root.optPresentLocalMetadataString("customArtist"),
            originalName = root.optPresentLocalMetadataString("originalName"),
            originalArtist = root.optPresentLocalMetadataString("originalArtist"),
            stableKey = root.optPresentLocalMetadataString("stableKey"),
            songId = root.optLong("songId").takeIf { root.has("songId") && it != 0L },
            channelId = root.optPresentLocalMetadataString("channelId"),
            audioId = root.optPresentLocalMetadataString("audioId"),
            subAudioId = root.optPresentLocalMetadataString("subAudioId"),
            playlistContextId = root.optPresentLocalMetadataString("playlistContextId"),
            coverPath = root.optPresentLocalMetadataString("coverPath"),
            coverUrl = root.optPresentLocalMetadataString("coverUrl"),
            originalCoverUrl = root.optPresentLocalMetadataString("originalCoverUrl"),
            customCoverUrl = root.optPresentLocalMetadataString("customCoverUrl"),
            durationMs = root.optLong("durationMs").coerceAtLeast(0L),
            hasLyricOverride = root.has("matchedLyric") || root.has("originalLyric"),
            hasTranslatedLyricOverride = root.has("matchedTranslatedLyric") ||
                root.has("originalTranslatedLyric"),
            hasRomanizedLyricOverride = root.has("matchedRomanizedLyric") ||
                root.has("originalRomanizedLyric"),
            matchedLyric = root.optPresentLocalMetadataString("matchedLyric"),
            matchedTranslatedLyric = root.optPresentLocalMetadataString(
                "matchedTranslatedLyric"
            ),
            originalLyric = root.optPresentLocalMetadataString("originalLyric"),
            originalTranslatedLyric = root.optPresentLocalMetadataString(
                "originalTranslatedLyric"
            ),
            matchedRomanizedLyric = root.optPresentLocalMetadataString(
                "matchedRomanizedLyric"
            ),
            originalRomanizedLyric = root.optPresentLocalMetadataString(
                "originalRomanizedLyric"
            )
        )
    }.onFailure {
        NPLogger.w(TAG, "parse local metadata sidecar failed for $reference: ${it.message}")
    }.getOrNull()
}

internal fun LocalMediaSupport.buildLocalLyricsMetadataJsonImpl(
    existingRaw: String?,
    song: SongItem,
    clearMissingLyricFields: Boolean = false
): String {
    val root = existingRaw
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JSONObject()
    if (root.optLong("sourceModifiedAtMs", 0L) <= 0L) {
        song.sourceModifiedAtMs?.takeIf { it > 0L }?.let { root.put("sourceModifiedAtMs", it) }
    }
    updateLyricMetadataField(
        root = root,
        matchedKey = "matchedLyric",
        originalKey = "originalLyric",
        matchedValue = song.matchedLyric,
        originalValue = song.originalLyric,
        clearMissing = clearMissingLyricFields
    )
    updateLyricMetadataField(
        root = root,
        matchedKey = "matchedTranslatedLyric",
        originalKey = "originalTranslatedLyric",
        matchedValue = song.matchedTranslatedLyric,
        originalValue = song.originalTranslatedLyric,
        clearMissing = clearMissingLyricFields
    )
    updateLyricMetadataField(
        root = root,
        matchedKey = "matchedRomanizedLyric",
        originalKey = "originalRomanizedLyric",
        matchedValue = song.matchedRomanizedLyric,
        originalValue = song.originalRomanizedLyric,
        clearMissing = clearMissingLyricFields
    )
    return root.toString()
}

internal fun LocalMediaSupport.buildEditableLocalMetadataJsonImpl(
    existingRaw: String?,
    song: SongItem,
    writeLyrics: Boolean,
    coverReference: String?,
    clearCoverReference: Boolean
): String {
    val root = existingRaw
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JSONObject()

    fun putValue(key: String, value: Any?) {
        if (value == null) {
            root.remove(key)
        } else {
            root.put(key, value)
        }
    }

    putValue("name", song.name)
    putValue("artist", song.artist)
    putValue("album", song.album)
    putValue("customName", song.customName)
    putValue("customArtist", song.customArtist)
    putValue("originalName", song.originalName)
    putValue("originalArtist", song.originalArtist)
    putValue("coverUrl", song.coverUrl)
    putValue("customCoverUrl", song.customCoverUrl)
    putValue("originalCoverUrl", song.originalCoverUrl)
    putValue("mediaUri", song.mediaUri)
    putValue("localFilePath", song.localFilePath)
    putValue("stableKey", song.songStableKey())
    putValue("songId", song.id)
    putValue("channelId", song.channelId)
    putValue("audioId", song.audioId)
    putValue("subAudioId", song.subAudioId)
    putValue("playlistContextId", song.playlistContextId)
    putValue("durationMs", song.durationMs)

    if (clearCoverReference) {
        root.remove("coverPath")
    } else if (coverReference != null) {
        putValue("coverPath", coverReference)
    }

    if (writeLyrics) {
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedLyric",
            originalKey = "originalLyric",
            matchedValue = song.matchedLyric,
            originalValue = song.originalLyric,
            clearMissing = true
        )
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedTranslatedLyric",
            originalKey = "originalTranslatedLyric",
            matchedValue = song.matchedTranslatedLyric,
            originalValue = song.originalTranslatedLyric,
            clearMissing = true
        )
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedRomanizedLyric",
            originalKey = "originalRomanizedLyric",
            matchedValue = song.matchedRomanizedLyric,
            originalValue = song.originalRomanizedLyric,
            clearMissing = true
        )
        putValue("matchedLyricSource", song.matchedLyricSource?.name)
        putValue("matchedSongId", song.matchedSongId)
        putValue("userLyricOffsetMs", song.userLyricOffsetMs)
    }
    return root.toString()
}
