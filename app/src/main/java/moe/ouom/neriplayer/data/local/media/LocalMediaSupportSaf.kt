package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LocalCoverCacheHit
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ResolvedInspectableLocalMedia
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ContainerMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableCoverWritePlan
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ContentSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.net.URLConnection
import java.util.LinkedHashMap
import java.util.Locale
import androidx.core.net.toUri
import okhttp3.Request

internal fun LocalMediaSupport.putTagValue(propertyMap: PropertyMap, key: String, value: String?) {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) {
        propertyMap.remove(key)
    } else {
        propertyMap[key] = arrayOf(normalized)
    }
}

internal fun LocalMediaSupport.hasExpectedTagValue(
    propertyMap: PropertyMap,
    key: String,
    expectedValue: String
): Boolean {
    val normalized = expectedValue.trim()
    if (normalized.isBlank()) {
        return key !in propertyMap || propertyMap[key].isNullOrEmpty()
    }
    return propertyMap[key]?.any { value -> value.trim() == normalized } == true
}

internal fun LocalMediaSupport.hasExpectedOneOfTagValues(
    propertyMap: PropertyMap,
    keys: List<String>,
    expectedValue: String?,
    verifyMissing: Boolean = false
): Boolean {
    if (expectedValue == null) {
        return !verifyMissing || keys.all { key ->
            key !in propertyMap || propertyMap[key].isNullOrEmpty()
        }
    }
    val normalized = expectedValue.trim()
    if (normalized.isBlank()) {
        return keys.all { key ->
            key !in propertyMap || propertyMap[key].isNullOrEmpty()
        }
    }
    return keys.any { key -> hasExpectedTagValue(propertyMap, key, normalized) }
}

internal fun LocalMediaSupport.hasExpectedStandardLyrics(
    propertyMap: PropertyMap,
    audioExtension: String?,
    expectedLyrics: String?
): Boolean {
    val keys = standardLyricsMetadataKeys(audioExtension)
    if (expectedLyrics.isNullOrBlank()) {
        return keys.all { key ->
            key !in propertyMap || propertyMap[key].isNullOrEmpty()
        }
    }
    return hasExpectedOneOfTagValues(propertyMap, keys, expectedLyrics)
}

internal fun LocalMediaSupport.copyEditablePropertyMap(source: PropertyMap): PropertyMap {
    val copied: PropertyMap = hashMapOf()
    source.forEach { (key, values) -> copied[key] = values.copyOf() }
    return copied
}

internal fun LocalMediaSupport.editableMetadataSourceStableKey(song: SongItem): String {
    return song.sourceStableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: song.songStableKey()
}

internal fun LocalMediaSupport.buildEditableCoverWritePlan(
    context: Context,
    descriptor: ParcelFileDescriptor,
    coverReference: String?,
    writeCover: Boolean,
    audioExtension: String?
): EditableCoverWritePlan {
    val reference = coverReference?.trim()?.takeIf(String::isNotBlank)
    val mutation = resolveEditableCoverMutation(writeCover, reference)
    if (mutation == EditableCoverMutation.UNCHANGED) return EditableCoverWritePlan.Unchanged
    val existingPictures = runCatching {
        TagLib.getPictures(descriptor.dup().detachFd())
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "本地封面读取失败: stage=cover_read, error=" +
                "${error.javaClass.simpleName}: ${error.message}",
            error
        )
        return EditableCoverWritePlan.Unreadable
    }
    if (mutation == EditableCoverMutation.CLEAR) {
        val updatedPictures = replaceEditableCoverPictures(
            existingPictures = existingPictures,
            replacementPicture = null,
            audioExtension = audioExtension
        )
        return if (
            editableCoverPictureListsEquivalent(
                left = existingPictures,
                right = updatedPictures,
                audioExtension = audioExtension
            )
        ) {
            EditableCoverWritePlan.Unchanged
        } else {
            EditableCoverWritePlan.Update(
                pictures = updatedPictures,
                originalPictures = existingPictures
            )
        }
    }
    require(mutation == EditableCoverMutation.REPLACE)
    val replacementReference = requireNotNull(reference)
    val replacementPicture = createEditableCoverPicture(
        context = context,
        reference = replacementReference,
        audioExtension = audioExtension
    )
        ?: run {
            NPLogger.w(
                TAG,
                "本地封面引用不可读，保留现有嵌入元信息等待重试: " +
                    "stage=cover_read, ref=${redactCoverReference(replacementReference)}"
            )
            // 未能读取请求写入的封面不能当作未修改，否则嵌入回读会误报成功
            return EditableCoverWritePlan.Unreadable
        }
    val updatedPictures = replaceEditableCoverPictures(
        existingPictures = existingPictures,
        replacementPicture = replacementPicture,
        audioExtension = audioExtension
    )
    if (
        editableCoverPictureListsEquivalent(
            left = existingPictures,
            right = updatedPictures,
            audioExtension = audioExtension
        )
    ) {
        return EditableCoverWritePlan.Unchanged
    }
    return EditableCoverWritePlan.Update(
        pictures = updatedPictures,
        originalPictures = existingPictures
    )
}

