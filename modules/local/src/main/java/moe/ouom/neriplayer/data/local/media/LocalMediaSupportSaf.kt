package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess

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
import android.provider.OpenableColumns
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import moe.ouom.neriplayer.data.network.DataHttpClients
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.identity.stableKey as songStableKey
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.lyrics.embedded.standardLyricsMetadataKeys
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.net.URLConnection
import java.util.EnumMap
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
    val normalized = expectedValue?.trim()
    return when {
        normalized == null -> !verifyMissing || lacksAllTagValues(propertyMap, keys)
        normalized.isBlank() -> lacksAllTagValues(propertyMap, keys)
        else -> hasAnyExpectedTagValue(propertyMap, keys, normalized)
    }
}

private fun lacksAllTagValues(propertyMap: PropertyMap, keys: List<String>): Boolean {
    return keys.all { key -> propertyMap[key].isNullOrEmpty() }
}

private fun LocalMediaSupport.hasAnyExpectedTagValue(
    propertyMap: PropertyMap,
    keys: List<String>,
    normalized: String
): Boolean {
    return keys.any { key -> hasExpectedTagValue(propertyMap, key, normalized) }
}

internal fun LocalMediaSupport.hasExpectedStandardLyrics(
    propertyMap: PropertyMap,
    audioExtension: String?,
    expectedLyrics: String?
): Boolean {
    val keys = standardLyricsMetadataKeys(audioExtension)
    if (expectedLyrics.isNullOrBlank()) {
        return lacksAllTagValues(propertyMap, keys)
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
        DataHttpClients.shared.newCall(request).execute().use { response ->
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
    val uri = reference.toUri()
    return normalizeEditableCoverMimeType(
        detectEditableCoverMimeType(bytes)
            ?: declaredCoverMimeType(context, uri)
            ?: imageMimeTypeOrNull(URLConnection.guessContentTypeFromName(uri.lastPathSegment ?: reference))
            ?: "image/jpeg"
    )
}

private fun declaredCoverMimeType(context: Context, uri: Uri): String? {
    val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return imageMimeTypeOrNull(declared?.substringBefore(';')?.trim())
}

private fun imageMimeTypeOrNull(mimeType: String?): String? {
    return mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) }
}

private val EDITABLE_COVER_MIME_TYPE_ALIASES = mapOf(
    "image/jpg" to "image/jpeg",
    "image/pjpeg" to "image/jpeg",
    "image/x-ms-bmp" to "image/bmp"
)

