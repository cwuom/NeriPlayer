package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ContainerMetadata
import android.content.Context
import android.net.Uri
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import androidx.core.net.toUri

internal fun LocalMediaSupport.resolveWritableLocalMediaUriImpl(
    context: Context,
    sourceUri: Uri
): Uri? {
    if (!isMediaStoreUri(sourceUri)) return sourceUri
    val contentInfo = queryContentInfo(context, sourceUri)
    val displayName = contentInfo.displayName?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    val navigation = resolveMediaStoreDocumentNavigation(context, sourceUri) ?: return null
    val parentDocumentId = navigation.parentDocumentId ?: return null
    val baseUri = navigation.treeUri ?: navigation.baseUri
    return queryDocumentChildren(context, baseUri, parentDocumentId)
        .firstOrNull { child ->
            !child.isDirectory && child.displayName.equals(displayName, ignoreCase = true)
        }
        ?.uri
        ?.toUri()
        ?.takeIf { isWritableDocumentUri(context, it) }
}

internal fun LocalMediaSupport.buildExternalStorageDocumentIdImpl(
    parentDocumentId: String,
    displayName: String
): String? {
    if (parentDocumentId.isBlank() || displayName.isBlank()) return null
    if (displayName.contains('/') || displayName.contains('\\')) return null
    return "${parentDocumentId.trimEnd('/')}/$displayName"
}

internal fun LocalMediaSupport.applyEditableMetadataImpl(
    propertyMap: PropertyMap,
    title: String,
    artist: String,
    lyrics: String?,
    translatedLyrics: String?,
    romanizedLyrics: String? = null,
    audioExtension: String?,
    writeLyrics: Boolean = false,
    sourceStableKey: String? = null
): PropertyMap {
    val updated: PropertyMap = hashMapOf()
    propertyMap.forEach { (key, values) ->
        updated[key] = values.copyOf()
    }
    putTagValue(updated, "TITLE", title)
    putTagValue(updated, "ARTIST", artist)
    sourceStableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { key -> putTagValue(updated, "NERI_STABLE_KEY", key) }
    if (writeLyrics) {
        val externalLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics)
        standardLyricsMetadataKeys(audioExtension).forEach { key ->
            putTagValue(updated, key, externalLyrics.orEmpty())
        }
        putTagValue(updated, NERI_ORIGINAL_LYRICS_METADATA_KEY, lyrics)
        translatedLyricsMetadataKeys.forEach { key ->
            putTagValue(updated, key, translatedLyrics)
        }
        putTagValue(updated, NERI_ROMANIZED_LYRICS_METADATA_KEY, romanizedLyrics)
    }
    return updated
}

internal fun LocalMediaSupport.hasExpectedEditableMetadataImpl(
    propertyMap: PropertyMap,
    title: String,
    artist: String,
    lyrics: String?,
    translatedLyrics: String?,
    romanizedLyrics: String? = null,
    audioExtension: String?,
    expectedStandardLyrics: String? = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
    verifyStandardLyrics: Boolean = lyrics != null || translatedLyrics != null,
    verifyMissingLyrics: Boolean = false,
    sourceStableKey: String? = null
): Boolean {
    return hasExpectedTagValue(propertyMap, "TITLE", title) &&
        hasExpectedTagValue(propertyMap, "ARTIST", artist) &&
        (!verifyStandardLyrics || hasExpectedStandardLyrics(
            propertyMap = propertyMap,
            audioExtension = audioExtension,
            expectedLyrics = expectedStandardLyrics
        )) &&
        hasExpectedOneOfTagValues(
            propertyMap = propertyMap,
            keys = listOf(NERI_ORIGINAL_LYRICS_METADATA_KEY),
            expectedValue = lyrics,
            verifyMissing = verifyMissingLyrics
        ) &&
        hasExpectedOneOfTagValues(
            propertyMap = propertyMap,
            keys = translatedLyricsMetadataKeys,
            expectedValue = translatedLyrics,
            verifyMissing = verifyMissingLyrics
        ) &&
        hasExpectedOneOfTagValues(
            propertyMap = propertyMap,
            keys = listOf(NERI_ROMANIZED_LYRICS_METADATA_KEY),
            expectedValue = romanizedLyrics,
            verifyMissing = verifyMissingLyrics
        ) &&
        (
            sourceStableKey.isNullOrBlank() ||
                hasExpectedOneOfTagValues(
                    propertyMap = propertyMap,
                    keys = listOf("NERI_STABLE_KEY", "NERI STABLE KEY"),
                    expectedValue = sourceStableKey
                )
            )
}