internal fun LocalMediaSupport.isFrontCoverPicture(picture: Picture): Boolean {
    return picture.pictureType.equals(FRONT_COVER_PICTURE_TYPE, ignoreCase = true)
}

internal fun LocalMediaSupport.editableCoverPictureListsEquivalent(
    left: Array<Picture>,
    right: Array<Picture>,
    audioExtension: String?
): Boolean {
    if (left.size != right.size) return false
    val rolelessPictureContainer = usesRolelessEditableCoverPictures(audioExtension)
    return left.indices.all { index ->
        val actual = left[index]
        val expected = right[index]
        actual.data.contentEquals(expected.data) && (
            rolelessPictureContainer ||
                actual.description == expected.description &&
                actual.pictureType.equals(expected.pictureType, ignoreCase = true) &&
                actual.mimeType.equals(expected.mimeType, ignoreCase = true)
            )
    }
}

internal fun LocalMediaSupport.readRemoteEditableCoverBytes(reference: String): ByteArray? {
    val request = Request.Builder()
        .url(reference)
        .header("Accept", "image/*")
        .build()
    return runCatching {
        AppContainer.sharedOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                NPLogger.w(TAG, "download editable cover failed: HTTP ${response.code}")
                return@use null
            }
            val body = response.body
            if (body.contentLength() > MAX_EDITABLE_COVER_BYTES) {
                NPLogger.w(TAG, "download editable cover exceeds size limit")
                return@use null
            }
            body.byteStream().use { input ->
                input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
            }.takeIf(ByteArray::isNotEmpty)
        }
    }.onFailure { error ->
        NPLogger.w(TAG, "download editable cover failed: ${error.message}")
    }.getOrNull()
}

internal fun LocalMediaSupport.createEditableCoverPicture(
    context: Context,
    reference: String,
    audioExtension: String?
): Picture? {
    val sourceBytes = readEditableCoverBytes(context, reference) ?: return null
    val sourceMimeType = resolveEditableCoverMimeType(context, reference, sourceBytes)
    val encodedCover = normalizeEmbeddedCoverForContainer(
        sourceBytes = sourceBytes,
        sourceMimeType = sourceMimeType,
        audioExtension = audioExtension
    )
    val finalCover = encodedCover ?: return null
    return Picture(
        data = finalCover.first,
        description = "",
        pictureType = FRONT_COVER_PICTURE_TYPE,
        mimeType = finalCover.second
    )
}

internal fun LocalMediaSupport.encodeEditableCoverAsJpeg(sourceBytes: ByteArray): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return null
    return try {
        ByteArrayOutputStream().use { output ->
            EDITABLE_COVER_JPEG_QUALITIES.forEach { quality ->
                output.reset()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    val encoded = output.toByteArray()
                    if (encoded.isNotEmpty() && encoded.size <= MAX_EDITABLE_COVER_BYTES) {
                        return@use encoded
                    }
                }
            }
            null
        }
    } finally {
        bitmap.recycle()
    }
}

internal fun LocalMediaSupport.resolveEditableCoverMimeType(
    context: Context,
    reference: String,
    bytes: ByteArray
): String {
    val uri = runCatching { reference.toUri() }.getOrNull()
    val declaredMimeType = uri?.let { coverUri ->
        runCatching { context.contentResolver.getType(coverUri) }.getOrNull()
    }?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/", ignoreCase = true) }
    val guessedMimeType = URLConnection.guessContentTypeFromName(
        uri?.lastPathSegment ?: reference
    )?.takeIf { it.startsWith("image/", ignoreCase = true) }
    return normalizeEditableCoverMimeType(
        detectEditableCoverMimeType(bytes) ?: declaredMimeType ?: guessedMimeType ?: "image/jpeg"
    )
}

internal fun LocalMediaSupport.normalizeEditableCoverMimeType(mimeType: String): String {
    return when (mimeType.lowercase(Locale.ROOT)) {
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        "image/x-ms-bmp" -> "image/bmp"
        else -> mimeType.lowercase(Locale.ROOT)
    }
}

internal fun LocalMediaSupport.coverExtensionForMimeType(mimeType: String): String {
    return when (normalizeEditableCoverMimeType(mimeType)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }
}

internal fun LocalMediaSupport.detectEditableCoverMimeType(bytes: ByteArray): String? {
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
    return null
}

internal fun LocalMediaSupport.propertyMapsEquivalent(left: PropertyMap, right: PropertyMap): Boolean {
    if (left.size != right.size) {
        return false
    }
    return left.all { (key, leftValues) ->
        right[key]?.contentEquals(leftValues) == true
    }
}