internal fun LocalMediaSupport.normalizeEditableCoverMimeType(mimeType: String): String {
    val lowercase = mimeType.lowercase(Locale.ROOT)
    return EDITABLE_COVER_MIME_TYPE_ALIASES[lowercase] ?: lowercase
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

private class CoverSignature(
    val mimeType: String,
    val minimumSize: Int,
    val magicByOffset: Map<Int, ByteArray>
) {
    fun matches(bytes: ByteArray): Boolean {
        return bytes.size >= minimumSize && magicByOffset.all { (offset, magic) -> bytes.hasMagicAt(offset, magic) }
    }
}

private fun ByteArray.hasMagicAt(offset: Int, magic: ByteArray): Boolean {
    return magic.indices.all { index -> this[offset + index] == magic[index] }
}

private fun magicBytes(vararg values: Int) = ByteArray(values.size) { index -> values[index].toByte() }

private fun asciiMagic(value: String) = value.toByteArray(Charsets.US_ASCII)

private val EDITABLE_COVER_SIGNATURES = listOf(
    CoverSignature("image/jpeg", 3, mapOf(0 to magicBytes(0xFF, 0xD8, 0xFF))),
    CoverSignature("image/png", 8, mapOf(0 to magicBytes(0x89, 0x50, 0x4E, 0x47))),
    CoverSignature("image/gif", 6, mapOf(0 to asciiMagic("GIF87a"))),
    CoverSignature("image/gif", 6, mapOf(0 to asciiMagic("GIF89a"))),
    CoverSignature("image/bmp", 2, mapOf(0 to asciiMagic("BM"))),
    CoverSignature("image/webp", 12, mapOf(0 to asciiMagic("RIFF"), 8 to asciiMagic("WEBP")))
)

internal fun LocalMediaSupport.detectEditableCoverMimeType(bytes: ByteArray): String? {
    return EDITABLE_COVER_SIGNATURES.firstOrNull { it.matches(bytes) }?.mimeType
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

    val metadata = ContainerMetadata(
        title = tag.copyOfRange(3, 33).decodeContainerText(),
        artist = tag.copyOfRange(33, 63).decodeContainerText(),
        album = tag.copyOfRange(63, 93).decodeContainerText(),
        year = tag.copyOfRange(93, 97).decodeContainerText()?.extractYear(),
        trackNumber = id3v1TrackNumber(tag)
    )
    return metadata.takeIf { it.hasAnyValue() }
}

private fun id3v1TrackNumber(tag: ByteArray): Int? {
    if (tag[125] != 0.toByte()) return null
    return (tag[126].toInt() and 0xFF).takeIf { it > 0 }
}

private enum class ContainerTextField(val parse: (String) -> Any?) {
    TITLE({ it }),
    ARTIST({ it }),
    ALBUM({ it }),
    ALBUM_ARTIST({ it }),
    COMPOSER({ it }),
    GENRE({ it }),
    YEAR({ value -> with(LocalMediaSupport) { value.extractYear() } }),
    TRACK_NUMBER({ value -> LocalMediaSupport.parseIndexedMetadata(value) }),
    DISC_NUMBER({ value -> LocalMediaSupport.parseIndexedMetadata(value) })
}

private val WAVE_INFO_FIELDS = mapOf(
    "INAM" to ContainerTextField.TITLE,
    "IART" to ContainerTextField.ARTIST,
    "IPRD" to ContainerTextField.ALBUM,
    "IAAR" to ContainerTextField.ALBUM_ARTIST,
    "IENG" to ContainerTextField.COMPOSER,
    "IGNR" to ContainerTextField.GENRE,
    "ICRD" to ContainerTextField.YEAR,
    "ITRK" to ContainerTextField.TRACK_NUMBER,
    "IPRT" to ContainerTextField.DISC_NUMBER
)

private val ID3_FRAME_FIELDS = mapOf(
    "TIT2" to ContainerTextField.TITLE,
    "TT2" to ContainerTextField.TITLE,
    "TPE1" to ContainerTextField.ARTIST,
    "TP1" to ContainerTextField.ARTIST,
    "TALB" to ContainerTextField.ALBUM,
    "TAL" to ContainerTextField.ALBUM,
    "TPE2" to ContainerTextField.ALBUM_ARTIST,
    "TP2" to ContainerTextField.ALBUM_ARTIST,
    "TCOM" to ContainerTextField.COMPOSER,
    "TCM" to ContainerTextField.COMPOSER,
    "TCON" to ContainerTextField.GENRE,
    "TCO" to ContainerTextField.GENRE,
    "TDRC" to ContainerTextField.YEAR,
    "TYER" to ContainerTextField.YEAR,
    "TYE" to ContainerTextField.YEAR,
    "TRCK" to ContainerTextField.TRACK_NUMBER,
    "TRK" to ContainerTextField.TRACK_NUMBER,
    "TPOS" to ContainerTextField.DISC_NUMBER,
    "TPA" to ContainerTextField.DISC_NUMBER
)

private class ContainerMetadataCollector {
    private val values = EnumMap<ContainerTextField, Any>(ContainerTextField::class.java)

    fun offer(field: ContainerTextField?, raw: String?) {
        if (field == null || raw == null || field in values) return
        field.parse(raw)?.let { values[field] = it }
    }

    fun build(): ContainerMetadata? = with(LocalMediaSupport) {
        ContainerMetadata(
            title = values[ContainerTextField.TITLE] as String?,
            artist = values[ContainerTextField.ARTIST] as String?,
            album = values[ContainerTextField.ALBUM] as String?,
            albumArtist = values[ContainerTextField.ALBUM_ARTIST] as String?,
            composer = values[ContainerTextField.COMPOSER] as String?,
            genre = values[ContainerTextField.GENRE] as String?,
            year = values[ContainerTextField.YEAR] as Int?,
            trackNumber = values[ContainerTextField.TRACK_NUMBER] as Int?,
            discNumber = values[ContainerTextField.DISC_NUMBER] as Int?
        ).takeIf { it.hasAnyValue() }
    }
}

internal fun LocalMediaSupport.parseWaveInfoMetadata(bytes: ByteArray): ContainerMetadata? {
    val collector = ContainerMetadataCollector()
    var offset = 0
    while (offset + 8 <= bytes.size) {
        val chunkId = bytes.readFourCc(offset) ?: break
        val chunkSize = bytes.readLittleEndianUInt32(offset + 4).coerceAtMost((bytes.size - offset - 8).toLong())
        val valueStart = offset + 8
        val valueEnd = valueStart + chunkSize.toInt()
        collector.offer(WAVE_INFO_FIELDS[chunkId], bytes.copyOfRange(valueStart, valueEnd).decodeContainerText())
        offset = valueEnd + (chunkSize.toInt() and 1)
    }
    return collector.build()
}

private const val ID3_HEADER_SIZE = 10

internal fun LocalMediaSupport.parseId3Metadata(bytes: ByteArray): ContainerMetadata? {
    if (!isId3TagHeader(bytes)) return null
    val majorVersion = bytes[3].toInt() and 0xFF
    val limit = minOf(bytes.size, ID3_HEADER_SIZE + bytes.readSynchsafeInt(6))
    var offset = id3FirstFrameOffset(bytes, majorVersion, limit) ?: return null
    val frameHeaderSize = id3FrameHeaderSize(majorVersion)
    val collector = ContainerMetadataCollector()
    while (offset + frameHeaderSize <= limit) {
        val frameId = id3FrameId(bytes, offset, majorVersion) ?: break
        val frameDataStart = offset + frameHeaderSize
        val frameSize = id3FrameSize(bytes, offset, majorVersion)
        if (!id3FrameFits(frameSize, frameDataStart, limit)) break
        val frameDataEnd = frameDataStart + frameSize
        collector.offer(ID3_FRAME_FIELDS[frameId], decodeId3TextFrame(bytes.copyOfRange(frameDataStart, frameDataEnd)))
        offset = frameDataEnd
    }
    return collector.build()
}

private fun isId3TagHeader(bytes: ByteArray): Boolean {
    return bytes.size >= ID3_HEADER_SIZE && bytes.readAscii(0, 3) == "ID3"
}

private fun id3FirstFrameOffset(bytes: ByteArray, majorVersion: Int, limit: Int): Int? {
    if (!id3HasExtendedHeader(bytes, majorVersion)) return ID3_HEADER_SIZE
    if (ID3_HEADER_SIZE + 4 > limit) return null
    val extendedSize = id3ExtendedHeaderSize(bytes, majorVersion)
    if (extendedSize < id3MinimumExtendedHeaderSize(majorVersion) || extendedSize > limit - ID3_HEADER_SIZE) {
        return null
    }
    return ID3_HEADER_SIZE + extendedSize.toInt()
}

private fun id3HasExtendedHeader(bytes: ByteArray, majorVersion: Int): Boolean {
    return majorVersion > 2 && (bytes[5].toInt() and 0x40) != 0
}

private fun id3ExtendedHeaderSize(bytes: ByteArray, majorVersion: Int): Long {
    return if (majorVersion >= 4) {
        bytes.readSynchsafeInt(ID3_HEADER_SIZE).toLong()
    } else {
        // v2.3 的长度不含 size 字段本身，v2.4 则包含
        bytes.readBigEndianInt(ID3_HEADER_SIZE).toLong() + 4L
    }
}

private fun id3MinimumExtendedHeaderSize(majorVersion: Int): Long = if (majorVersion >= 4) 6L else 10L

private fun id3FrameHeaderSize(majorVersion: Int): Int = if (majorVersion == 2) 6 else 10

private fun id3FrameId(bytes: ByteArray, offset: Int, majorVersion: Int): String? {
    val frameId = if (majorVersion == 2) {
        bytes.readAscii(offset, 3)
    } else {
        bytes.readFourCc(offset)?.trimEnd(NUL_CHAR, ' ')
    }
    return frameId?.takeIf(String::isNotBlank)
}

private fun id3FrameSize(bytes: ByteArray, offset: Int, majorVersion: Int): Int {
    return when {
        majorVersion >= 4 -> bytes.readSynchsafeInt(offset + 4)
        majorVersion == 2 -> bytes.readBigEndianInt24(offset + 3)
        else -> bytes.readBigEndianInt(offset + 4)
    }
}

private fun id3FrameFits(frameSize: Int, frameDataStart: Int, limit: Int): Boolean {
    return frameSize > 0 && frameSize <= limit - frameDataStart
}

internal fun LocalMediaSupport.mergeContainerMetadata(
    primary: ContainerMetadata?,
    fallback: ContainerMetadata?
): ContainerMetadata? {
    if (primary == null) return fallback
    if (fallback == null) return primary
    return primary.withMissingValuesFrom(fallback)
}

private fun ContainerMetadata.withMissingValuesFrom(fallback: ContainerMetadata): ContainerMetadata {
    return ContainerMetadata(
        title = firstPresent(title, fallback.title),
        artist = firstPresent(artist, fallback.artist),
        album = firstPresent(album, fallback.album),
        albumArtist = firstPresent(albumArtist, fallback.albumArtist),
        composer = firstPresent(composer, fallback.composer),
        genre = firstPresent(genre, fallback.genre),
        year = firstPresent(year, fallback.year),
        trackNumber = firstPresent(trackNumber, fallback.trackNumber),
        discNumber = firstPresent(discNumber, fallback.discNumber)
    )
}

private fun <T : Any> firstPresent(primary: T?, fallback: T?): T? = primary ?: fallback

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
    val prefixes = listOfNotNull(resolved?.file?.absolutePath, uri.toString()).map { "$it|" }
    synchronized(localCoverLookupCache) {
        localCoverLookupCache.keys.removeAll { key -> prefixes.any(key::startsWith) }
    }
    embeddedCoverCacheKeys(uri.toString(), resolved?.resolvedPath).forEach { cacheKey ->
        deleteEmbeddedCoverCache(context, cacheKey)
    }
}