internal fun LocalMediaSupport.hasExpectedPropertyMapValuesImpl(
    actual: PropertyMap,
    expected: PropertyMap,
    requiredKeys: Set<String>
): Boolean {
    fun PropertyMap.normalizedValues(key: String): List<String> = entries
        .firstOrNull { (candidate, _) -> candidate.equals(key, ignoreCase = true) }
        ?.value
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()

    return requiredKeys.all { key ->
        actual.normalizedValues(key) == expected.normalizedValues(key)
    }
}

internal fun LocalMediaSupport.hasExpectedEditableCoverImpl(
    actualPictures: Array<Picture>,
    expectedPictures: Array<Picture>,
    audioExtension: String? = null
): Boolean {
    if (usesRolelessEditableCoverPictures(audioExtension)) {
        return editableCoverPictureListsEquivalent(
            left = actualPictures,
            right = expectedPictures,
            audioExtension = audioExtension
        )
    }
    val actualFrontCover = actualPictures.firstOrNull(::isFrontCoverPicture)
    val expectedFrontCover = expectedPictures.firstOrNull(::isFrontCoverPicture)
    return when {
        expectedFrontCover == null -> actualFrontCover == null
        actualFrontCover == null -> false
        else -> actualFrontCover.data.contentEquals(expectedFrontCover.data)
    }
}

internal fun LocalMediaSupport.replaceEditableCoverPicturesImpl(
    existingPictures: Array<Picture>,
    replacementPicture: Picture?,
    audioExtension: String?
): Array<Picture> {
    if (usesRolelessEditableCoverPictures(audioExtension)) {
        return replacementPicture?.let { arrayOf(it) } ?: emptyArray<Picture>()
    }
    val retainedPictures = existingPictures.filterNot(::isFrontCoverPicture)
    return if (replacementPicture == null) {
        retainedPictures.toTypedArray()
    } else {
        (retainedPictures + replacementPicture).toTypedArray()
    }
}

internal fun LocalMediaSupport.resolveEditableCoverMutationImpl(
    writeCover: Boolean,
    coverReference: String?
): EditableCoverMutation {
    if (!writeCover) return EditableCoverMutation.UNCHANGED
    return if (coverReference.isNullOrBlank()) {
        EditableCoverMutation.CLEAR
    } else {
        EditableCoverMutation.REPLACE
    }
}

internal fun LocalMediaSupport.readEditableCoverBytesImpl(context: Context, reference: String): ByteArray? {
    val uri = runCatching { reference.toUri() }.getOrNull()
    if (reference.isRemoteCoverReference()) {
        return readRemoteEditableCoverBytes(reference)
    }
    val localFile = when {
        reference.startsWith("/") -> File(reference)
        else -> uri
            ?.takeIf { coverUri -> coverUri.scheme.equals("file", ignoreCase = true) }
            ?.path
            ?.let(::File)
    }
    if (localFile != null) {
        if (!localFile.isFile) {
            logEditableCoverReadFailure(
                stage = "local_missing",
                reference = reference,
                error = FileNotFoundException("cover file is missing")
            )
            return null
        }
        return runCatching {
            localFile.inputStream().use { input ->
                input.readBytesLimited(MAX_EDITABLE_COVER_BYTES).also { bytes ->
                    if (bytes.isEmpty()) {
                        throw IOException("cover bytes are empty")
                    }
                }
            }
        }.onFailure { error ->
            logEditableCoverReadFailure(
                stage = "local_read",
                reference = reference,
                error = error
            )
        }.getOrNull()
    }
    val coverUri = uri ?: run {
        logEditableCoverReadFailure(
            stage = "reference_parse",
            reference = reference,
            error = IllegalArgumentException("invalid cover reference")
        )
        return null
    }
    return runCatching {
        val stream = context.contentResolver.openInputStream(coverUri)
            ?: throw IOException("content resolver returned no input stream")
        stream.use { input ->
            input.readBytesLimited(MAX_EDITABLE_COVER_BYTES).also { bytes ->
                if (bytes.isEmpty()) {
                    throw IOException("cover bytes are empty")
                }
            }
        }
    }.onFailure { error ->
        logEditableCoverReadFailure(
            stage = "content_read",
            reference = reference,
            error = error
        )
    }.getOrNull()
}