internal fun LocalMediaSupport.parseContainerMetadata(file: File): ContainerMetadata? {
    if (!file.exists() || !file.isFile) return null
    return when (file.extension.lowercase()) {
        "wav", "wave" -> parseWaveMetadata(file)
        "mp1", "mp2", "mp3", "aac" -> parseId3FileMetadata(file)
        else -> parseId3FileMetadata(file)
    }
}

internal fun LocalMediaSupport.readId3v2FileMetadata(raf: RandomAccessFile): ContainerMetadata? {
    if (raf.length() < 10L) return null
    raf.seek(0)
    val header = ByteArray(10)
    raf.readFully(header)
    if (header.readAscii(0, 3) != "ID3") return null

    val tagSize = header.readSynchsafeInt(6)
    if (tagSize <= 0) {
        return null
    }
    val readableSize = minOf(
        raf.length(),
        10L + tagSize.toLong(),
        MAX_CONTAINER_METADATA_BYTES
    ).toInt()
    if (readableSize <= 10) return null

    raf.seek(0)
    val tagBytes = ByteArray(readableSize)
    raf.readFully(tagBytes)
    return parseId3Metadata(tagBytes)
}

internal fun LocalMediaSupport.readId3v1FileMetadata(raf: RandomAccessFile): ContainerMetadata? {
    if (raf.length() < 128L) return null
    raf.seek(raf.length() - 128L)
    val tag = ByteArray(128)
    raf.readFully(tag)
    if (tag.readAscii(0, 3) != "TAG") return null

    val trackNumber = tag[125]
        .takeIf { it == 0.toByte() }
        ?.let { tag[126].toInt() and 0xFF }
        ?.takeIf { it > 0 }
    val metadata = ContainerMetadata(
        title = tag.copyOfRange(3, 33).decodeContainerText(),
        artist = tag.copyOfRange(33, 63).decodeContainerText(),
        album = tag.copyOfRange(63, 93).decodeContainerText(),
        year = tag.copyOfRange(93, 97).decodeContainerText()?.extractYear(),
        trackNumber = trackNumber
    )
    return metadata.takeIf { it.hasAnyValue() }
}

internal fun LocalMediaSupport.parseWaveInfoMetadata(bytes: ByteArray): ContainerMetadata? {
    var offset = 0
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var composer: String? = null
    var genre: String? = null
    var year: Int? = null
    var trackNumber: Int? = null
    var discNumber: Int? = null

    while (offset + 8 <= bytes.size) {
        val chunkId = bytes.readFourCc(offset) ?: break
        val chunkSize = bytes.readLittleEndianUInt32(offset + 4).coerceAtMost((bytes.size - offset - 8).toLong())
        val valueStart = offset + 8
        val valueEnd = valueStart + chunkSize.toInt()
        val value = bytes.copyOfRange(valueStart, valueEnd).decodeContainerText()

        when (chunkId) {
            "INAM" -> title = title ?: value
            "IART" -> artist = artist ?: value
            "IPRD" -> album = album ?: value
            "IAAR" -> albumArtist = albumArtist ?: value
            "IENG" -> composer = composer ?: value
            "IGNR" -> genre = genre ?: value
            "ICRD" -> year = year ?: value?.extractYear()
            "ITRK" -> trackNumber = trackNumber ?: parseIndexedMetadata(value)
            "IPRT" -> discNumber = discNumber ?: parseIndexedMetadata(value)
        }

        offset = valueEnd + (chunkSize.toInt() and 1)
    }

    return ContainerMetadata(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        composer = composer,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber
    ).takeIf { it.hasAnyValue() }
}

internal fun LocalMediaSupport.parseId3Metadata(bytes: ByteArray): ContainerMetadata? {
    if (bytes.size < 10 || bytes.readAscii(0, 3) != "ID3") return null
    val majorVersion = bytes[3].toInt() and 0xFF
    val flags = bytes[5].toInt() and 0xFF
    val tagSize = bytes.readSynchsafeInt(6)
    val limit = minOf(bytes.size, 10 + tagSize)
    var offset = 10

    if (majorVersion > 2 && (flags and 0x40) != 0 && offset + 4 <= limit) {
        val extendedSize = if (majorVersion >= 4) {
            bytes.readSynchsafeInt(offset)
        } else {
            bytes.readBigEndianInt(offset)
        }
        offset += extendedSize.coerceAtLeast(0)
    }

    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var composer: String? = null
    var genre: String? = null
    var year: Int? = null
    var trackNumber: Int? = null
    var discNumber: Int? = null

    val frameHeaderSize = if (majorVersion == 2) 6 else 10
    while (offset + frameHeaderSize <= limit) {
        val frameId = when (majorVersion) {
            2 -> bytes.readAscii(offset, 3)
            else -> bytes.readFourCc(offset)?.trimEnd(NUL_CHAR, ' ')
        }.orEmpty()
        if (frameId.isBlank()) break
        val frameSize = if (majorVersion >= 4) {
            bytes.readSynchsafeInt(offset + 4)
        } else if (majorVersion == 2) {
            bytes.readBigEndianInt24(offset + 3)
        } else {
            bytes.readBigEndianInt(offset + 4)
        }
        if (frameSize <= 0) break

        val frameDataStart = offset + frameHeaderSize
        val frameDataEnd = frameDataStart + frameSize
        if (frameDataEnd > limit) break

        val frameData = bytes.copyOfRange(frameDataStart, frameDataEnd)
        val value = decodeId3TextFrame(frameData)

        when (frameId) {
            "TIT2", "TT2" -> title = title ?: value
            "TPE1", "TP1" -> artist = artist ?: value
            "TALB", "TAL" -> album = album ?: value
            "TPE2", "TP2" -> albumArtist = albumArtist ?: value
            "TCOM", "TCM" -> composer = composer ?: value
            "TCON", "TCO" -> genre = genre ?: value
            "TDRC", "TYER", "TYE" -> year = year ?: value?.extractYear()
            "TRCK", "TRK" -> trackNumber = trackNumber ?: parseIndexedMetadata(value)
            "TPOS", "TPA" -> discNumber = discNumber ?: parseIndexedMetadata(value)
        }

        offset = frameDataEnd
    }

    return ContainerMetadata(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        composer = composer,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber
    ).takeIf { it.hasAnyValue() }
}