private fun LocalMediaSupport.deleteEmbeddedCoverCache(context: Context, cacheKey: String) {
    val cacheFile = embeddedCoverFile(context, cacheKey)
    if (cacheFile.isFile && !cacheFile.delete()) {
        NPLogger.w(TAG, "clear stale embedded cover cache failed: ${cacheFile.name}")
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
    findCachedEmbeddedCover(context, uriKey)?.let { return it }
    val file = embeddedCoverFile(context, uriKey)
    val parent = file.parentFile ?: return null
    if (!ensureEmbeddedCoverDirectory(parent)) return null
    val cacheBytes = compactEmbeddedCoverForCache(embeddedPicture) ?: return null
    writeEmbeddedCoverCache(file, parent, cacheBytes)
    return file.toURI().toString()
}

private fun LocalMediaSupport.ensureEmbeddedCoverDirectory(directory: File): Boolean {
    if (directory.isDirectory || directory.mkdirs()) return true
    NPLogger.w(TAG, "create embedded cover cache directory failed: ${directory.path}")
    return false
}

private fun writeEmbeddedCoverCache(file: File, parent: File, bytes: ByteArray) {
    val tempFile = File(parent, ".${file.name}.tmp")
    tempFile.writeBytes(bytes)
    if (!tempFile.renameTo(file)) {
        file.writeBytes(bytes)
        tempFile.delete()
    }
}

internal fun LocalMediaSupport.compactEmbeddedCoverForCache(sourceBytes: ByteArray): ByteArray? {
    val bounds = decodeEmbeddedCoverBounds(sourceBytes) ?: return null
    if (sourceBytes.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) return sourceBytes
    return downsampleEmbeddedCoverForCache(sourceBytes, bounds)
}

private fun decodeEmbeddedCoverBounds(sourceBytes: ByteArray): BitmapFactory.Options? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    return bounds.takeIf { it.outWidth > 0 && it.outHeight > 0 }
}