internal fun LocalMediaSupport.normalizeEmbeddedCoverForContainerImpl(
    sourceBytes: ByteArray,
    sourceMimeType: String?,
    audioExtension: String?
): Pair<ByteArray, String>? {
    val normalizedMimeType = sourceMimeType?.let(::normalizeEditableCoverMimeType)
    if (
        !usesRolelessEditableCoverPictures(audioExtension) ||
            normalizedMimeType in MP4_SUPPORTED_COVER_MIME_TYPES
    ) {
        return sourceBytes to (normalizedMimeType ?: "image/jpeg")
    }
    return encodeEditableCoverAsJpeg(sourceBytes)?.let { bytes ->
        bytes to "image/jpeg"
    }
}

internal fun LocalMediaSupport.parseId3FileMetadataImpl(file: File): ContainerMetadata? {
    if (!file.exists() || !file.isFile) return null
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            mergeContainerMetadata(
                primary = readId3v2FileMetadata(raf),
                fallback = readId3v1FileMetadata(raf)
            )
        }
    }.getOrElse {
        NPLogger.w(TAG, "parseId3FileMetadata failed for ${file.absolutePath}: ${it.message}")
        null
    }
}

internal fun LocalMediaSupport.parseWaveMetadataImpl(file: File): ContainerMetadata? {
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12L) return@use null
            val riffId = raf.readFourCc() ?: return@use null
            val riffSize = raf.readLittleEndianUInt32()
            val waveId = raf.readFourCc() ?: return@use null
            if (riffId != "RIFF" || waveId != "WAVE") return@use null

            val fileLimit = minOf(raf.length(), riffSize + 8L)
            var infoMetadata: ContainerMetadata? = null
            var id3Metadata: ContainerMetadata? = null

            while (raf.filePointer + 8L <= fileLimit) {
                val chunkId = raf.readFourCc() ?: break
                val chunkSize = raf.readLittleEndianUInt32()
                val chunkDataStart = raf.filePointer
                when {
                    chunkId == "LIST" && chunkSize >= 4L -> {
                        val listType = raf.readFourCc()
                        if (listType == "INFO") {
                            val infoBytes = raf.readChunkBytes(chunkSize - 4L, fileLimit)
                            infoMetadata = mergeContainerMetadata(
                                primary = infoMetadata,
                                fallback = infoBytes?.let(::parseWaveInfoMetadata)
                            )
                        }
                    }

                    chunkId.trimEnd(' ') == "ID3" -> {
                        val id3Bytes = raf.readChunkBytes(chunkSize, fileLimit)
                        id3Metadata = mergeContainerMetadata(
                            primary = id3Metadata,
                            fallback = id3Bytes?.let(::parseId3Metadata)
                        )
                    }
                }

                val nextChunkPosition = chunkDataStart + chunkSize + (chunkSize and 1L)
                if (nextChunkPosition <= raf.filePointer) break
                raf.seek(minOf(nextChunkPosition, fileLimit))
            }

            mergeContainerMetadata(id3Metadata, infoMetadata)
        }
    }.getOrElse {
        NPLogger.w(TAG, "parseWaveMetadata failed for ${file.absolutePath}: ${it.message}")
        null
    }
}