internal fun LocalMediaSupport.mergeContainerMetadata(
    primary: ContainerMetadata?,
    fallback: ContainerMetadata?
): ContainerMetadata? {
    if (primary == null) return fallback
    if (fallback == null) return primary
    return ContainerMetadata(
        title = primary.title ?: fallback.title,
        artist = primary.artist ?: fallback.artist,
        album = primary.album ?: fallback.album,
        albumArtist = primary.albumArtist ?: fallback.albumArtist,
        composer = primary.composer ?: fallback.composer,
        genre = primary.genre ?: fallback.genre,
        year = primary.year ?: fallback.year,
        trackNumber = primary.trackNumber ?: fallback.trackNumber,
        discNumber = primary.discNumber ?: fallback.discNumber
    )
}

internal fun LocalMediaSupport.localCoverLookupKey(uri: Uri, resolved: ResolvedInspectableLocalMedia): String {
    val file = resolved.file
    return buildString {
        append(file?.absolutePath ?: uri.toString())
        append('|')
        append(file?.length() ?: resolved.queried.sizeBytes ?: -1L)
        append('|')
        append(file?.lastModified() ?: resolved.queried.lastModifiedMs ?: -1L)
    }
}

internal fun LocalMediaSupport.cachedLocalCoverLookup(
    context: Context,
    cacheKey: String
): LocalCoverCacheHit? {
    val coverUri = synchronized(localCoverLookupCache) {
        if (!localCoverLookupCache.containsKey(cacheKey)) return null
        localCoverLookupCache[cacheKey]
    }
    if (coverUri == null) {
        // 没有封面时不保留负缓存，避免后续写入或恢复元信息后永远跳过重试
        synchronized(localCoverLookupCache) {
            localCoverLookupCache.remove(cacheKey)
        }
        return null
    }
    if (!isUsableCachedCoverUri(context, coverUri)) {
        synchronized(localCoverLookupCache) {
            if (localCoverLookupCache[cacheKey] == coverUri) {
                localCoverLookupCache.remove(cacheKey)
            }
        }
        return null
    }
    return LocalCoverCacheHit(coverUri)
}

internal fun LocalMediaSupport.isUsableCachedCoverUri(context: Context, coverUri: String): Boolean {
    return isUsableCoverReference(context, coverUri)
}

internal fun LocalMediaSupport.rememberLocalCoverLookup(cacheKey: String, coverUri: String?) {
    val normalizedCoverUri = coverUri
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    synchronized(localCoverLookupCache) {
        localCoverLookupCache[cacheKey] = normalizedCoverUri
    }
}

internal fun LocalMediaSupport.invalidateLocalCoverLookupCache(
    context: Context,
    uri: Uri,
    resolved: ResolvedInspectableLocalMedia?
) {
    val prefixes = buildList {
        resolved?.file?.absolutePath?.let { add("$it|") }
        add("${uri}|")
    }
    synchronized(localCoverLookupCache) {
        val iterator = localCoverLookupCache.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (prefixes.any(key::startsWith)) {
                iterator.remove()
            }
        }
    }
    embeddedCoverCacheKeys(uri.toString(), resolved?.resolvedPath).forEach { cacheKey ->
        val cacheFile = embeddedCoverFile(context, cacheKey)
        if (cacheFile.isFile && !cacheFile.delete()) {
            NPLogger.w(TAG, "clear stale embedded cover cache failed: ${cacheFile.name}")
        }
    }
}