private fun LocalMediaSupport.downsampleEmbeddedCoverForCache(
    sourceBytes: ByteArray,
    bounds: BitmapFactory.Options
): ByteArray? {
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
    file: File? = null,
    forMutation: Boolean = false
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
    val mutationParentChildren = if (forMutation) {
        queryDocumentChildrenForMutation(context, baseUri, parentDocumentId)
    } else null
    val parentChildren = mutationParentChildren ?: queryDocumentChildren(
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
        ),
        mutationParentChildren = mutationParentChildren
    )
}

fun LocalMediaSupport.selectCachedEditableMetadataReference(
    snapshot: DownloadLibrarySnapshot?,
    sourceReference: String,
    displayName: String
): String? {
    if (snapshot?.rootEntriesComplete != true) return null
    val audio = snapshot.audioEntriesByLookupKey[sourceReference]
    if (audio == null || !isCachedEditableAudio(audio, sourceReference, displayName)) return null
    return contentMetadataReference(snapshot.metadataEntriesByAudioName[displayName], displayName)
}

private fun isCachedEditableAudio(
    audio: DownloadLibraryEntry,
    sourceReference: String,
    displayName: String
): Boolean {
    return !audio.isPendingAudioWrite && audio.name == displayName &&
        (sourceReference == audio.reference || sourceReference == audio.mediaUri)
}

private fun contentMetadataReference(metadata: DownloadLibraryEntry?, displayName: String): String? {
    if (metadata == null || metadata.name != displayName + LOCAL_METADATA_SUFFIX) return null
    return metadata.reference.takeIf { it.startsWith("content://", ignoreCase = true) }
}