internal fun LocalMediaSupport.extractEmbeddedCoverWithRetriever(
    context: Context,
    uri: Uri,
    resolved: ResolvedInspectableLocalMedia
): String? {
    val uriKey = resolved.resolvedPath ?: uri.toString()
    findCachedEmbeddedCover(context, uriKey)?.let { return it }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, resolved.playableUri)
        saveEmbeddedCover(context, uriKey, retriever.embeddedPicture)
    } catch (error: Exception) {
        NPLogger.w(TAG, "resolve embedded cover failed for $uri: ${error.message}")
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun LocalMediaSupport.extractEmbeddedCoverWithTagLib(
    context: Context,
    uri: Uri,
    resolved: ResolvedInspectableLocalMedia
): String? {
    val uriKey = "${resolved.resolvedPath ?: uri}#taglib"
    findCachedEmbeddedCover(context, uriKey)?.let { return it }
    val coverBytes = openTagLibDescriptor(context, resolved.playableUri, resolved.file)?.use { descriptor ->
        runCatching {
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), true)
            metadata?.pictures
                ?.firstOrNull { it.pictureType.equals("Front Cover", ignoreCase = true) }
                ?.data
                ?: metadata?.pictures?.firstOrNull()?.data
        }.getOrElse {
            NPLogger.w(TAG, "TagLib cover failed for $uri: ${it.message}")
            null
        }
    }
    return saveEmbeddedCover(context, uriKey, coverBytes)
}

internal fun LocalMediaSupport.findCachedEmbeddedCover(context: Context, uriKey: String): String? {
    val file = embeddedCoverFile(context, uriKey)
    if (!file.isFile || file.length() <= 0L) return null
    if (!isUsableCoverFile(file)) {
        if (!file.delete()) {
            NPLogger.w(TAG, "remove invalid embedded cover cache failed: ${file.name}")
        }
        return null
    }
    return file.toURI().toString()
}

internal fun LocalMediaSupport.embeddedCoverFile(context: Context, uriKey: String): File {
    val coverDir = File(context.filesDir, "local_audio_covers")
    return File(coverDir, "${stableKey(uriKey)}.jpg")
}

internal fun LocalMediaSupport.saveEmbeddedCover(context: Context, uriKey: String, embeddedPicture: ByteArray?): String? {
    if (embeddedPicture == null || embeddedPicture.isEmpty()) return null
    val file = embeddedCoverFile(context, uriKey)
    if (file.isFile && file.length() > 0L) {
        if (isUsableCoverFile(file)) {
            return file.toURI().toString()
        }
        if (!file.delete()) {
            NPLogger.w(TAG, "replace invalid embedded cover cache failed: ${file.name}")
        }
    }
    val parent = file.parentFile ?: return null
    if (!parent.isDirectory && !parent.mkdirs()) {
        NPLogger.w(TAG, "create embedded cover cache directory failed: ${parent.path}")
        return null
    }
    val cacheBytes = compactEmbeddedCoverForCache(embeddedPicture) ?: return null
    val tempFile = File(file.parentFile ?: context.filesDir, ".${file.name}.tmp")
    tempFile.writeBytes(cacheBytes)
    if (!tempFile.renameTo(file)) {
        file.writeBytes(cacheBytes)
        tempFile.delete()
    }
    return file.toURI().toString()
}

internal fun LocalMediaSupport.compactEmbeddedCoverForCache(sourceBytes: ByteArray): ByteArray? {
    if (sourceBytes.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        return sourceBytes.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var targetDimension = MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX
    repeat(3) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = embeddedCoverCacheSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetDimension = targetDimension
            )
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
            ?: return null
        try {
            encodeEmbeddedCoverForCache(bitmap)?.let { return it }
        } finally {
            bitmap.recycle()
        }
        targetDimension = (targetDimension / 2).coerceAtLeast(1)
    }
    return null
}

internal fun LocalMediaSupport.encodeEmbeddedCoverForCache(bitmap: Bitmap): ByteArray? {
    return ByteArrayOutputStream().use { output ->
        EDITABLE_COVER_JPEG_QUALITIES.forEach { quality ->
            output.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                return@forEach
            }
            val encoded = output.toByteArray()
            if (encoded.isNotEmpty() && encoded.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) {
                return@use encoded
            }
        }
        null
    }
}

internal fun LocalMediaSupport.copyLyricReference(
    context: Context,
    reference: String,
    target: File
) {
    if (target.exists()) return
    runCatching {
        context.contentResolver.openInputStream(reference.toUri())?.use { input ->
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: error("unable to open lyric sidecar: $reference")
    }.onFailure {
        NPLogger.w(TAG, "copy lyric sidecar failed for $reference: ${it.message}")
        target.delete()
    }
}

internal fun LocalMediaSupport.readNearbyLyricContent(
    context: Context,
    reference: String?,
    label: String
): String? {
    return reference?.let {
        readTextContent(context, it)
            ?: run {
                NPLogger.w(TAG, "read $label failed for $it")
                null
            }
    }
}

internal fun LocalMediaSupport.findNearbyLyricReferences(
    context: Context,
    uri: Uri,
    file: File?,
    displayName: String
): NearbyLyricReferences {
    return resolveContentSidecarReferences(
        context = context,
        sourceUri = uri,
        file = file,
        displayName = displayName
    ).lyricReferences
}

internal fun LocalMediaSupport.resolveContentSidecarReferences(
    context: Context,
    sourceUri: Uri,
    displayName: String,
    file: File? = null
): ContentSidecarReferences {
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    val localFiles = findNearbyLyricFiles(localFile)
    val localReferences = NearbyLyricReferences(
        original = localFiles.original?.absolutePath,
        translated = localFiles.translated?.absolutePath,
        romanized = localFiles.romanized?.absolutePath
    )
    if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
        return ContentSidecarReferences(
            metadataReference = localMetadataReference(localFile),
            lyricReferences = localReferences
        )
    }

    val navigation = resolveLocalDocumentNavigation(context, sourceUri)
        ?: return ContentSidecarReferences(
            metadataReference = localMetadataReference(localFile),
            lyricReferences = localReferences
        )
    val parentDocumentId = navigation.parentDocumentId
        ?: return ContentSidecarReferences(
            metadataReference = localMetadataReference(localFile),
            lyricReferences = localReferences
        )
    val baseUri = navigation.treeUri ?: navigation.baseUri
    val parentChildren = queryDocumentChildren(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId
    )
    val audioBaseName = displayName.substringBeforeLast('.', displayName)
    val directReferences = resolveDocumentLyricReferences(
        children = parentChildren,
        baseName = audioBaseName
    )
    val lyricsDirectory = findManagedSidecarDirectory(parentChildren, "Lyrics")
    val nestedReferences = resolveDocumentLyricReferences(
        children = lyricsDirectory?.let {
            queryDocumentChildren(
                context = context,
                baseUri = baseUri,
                parentDocumentId = it.documentId
            )
        }.orEmpty(),
        baseName = audioBaseName
    )
    val metadataName = displayName + LOCAL_METADATA_SUFFIX
    val metadataReference = findDocumentSidecarChild(parentChildren, metadataName)?.uri
        ?: localMetadataReference(localFile)
    return ContentSidecarReferences(
        metadataReference = metadataReference,
        lyricReferences = NearbyLyricReferences(
            original = nestedReferences.original ?: directReferences.original
                ?: localFiles.original?.absolutePath,
            translated = nestedReferences.translated ?: directReferences.translated
                ?: localFiles.translated?.absolutePath,
            romanized = nestedReferences.romanized ?: directReferences.romanized
                ?: localFiles.romanized?.absolutePath
        )
    )
}

internal fun LocalMediaSupport.localMetadataReference(file: File?): String? {
    if (file == null) return null
    return File(
        file.parentFile ?: return null,
        file.name + LOCAL_METADATA_SUFFIX
    ).takeIf(File::isFile)?.absolutePath
}

internal fun LocalMediaSupport.findNearbyCoverReference(
    context: Context,
    uri: Uri,
    file: File?,
    displayName: String
): String? {
    fun usable(reference: String?): String? {
        return reference?.takeIf { isUsableCoverReference(context, it) }
    }

    if (uri.scheme.equals("content", ignoreCase = true)) {
        val navigation = resolveLocalDocumentNavigation(context, uri)
        val parentId = navigation?.parentDocumentId
        if (navigation != null && parentId != null) {
            val baseUri = navigation.treeUri ?: navigation.baseUri
    val parentChildren = queryDocumentChildren(context, baseUri, parentId)
    val baseName = displayName.substringBeforeLast('.', displayName)
    fun specific(children: Collection<DocumentChild>): String? {
        return imageExtensions.asSequence()
            .flatMap { extension ->
                children.asSequence().filter { child ->
                    !child.isDirectory &&
                        coverSidecarNameMatches(child.displayName, baseName, extension)
                }
            }
            .sortedWith(compareBy({
                if (it.displayName.equals("$baseName.${it.displayName.substringAfterLast('.')}", ignoreCase = true)) {
                    0
                } else {
                    1
                }
            }, DocumentChild::displayName))
            .firstOrNull()
            ?.uri
    }
            usable(specific(parentChildren))?.let { return it }
            val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
            val coversChildren = coversDirectory?.let { directory ->
                queryDocumentChildren(context, baseUri, directory.documentId)
            }.orEmpty()
            usable(specific(coversChildren))?.let { return it }
            usable(coverFileNames.firstNotNullOfOrNull { coverName ->
                imageExtensions.firstNotNullOfOrNull { extension ->
                    parentChildren.firstOrNull { child ->
                        !child.isDirectory && child.displayName.equals(
                            "$coverName.$extension",
                            ignoreCase = true
                        )
                    }?.uri
                }
            })?.let { return it }
        }
    }
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(uri)
    }
    return usable(findNearbyCover(localFile)?.toURI()?.toString())
}