fun LocalMediaSupport.resolveCachedEditableMetadataReference(
    context: Context,
    sourceUri: Uri,
    displayName: String
): String? {
    if (!sourceUri.scheme.equals("content", ignoreCase = true) ||
        !shouldUseDocumentSidecarMutation(sourceUri) &&
        !DocumentsContract.isDocumentUri(context, sourceUri) &&
        !DocumentsContract.isTreeUri(sourceUri)
    ) return null
    val reference = selectCachedEditableMetadataReference(
        snapshot = LocalMediaHostAccess.downloads.cachedDownloadLibrarySnapshot(
            context, restorePersisted = false
        ),
        sourceReference = sourceUri.toString(),
        displayName = displayName
    ) ?: return null
    val metadataUri = reference.toUri()
    if (metadataUri.authority != sourceUri.authority) return null
    val sourceParentId = findDocumentParentId(context, sourceUri) ?: return null
    if (sourceParentId != findDocumentParentId(context, metadataUri)) return null
    fun actualName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    if (actualName(sourceUri) != displayName ||
        actualName(metadataUri) != displayName + LOCAL_METADATA_SUFFIX
    ) return null
    return reference
}

internal fun LocalMediaSupport.resolveCachedEditableLyricsReferences(
    context: Context,
    sourceUri: Uri,
    displayName: String
): ContentSidecarReferences? {
    val metadataReference = resolveCachedEditableMetadataReference(
        context, sourceUri, displayName
    ) ?: return null
    val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
    val parentId = navigation.parentDocumentId ?: return null
    val baseUri = navigation.treeUri ?: navigation.baseUri
    val cachedDirectoryUri = synchronized(documentChildrenCache) {
        documentChildrenCache[documentParentCacheKey(baseUri, parentId)]
            ?.children
            ?.let { children -> findManagedSidecarDirectory(children, "Lyrics") }
            ?.uri
    }?.toUri()
    val indexedDirectoryUri = LocalMediaHostAccess.downloads.cachedDownloadLibrarySnapshot(
        context, restorePersisted = false
    )?.lyricEntriesByName?.values
        ?.firstOrNull { entry ->
            entry.reference.startsWith("content://", ignoreCase = true) &&
                entry.reference.toUri().authority == sourceUri.authority
        }?.reference?.toUri()
        ?.let { lyricUri -> findDocumentParentId(context, lyricUri) }
        ?.let { directoryId -> buildDocumentReferenceUri(baseUri, directoryId) }
    val sourceId = runCatching { DocumentsContract.getDocumentId(sourceUri) }
        .getOrNull() ?: return null
    val source = documentChildFromUri(context, sourceUri, false)
        ?.takeIf { child ->
            !child.isDirectory && child.documentId == sourceId &&
                child.displayName == displayName
        } ?: return null
    if (findDocumentParentId(context, sourceUri) != parentId) return null
    val lyricsDirectory = listOfNotNull(cachedDirectoryUri, indexedDirectoryUri)
        .distinct()
        .firstNotNullOfOrNull { candidate ->
            documentChildFromUri(context, candidate, true)?.takeIf { child ->
                child.isDirectory && isManagedSidecarDirectoryName(child.displayName, "Lyrics") &&
                    findDocumentParentId(context, candidate) == parentId
            }
        } ?: return null
    return ContentSidecarReferences(
        metadataReference = metadataReference,
        lyricReferences = NearbyLyricReferences(null, null, null),
        mutationParentChildren = listOf(source, lyricsDirectory)
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
    displayName: String,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null
): String? {
    fun usable(reference: String?): String? {
        return reference?.takeIf { isUsableCoverReference(context, it) }
    }

    if (uri.scheme.equals("content", ignoreCase = true)) {
        val navigation = resolveLocalDocumentNavigation(context, uri)
        val parentId = navigation?.parentDocumentId
        if (navigation != null && parentId != null) {
            val baseUri = navigation.treeUri ?: navigation.baseUri
    val parentChildren = parentChildrenForMutation ?: queryDocumentChildren(context, baseUri, parentId)
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

fun LocalMediaSupport.invalidateDocumentChildrenCache(baseUri: Uri, parentDocumentId: String) {
    val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
    synchronized(documentChildrenCache) {
        documentChildrenCache.remove(cacheKey)
    }
    consecutiveEmptyDocumentRefreshes.remove(cacheKey)
}

fun LocalMediaSupport.documentParentCacheKey(baseUri: Uri, parentDocumentId: String): String {
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