internal fun LocalMediaSupport.documentChildrenContainSource(
    parentChildren: Collection<DocumentChild>,
    sourceUri: Uri,
    displayName: String,
    parentDocumentId: String
): Boolean {
    val sourceDocumentId = try {
        DocumentsContract.getDocumentId(sourceUri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    val containsSource = containsExactDocumentSource(
        documentIds = parentChildren
            .filterNot(DocumentChild::isDirectory)
            .map(DocumentChild::documentId),
        sourceDocumentId = sourceDocumentId
    )
    if (!containsSource) {
        NPLogger.w(
            TAG,
            "SAF 子项枚举未包含当前音频，拒绝创建侧载: " +
                "source=$sourceUri, parent=$parentDocumentId, name=$displayName"
        )
    }
    return containsSource
}

internal fun LocalMediaSupport.findDocumentParentId(context: Context, documentUri: Uri): String? {
    if (isMediaStoreUri(documentUri)) return null
    return try {
        DocumentsContract.findDocumentPath(context.contentResolver, documentUri)
            ?.path
            ?.dropLast(1)
            ?.lastOrNull()
            ?.takeIf(String::isNotBlank)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal fun LocalMediaSupport.resolveDocumentLyricReferences(
    children: Collection<DocumentChild>,
    baseName: String
): NearbyLyricReferences {
    fun find(kind: LyricKind): String? {
        val names = lyricSidecarNames(baseName, kind, lyricExtensions)
        return names.firstNotNullOfOrNull { expectedName ->
            findDocumentSidecarChild(children, expectedName)?.uri
        }
    }
    return NearbyLyricReferences(
        original = find(LyricKind.ORIGINAL),
        translated = find(LyricKind.TRANSLATED),
        romanized = find(LyricKind.ROMANIZED)
    )
}

internal fun LocalMediaSupport.findManagedSidecarDirectory(
    children: Collection<DocumentChild>,
    desiredName: String
): DocumentChild? {
    return children
        .asSequence()
        .filter(DocumentChild::isDirectory)
        .filter { child -> isManagedSidecarDirectoryName(child.displayName, desiredName) }
        .minWithOrNull(
            compareBy(
                { if (canonicalSafName(it.displayName) == canonicalSafName(desiredName)) 0 else 1 },
                { it.displayName.substringAfter("(", "").removeSuffix(")").toIntOrNull() ?: Int.MAX_VALUE }
            )
        )
}

internal fun LocalMediaSupport.findExactManagedSidecarDirectory(
    children: Collection<DocumentChild>,
    desiredName: String
): DocumentChild? {
    return children.asSequence()
        .filter(DocumentChild::isDirectory)
        .filter { child ->
            canonicalSafName(child.displayName) == canonicalSafName(desiredName)
        }
        .minByOrNull(DocumentChild::displayName)
}

internal fun LocalMediaSupport.localCoverSidecarNames(
    baseName: String,
    extension: String,
    stableIdentityKey: String?
): List<String> {
    return listOfNotNull(
        localCoverSidecarName(baseName, extension, stableIdentityKey),
        "$baseName.$extension".takeUnless {
            stableIdentityKey.isNullOrBlank()
        }
    ).distinct()
}

internal fun LocalMediaSupport.managedCoverSidecarNames(
    baseName: String,
    extensions: Collection<String>,
    stableIdentityKey: String?
): Set<String> {
    val normalizedKey = stableIdentityKey?.trim()?.takeIf(String::isNotBlank)
        ?: return emptySet()
    return extensions.mapTo(linkedSetOf()) { extension ->
        localCoverSidecarName(
            baseName = baseName,
            extension = extension,
            stableIdentityKey = normalizedKey
        )
    }
}

internal fun LocalMediaSupport.coverSidecarNameMatches(
    actualName: String,
    baseName: String,
    extension: String
): Boolean {
    val plainName = "$baseName.$extension"
    if (sidecarNameMatches(actualName, plainName)) return true
    val canonical = removeProviderNumberedSidecarSuffix(actualName)
    val suffix = ".$extension"
    if (!canonical.endsWith(suffix, ignoreCase = true)) return false
    val stem = canonical.substring(0, canonical.length - suffix.length)
    val prefix = "$baseName-"
    val hash = stem.removePrefix(prefix)
    return stem.startsWith(prefix) &&
        hash.isNotEmpty() &&
        (hash.length <= 8 || hash.length == 32) &&
        hash.all { it in "0123456789abcdefABCDEF" }
}

internal fun LocalMediaSupport.numberedSidecarNameMatches(actualName: String, canonicalName: String): Boolean {
    return numberedSidecarNameOrdinalOrNull(actualName, canonicalName) != null
}

internal fun LocalMediaSupport.numberedSidecarNameOrdinal(actualName: String, canonicalName: String): Int {
    return numberedSidecarNameOrdinalOrNull(actualName, canonicalName) ?: Int.MAX_VALUE
}

internal fun LocalMediaSupport.numberedSidecarNameOrdinalOrNull(actualName: String, canonicalName: String): Int? {
    val normalizedActualName = Normalizer.normalize(actualName, Normalizer.Form.NFC)
    val normalizedCanonicalName = Normalizer.normalize(canonicalName, Normalizer.Form.NFC)
    parseNumberedSidecarNameOrdinal(
        actualName = normalizedActualName,
        prefix = "$normalizedCanonicalName (",
        suffix = ""
    )?.let { return it }
    val extensionIndex = normalizedCanonicalName.lastIndexOf('.')
    if (extensionIndex <= 0 || extensionIndex == normalizedCanonicalName.lastIndex) return null
    return parseNumberedSidecarNameOrdinal(
        actualName = normalizedActualName,
        prefix = normalizedCanonicalName.substring(0, extensionIndex) + " (",
        suffix = normalizedCanonicalName.substring(extensionIndex)
    )
}

internal fun LocalMediaSupport.parseNumberedSidecarNameOrdinal(
    actualName: String,
    prefix: String,
    suffix: String
): Int? {
    if (
        !actualName.startsWith(prefix, ignoreCase = true) ||
            !actualName.endsWith(suffix, ignoreCase = true)
    ) {
        return null
    }
    val numberEnd = actualName.length - suffix.length
    if (numberEnd <= prefix.length || actualName[numberEnd - 1] != ')') return null
    return actualName.substring(prefix.length, numberEnd - 1).toIntOrNull()
}

internal fun LocalMediaSupport.removeProviderNumberedSidecarSuffix(actualName: String): String {
    val extensionIndex = actualName.lastIndexOf('.')
    if (extensionIndex > 0 && extensionIndex < actualName.lastIndex) {
        val stem = actualName.substring(0, extensionIndex)
        val markerIndex = stem.lastIndexOf(" (")
        if (
            markerIndex >= 0 &&
                stem.endsWith(")") &&
                stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull() != null
        ) {
            return actualName.substring(0, markerIndex) + actualName.substring(extensionIndex)
        }
    }
    val markerIndex = actualName.lastIndexOf(" (")
    return if (
        markerIndex >= 0 &&
            actualName.endsWith(")") &&
            actualName.substring(markerIndex + 2, actualName.length - 1).toIntOrNull() != null
    ) {
        actualName.substring(0, markerIndex)
    } else {
        actualName
    }
}

internal fun <T> LocalMediaSupport.withDocumentMutationLock(
    baseUri: Uri,
    parentDocumentId: String,
    block: () -> T
): T {
    return ManagedDownloadTreeMutationLocks.withLock(baseUri, parentDocumentId, block)
}

internal fun LocalMediaSupport.invalidateDocumentChildrenCache(baseUri: Uri, parentDocumentId: String) {
    val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
    synchronized(documentChildrenCache) {
        documentChildrenCache.remove(cacheKey)
    }
    consecutiveEmptyDocumentRefreshes.remove(cacheKey)
}

internal fun LocalMediaSupport.documentParentCacheKey(baseUri: Uri, parentDocumentId: String): String {
    val treeDocumentId = try {
        DocumentsContract.getTreeDocumentId(baseUri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }?.takeIf(String::isNotBlank)
    val scope = treeDocumentId ?: baseUri.toString()
    return "generation=${LocalStorageRootGeneration.current()}|" +
        "${baseUri.authority.orEmpty()}|$scope|$parentDocumentId"
}

internal fun LocalMediaSupport.cachedDocumentChildren(
    baseUri: Uri,
    parentDocumentId: String
): List<DocumentChild> {
    val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
    return synchronized(documentChildrenCache) {
        documentChildrenCache[cacheKey]
            ?.takeIf { entry ->
                entry.isFresh(System.currentTimeMillis())
            }
            ?.children
            .orEmpty()
    }
}

internal fun LocalMediaSupport.rememberDocumentChild(
    baseUri: Uri,
    parentDocumentId: String,
    child: DocumentChild
) {
    val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
    synchronized(documentChildrenCache) {
        val childrenByUri = LinkedHashMap<String, DocumentChild>()
        documentChildrenCache[cacheKey]
            ?.children
            .orEmpty()
            .forEach { cached -> childrenByUri[cached.uri] = cached }
        childrenByUri[child.uri] = child
        if (!isDocumentChildrenCacheSizeAllowed(childrenByUri.size)) {
            documentChildrenCache.remove(cacheKey)
            return
        }
        rememberDocumentChildrenCacheEntryLocked(
            cacheKey = cacheKey,
            children = childrenByUri.values.toList(),
            cachedAtMs = System.currentTimeMillis(),
            isComplete = documentChildrenCache[cacheKey]?.isComplete ?: false
        )
    }
}
