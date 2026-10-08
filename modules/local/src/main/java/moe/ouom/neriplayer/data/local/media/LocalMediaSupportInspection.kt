package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaCompanionTransaction
import moe.ouom.neriplayer.data.local.media.metadata.writeTextContent
import moe.ouom.neriplayer.data.local.media.metadata.writeTextFileAtomically

import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess

import moe.ouom.neriplayer.data.model.local.LocalMediaDetails

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentNavigationCacheEntry
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.AudioTrackTechInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.RetrieverTextMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ResolvedInspectableLocalMedia
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.TagLibMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.QueriedContentInfo
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.system.Os
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.lyrics.embedded.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.lyrics.embedded.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.lyrics.embedded.translatedLyricsMetadataKeys
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.max
import androidx.core.net.toUri

internal fun LocalMediaSupport.resolveInspectableLocalMedia(
    context: Context,
    uri: Uri,
    allowDescriptorFallback: Boolean = true
): ResolvedInspectableLocalMedia {
    require(uri.isSupportedLocalMediaUri()) { "Unsupported local media uri: $uri" }
    val queried = queryContentInfo(context, uri)
    val resolvedPath = resolveInspectablePath(context, uri, queried, allowDescriptorFallback)
    val file = existingFileOrNull(resolvedPath)
    val playableUri = inspectablePlayableUri(uri, file)
    val displayName = listOfNotNull(
        file?.name,
        queried.displayName,
        resolvedPath?.substringAfterLast(File.separatorChar),
        playableUri.lastPathSegment
    ).firstOrNull() ?: uri.toString()
    val fallbackTitle = displayName.substringBeforeLast('.').ifBlank {
        context.getString(CoreCommonR.string.local_files)
    }
    return ResolvedInspectableLocalMedia(
        queried = queried,
        resolvedPath = resolvedPath,
        file = file,
        playableUri = playableUri,
        displayName = displayName,
        fallbackTitle = fallbackTitle,
        fileExtension = inspectableFileExtension(file, displayName)
    )
}

private fun LocalMediaSupport.resolveInspectablePath(
    context: Context,
    uri: Uri,
    queried: QueriedContentInfo,
    allowDescriptorFallback: Boolean
): String? {
    return directFilePath(uri)
        ?: queried.filePath
        ?: if (allowDescriptorFallback) resolvePathFromDescriptor(context, uri) else null
}

private fun existingFileOrNull(path: String?): File? = path?.let(::File)?.takeIf(File::exists)

private fun inspectablePlayableUri(uri: Uri, file: File?): Uri {
    val keepsSourceUri = uri.scheme.equals("content", ignoreCase = true) ||
        uri.scheme.equals("android.resource", ignoreCase = true)
    if (keepsSourceUri) return uri
    return file?.let(Uri::fromFile) ?: uri
}

private fun inspectableFileExtension(file: File?, displayName: String): String? {
    return listOfNotNull(file?.extension, displayName.substringAfterLast('.', "")).firstOrNull(String::isNotBlank)
}

internal fun LocalMediaSupport.buildQuickLocalMediaDetails(
    context: Context,
    sourceUri: Uri,
    resolved: ResolvedInspectableLocalMedia,
    audioTrackTechInfo: AudioTrackTechInfo?
): LocalMediaDetails {
    val selectedMetadata = selectQuickLocalMetadata(
        title = pickReadableLocalTitle(
            sourceUri = sourceUri,
            fallbackTitle = resolved.fallbackTitle,
            resolved.queried.title
        ) ?: resolved.fallbackTitle,
        queriedArtist = resolved.queried.artist,
        queriedAlbum = resolved.queried.album,
        queriedDurationMs = resolved.queried.durationMs,
        unknownArtistLabel = context.getString(CoreCommonR.string.music_unknown_artist),
        defaultAlbumLabel = context.getString(CoreCommonR.string.local_files)
    )
    val techInfo = audioTrackTechInfo ?: UNKNOWN_AUDIO_TRACK_TECH_INFO
    return LocalMediaDetails(
        sourceUri = sourceUri,
        displayName = resolved.displayName,
        title = selectedMetadata.title,
        artist = selectedMetadata.artist,
        album = normalizeLocalAlbumIdentity(
            selectedMetadata.album,
            selectedMetadata.usesFallbackAlbum
        ),
        usesFallbackAlbum = selectedMetadata.usesFallbackAlbum,
        albumArtist = null,
        composer = null,
        genre = null,
        year = null,
        trackNumber = null,
        discNumber = null,
        durationMs = quickDurationMs(selectedMetadata.durationMs, techInfo.durationMs),
        fileExtension = resolved.fileExtension,
        mimeType = resolved.queried.mimeType,
        audioMimeType = techInfo.audioMimeType,
        bitrateKbps = techInfo.bitrateKbps,
        sampleRateHz = techInfo.sampleRateHz,
        channelCount = techInfo.channelCount,
        bitsPerSample = null,
        sizeBytes = resolved.knownSizeBytes(),
        lastModifiedMs = resolved.knownLastModifiedMs(),
        filePath = resolved.file?.absolutePath,
        coverUri = null,
        coverSource = null,
        lyricContent = null,
        lyricPath = null,
        lyricSource = null,
        originalTitle = selectedMetadata.title,
        originalArtist = selectedMetadata.artist,
        embeddedCover = false,
        romanizedLyricContent = null
    )
}

private val UNKNOWN_AUDIO_TRACK_TECH_INFO = AudioTrackTechInfo(null, null, null, null, null)

private fun quickDurationMs(selectedDurationMs: Long, trackDurationMs: Long?): Long {
    return selectedDurationMs.takeIf { it > 0L } ?: trackDurationMs ?: 0L
}

private fun ResolvedInspectableLocalMedia.knownSizeBytes(): Long? = queried.sizeBytes ?: file?.length()

private fun ResolvedInspectableLocalMedia.knownLastModifiedMs(): Long? = queried.lastModifiedMs ?: file?.lastModified()

internal fun LocalMediaSupport.readLocalMetadataSidecar(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String
): LocalMetadataSidecar? {
    val reference = resolveLocalMetadataReference(
        context = context,
        sourceUri = sourceUri,
        file = file,
        displayName = displayName
    )?.takeUnless(::isMediaStoreSidecarReference) ?: return null
    readTextContent(context, reference)
        ?.let { raw -> parseLocalMetadataSidecar(reference, raw) }
        ?.let { return it }

    // SAF 文件可能同时暴露出不可直接读取的绝对路径，失败后重新走文档树
    if (sourceUri.scheme.equals("content", ignoreCase = true) && file != null) {
        val documentReference = resolveLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = null,
            displayName = displayName
        )?.takeUnless { it == reference || isMediaStoreSidecarReference(it) }
        if (documentReference != null) {
            readTextContent(context, documentReference)
                ?.let { raw -> parseLocalMetadataSidecar(documentReference, raw) }
                ?.let { return it }
        }
    }
    return null
}

internal fun LocalMediaSupport.updateLyricMetadataField(
    root: JSONObject,
    matchedKey: String,
    originalKey: String,
    matchedValue: String?,
    originalValue: String?,
    clearMissing: Boolean
) {
    matchedValue?.let { root.put(matchedKey, it) }
    originalValue?.let { root.put(originalKey, it) }
    if (clearMissing && matchedValue == null && originalValue == null) {
        root.remove(matchedKey)
        root.remove(originalKey)
    }
}

fun LocalMediaSupport.writeLocalLyricsMetadata(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String,
    song: SongItem,
    knownReference: String? = null,
    writeFullMetadata: Boolean = false,
    writeLyricFields: Boolean = true,
    coverReference: String? = null,
    clearCoverReference: Boolean = false,
    companionTransaction: LocalMediaCompanionTransaction? = null,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null,
    useVerifiedExistingReference: Boolean = false
): Boolean {
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    val localMetadataReference = knownReference
        ?.takeUnless(::isMediaStoreSidecarReference)
        ?.takeUnless { reference ->
            shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
        }
    val navigation = sourceUri.takeIf { uri ->
        uri.scheme.equals("content", ignoreCase = true)
    }?.let { uri -> resolveLocalDocumentNavigation(context, uri) }
    val parentId = navigation?.parentDocumentId
    if (
        navigation != null && parentId != null &&
            (localFile == null || isMediaStoreUri(sourceUri))
    ) {
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val metadataName = displayName + LOCAL_METADATA_SUFFIX
        val written = withDocumentMutationLock(baseUri, parentId) {
            if (useVerifiedExistingReference && localMetadataReference != null) {
                if (readTextContent(context, localMetadataReference) == null) {
                    return@withDocumentMutationLock false
                }
                return@withDocumentMutationLock writeLocalLyricsMetadataReference(
                    context = context,
                    reference = localMetadataReference,
                    file = null,
                    song = song,
                    writeFullMetadata = writeFullMetadata,
                    writeLyricFields = writeLyricFields,
                    coverReference = coverReference,
                    clearCoverReference = clearCoverReference,
                    companionTransaction = companionTransaction
                )
            }
            val parentChildren = parentChildrenForMutation ?: queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId
            ) ?: return@withDocumentMutationLock false
            if (!documentChildrenContainSource(
                    parentChildren = parentChildren,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    parentDocumentId = parentId
                )
            ) {
                return@withDocumentMutationLock false
            }
            val targetChild = parentChildren.firstOrNull { child ->
                child.uri == localMetadataReference &&
                    !child.isDirectory &&
                    canonicalSafName(child.displayName) == canonicalSafName(metadataName)
            }
                ?: findDocumentSidecarChild(parentChildren, metadataName)
                ?: createDocumentSidecarForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = parentId,
                    mimeType = "application/json",
                    displayName = metadataName,
                    parentChildren
                )
                ?: return@withDocumentMutationLock false
            if (targetChild.createdByCurrentMutation) {
                companionTransaction?.created(targetChild.uri)
            }
            writeLocalLyricsMetadataReference(
                context = context,
                reference = targetChild.uri,
                file = null,
                song = song,
                writeFullMetadata = writeFullMetadata,
                writeLyricFields = writeLyricFields,
                coverReference = coverReference,
                clearCoverReference = clearCoverReference,
                companionTransaction = companionTransaction
            )
        }
        clearLyricsLookupCache()
        return written
    }
    val metadataReference = localMetadataReference
        ?: resolveLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = localFile,
            displayName = displayName
        )
    val targetReference = metadataReference ?: createLocalMetadataReference(
        context = context,
        sourceUri = sourceUri,
        file = localFile,
        displayName = displayName,
        companionTransaction = companionTransaction
    ) ?: return false
    val written = writeLocalLyricsMetadataReference(
        context = context,
        reference = targetReference,
        file = localFile,
        song = song,
        writeFullMetadata = writeFullMetadata,
        writeLyricFields = writeLyricFields,
        coverReference = coverReference,
        clearCoverReference = clearCoverReference,
        companionTransaction = companionTransaction
    )
    clearLyricsLookupCache()
    return written
}

internal fun LocalMediaSupport.writeLocalLyricsMetadataReference(
    context: Context,
    reference: String,
    file: File?,
    song: SongItem,
    writeFullMetadata: Boolean = false,
    writeLyricFields: Boolean = true,
    coverReference: String? = null,
    clearCoverReference: Boolean = false,
    companionTransaction: LocalMediaCompanionTransaction? = null
): Boolean {
    val existingRaw = readTextContent(context, reference)
    if (existingRaw == null && isReadableLocalReference(context, reference)) {
        NPLogger.w(TAG, "拒绝覆盖无法读取的本地 metadata sidecar: $reference")
        return false
    }
    val updatedRaw = if (writeFullMetadata) {
        buildEditableLocalMetadataJson(
            existingRaw = existingRaw,
            song = song,
            writeLyrics = writeLyricFields,
            coverReference = coverReference,
            clearCoverReference = clearCoverReference
        )
    } else {
        buildLocalLyricsMetadataJson(
            existingRaw = existingRaw,
            song = song,
            clearMissingLyricFields = false
        )
    }
    val bytes = updatedRaw.toByteArray(Charsets.UTF_8)
    return if (companionTransaction != null) {
        companionTransaction.write(reference, bytes, created = existingRaw == null)
    } else writeLocalMetadataReference(context, reference, file, updatedRaw)
}

internal fun LocalMediaSupport.isReadableLocalReference(context: Context, reference: String): Boolean {
    if (reference.startsWith("/")) {
        return File(reference).isFile
    }
    val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
    val isDocumentUri = try {
        DocumentsContract.isDocumentUri(context, uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        false
    }
    if (!isDocumentUri) return false
    return when (
        val result = ManagedDownloadReferenceIo.inspect(context, uri.toString())
    ) {
        ManagedDownloadReferenceIo.AccessResult.Accessible -> true
        ManagedDownloadReferenceIo.AccessResult.Missing -> false
        ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
            throw SecurityException("local metadata permission lost: $uri")
        }
        is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> throw result.error
    }
}

internal fun LocalMediaSupport.resolveLocalMetadataReference(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String
): String? {
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    localFile?.let { resolvedFile ->
        val target = File(
            resolvedFile.parentFile ?: return@let,
            resolvedFile.name + LOCAL_METADATA_SUFFIX
        )
        if (shouldProbeAbsoluteMetadataSidecar(sourceUri, target)) {
            return target.absolutePath
        }
    }
    val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
    val parentChildren = queryDocumentChildren(
        context = context,
        baseUri = navigation.treeUri ?: navigation.baseUri,
        parentDocumentId = navigation.parentDocumentId
    )
    val metadataName = displayName + LOCAL_METADATA_SUFFIX
    return findDocumentSidecarChild(parentChildren, metadataName)?.uri ?: localFile?.let { resolvedFile ->
        val target = resolvedFile.parentFile
            ?.let { parent -> File(parent, resolvedFile.name + LOCAL_METADATA_SUFFIX) }
            ?: return@let null
        target.absolutePath.takeIf {
            shouldProbeAbsoluteMetadataSidecar(sourceUri, target)
        }
    }
}

internal fun LocalMediaSupport.createLocalMetadataReference(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String,
    companionTransaction: LocalMediaCompanionTransaction? = null
): String? {
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    if (localFile != null && !isMediaStoreUri(sourceUri)) {
        return File(
            localFile.parentFile ?: return null,
            localFile.name + LOCAL_METADATA_SUFFIX
        ).absolutePath
    }
    val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
    val parentId = navigation.parentDocumentId ?: return null
    val baseUri = navigation.treeUri ?: navigation.baseUri
    val metadataName = displayName + LOCAL_METADATA_SUFFIX
    val documentReference = withDocumentMutationLock(baseUri, parentId) {
        val parentChildren = queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentId
        ) ?: return@withDocumentMutationLock null
        if (!documentChildrenContainSource(
                parentChildren = parentChildren,
                sourceUri = sourceUri,
                displayName = displayName,
                parentDocumentId = parentId
            )
        ) {
            return@withDocumentMutationLock null
        }
        val child = findDocumentSidecarChild(parentChildren, metadataName)
            ?: createDocumentSidecarForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId,
                mimeType = "application/json",
                displayName = metadataName
            )
        child?.also { created ->
            if (created.createdByCurrentMutation) companionTransaction?.created(created.uri)
        }?.uri
    }
    if (documentReference != null) return documentReference
    if (isMediaStoreUri(sourceUri)) return null
    return localFile?.let { resolvedFile ->
        resolvedFile.parentFile
            ?.let { parent -> File(parent, resolvedFile.name + LOCAL_METADATA_SUFFIX) }
            ?.absolutePath
    }
}

internal fun LocalMediaSupport.writeLocalMetadataReference(
    context: Context,
    reference: String,
    file: File?,
    content: String
): Boolean {
    if (file != null && reference.startsWith("/")) {
        return writeTextFileAtomically(File(reference), content)
    }
    return writeTextContent(context, reference, content)
}

fun LocalMediaSupport.resolveLocalDocumentNavigation(
    context: Context,
    uri: Uri
): LocalDocumentNavigation? {
    if (!uri.scheme.equals("content", ignoreCase = true)) return null
    val cacheKey = "generation=${LocalStorageRootGeneration.current()}|$uri"
    synchronized(documentNavigationCache) {
        documentNavigationCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs <= DOCUMENT_CHILDREN_CACHE_TTL_MS) {
                return cached.navigation
            }
            documentNavigationCache.remove(cacheKey)
        }
    }
    val navigation = if (isMediaStoreUri(uri)) {
        resolveMediaStoreDocumentNavigation(context, uri)
    } else {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val treeUri = try {
            val authority = uri.authority
            if (authority == null) {
                null
            } else {
                treeDocumentId?.let { DocumentsContract.buildTreeDocumentUri(authority, it) }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val documentUri = if (treeUri != null && documentId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            uri
        }
        val providerParentId = findDocumentParentId(context, documentUri)
        LocalDocumentNavigation(
            baseUri = uri,
            treeUri = treeUri,
            parentDocumentId = providerParentId ?: treeDocumentId
        )
    }
    synchronized(documentNavigationCache) {
        documentNavigationCache[cacheKey] = DocumentNavigationCacheEntry(
            navigation = navigation,
            cachedAtMs = System.currentTimeMillis()
        )
    }
    return navigation
}

internal fun LocalMediaSupport.resolveMediaStoreDocumentNavigation(
    context: Context,
    sourceUri: Uri
): LocalDocumentNavigation? {
    val relativePath = queryContentInfo(context, sourceUri).relativePath
        ?.trim()
        ?.trim('/')
        ?.takeIf(String::isNotBlank)
        ?: return null
    val treeUri = resolveExternalStorageTreeUri(context, relativePath) ?: return null
    val treeDocumentId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return null
    val rootSegments = documentPathSegments(treeDocumentId)
    val relativeSegments = documentPathSegments(relativePath)
    val targetSegments = when {
        rootSegments.isNotEmpty() && relativeSegments.startsWithSegments(rootSegments) -> {
            relativeSegments.drop(rootSegments.size)
        }
        rootSegments.isEmpty() -> relativeSegments
        else -> return null
    }
    var parentDocumentId = treeDocumentId
    targetSegments.forEach { segment ->
        val child = queryDocumentChildren(
            context = context,
            baseUri = treeUri,
            parentDocumentId = parentDocumentId
        ).firstOrNull { it.isDirectory && it.displayName == segment }
            ?: return null
        parentDocumentId = child.documentId
    }
    return LocalDocumentNavigation(
        baseUri = treeUri,
        treeUri = treeUri,
        parentDocumentId = parentDocumentId
    )
}

internal fun LocalMediaSupport.isWritableDocumentUri(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } == true
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal fun LocalMediaSupport.resolveExternalStorageTreeUri(
    context: Context,
    relativePath: String
): Uri? {
    val candidates = buildList {
        LocalMediaHostAccess.downloads.configuredDirectoryUri()
            ?.let { runCatching { it.toUri() }.getOrNull() }
            ?.let(::add)
        context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission || it.isWritePermission }
            .map { it.uri }
            .forEach(::add)
    }.filter { uri ->
        uri.authority == "com.android.externalstorage.documents" &&
            runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
    }.distinctBy(Uri::toString)

    val relativeSegments = documentPathSegments(relativePath)
    return candidates.firstOrNull { treeUri ->
        val treeId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val rootSegments = documentPathSegments(treeId)
        rootSegments.isEmpty() || relativeSegments.startsWithSegments(rootSegments)
    }
}

internal fun LocalMediaSupport.documentPathSegments(value: String?): List<String> {
    val decoded = Uri.decode(value.orEmpty())
    val path = decoded.substringAfter(':', decoded)
    return path.split('/').filter(String::isNotBlank)
}

internal fun LocalMediaSupport.readLimitedTextFile(file: File): ByteArray {
    val length = file.length()
    require(length <= MAX_LOCAL_LYRIC_BYTES) { "text file is too large: $length bytes" }
    return file.inputStream().use(::readLimitedTextStream)
}

internal fun LocalMediaSupport.readLimitedTextStream(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        require(total <= MAX_LOCAL_LYRIC_BYTES) { "text stream is too large: $total bytes" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun LocalMediaSupport.decodeTextBytes(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""

    detectBomCharset(bytes)?.let { (charset, offset) ->
        return bytes.copyOfRange(offset, bytes.size).toString(charset).normalizeDecodedText()
    }

    val utf8Text = bytes.toString(StandardCharsets.UTF_8).normalizeDecodedText()
    if (!utf8Text.contains('\uFFFD')) {
        return utf8Text
    }
    return decodeTextWithBestScoringCharset(bytes)
}

private val TEXT_DECODING_CHARSETS: List<Charset> by lazy {
    listOfNotNull(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_16LE,
        StandardCharsets.UTF_16BE,
        supportedCharsetOrNull("GB18030"),
        supportedCharsetOrNull("GBK")
    ).distinct()
}

internal fun supportedCharsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

private fun LocalMediaSupport.decodeTextWithBestScoringCharset(bytes: ByteArray): String? {
    return TEXT_DECODING_CHARSETS
        .maxByOrNull { charset -> scoreDecodedText(bytes.toString(charset).normalizeDecodedText()) }
        ?.let { bytes.toString(it).normalizeDecodedText() }
}

internal fun LocalMediaSupport.queryContentInfo(context: Context, uri: Uri): QueriedContentInfo {
    val resolver = context.contentResolver
    directFilePath(uri)?.let { filePath -> return directFileContentInfo(resolver, File(filePath)) }
    return runCatching { queryProviderContentInfo(resolver, uri) }
        .getOrElse {
            NPLogger.w(TAG, "queryContentInfo failed for $uri: ${it.message}")
            null
        }
        ?: unknownContentInfo(resolver.getType(uri))
}

private fun directFileContentInfo(resolver: ContentResolver, file: File): QueriedContentInfo {
    val info = unknownContentInfo(resolver.getType(Uri.fromFile(file))).copy(displayName = file.name)
    if (!file.exists()) return info
    return info.copy(sizeBytes = file.length(), lastModifiedMs = file.lastModified(), filePath = file.absolutePath)
}

private fun unknownContentInfo(mimeType: String?) = QueriedContentInfo(
    displayName = null,
    sizeBytes = null,
    mimeType = mimeType,
    lastModifiedMs = null,
    filePath = null,
    relativePath = null,
    title = null,
    artist = null,
    album = null,
    durationMs = null
)

private fun LocalMediaSupport.queryProviderContentInfo(resolver: ContentResolver, uri: Uri): QueriedContentInfo? {
    val includeRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    return resolver.query(uri, contentInfoProjection(includeRelativePath), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) contentInfoAtCursor(cursor, includeRelativePath) else null
    }
}

private fun contentInfoProjection(includeRelativePath: Boolean): Array<String> = buildList {
    add(OpenableColumns.DISPLAY_NAME)
    add(OpenableColumns.SIZE)
    add(MediaStore.MediaColumns.MIME_TYPE)
    add(MediaStore.MediaColumns.DATE_MODIFIED)
    if (includeRelativePath) {
        add(MediaStore.MediaColumns.RELATIVE_PATH)
    }
    add("_data")
    add(MediaStore.Audio.Media.TITLE)
    add(MediaStore.Audio.Media.ARTIST)
    add(MediaStore.Audio.Media.ALBUM)
    add(MediaStore.Audio.Media.DURATION)
}.toTypedArray()

private fun LocalMediaSupport.contentInfoAtCursor(cursor: Cursor, includeRelativePath: Boolean): QueriedContentInfo {
    val displayName = cursor.getOptionalString(OpenableColumns.DISPLAY_NAME)
    val relativePath = if (includeRelativePath) {
        cursor.getOptionalString(MediaStore.MediaColumns.RELATIVE_PATH)
    } else {
        null
    }
    return QueriedContentInfo(
        displayName = displayName,
        sizeBytes = cursor.getOptionalLong(OpenableColumns.SIZE),
        mimeType = cursor.getOptionalString(MediaStore.MediaColumns.MIME_TYPE),
        lastModifiedMs = cursor.getOptionalLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000),
        filePath = resolveQueryFilePath(
            rawPath = cursor.getOptionalString("_data"),
            relativePath = relativePath,
            displayName = displayName
        ),
        relativePath = relativePath,
        title = cursor.getOptionalString(MediaStore.Audio.Media.TITLE),
        artist = cursor.getOptionalString(MediaStore.Audio.Media.ARTIST),
        album = cursor.getOptionalString(MediaStore.Audio.Media.ALBUM),
        durationMs = cursor.getOptionalLong(MediaStore.Audio.Media.DURATION)
    )
}

internal fun LocalMediaSupport.resolvePathFromDescriptor(context: Context, uri: Uri): String? {
    if (!uri.isSupportedLocalMediaUri()) {
        return null
    }
    directFilePath(uri)?.let { return it }
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Os.readlink("/proc/self/fd/${descriptor.fd}")
                .substringBefore(" (deleted)")
                .takeIf { it.startsWith("/") && File(it).exists() }
        }
    }.getOrElse {
        NPLogger.w(TAG, "resolvePathFromDescriptor failed for $uri: ${it.message}")
        null
    }
}

internal fun LocalMediaSupport.resolveQueryFilePath(
    rawPath: String?,
    relativePath: String?,
    displayName: String?
): String? {
    return existingAbsoluteQueryPath(rawPath) ?: reconstructedExternalStoragePath(relativePath, displayName)
}

private fun existingAbsoluteQueryPath(rawPath: String?): String? {
    val path = rawPath?.substringBefore(" (deleted)") ?: return null
    return path.takeIf { it.startsWith("/") && File(it).exists() }
}

private fun reconstructedExternalStoragePath(relativePath: String?, displayName: String?): String? {
    if (relativePath.isNullOrBlank() || displayName.isNullOrBlank()) return null
    val reconstructed = File(Environment.getExternalStorageDirectory(), relativePath).resolve(displayName)
    return reconstructed.absolutePath.takeIf { reconstructed.exists() }
}

internal fun LocalMediaSupport.resolveSizeFromAssetDescriptor(context: Context, uri: Uri): Long? {
    if (!uri.isSupportedLocalMediaUri()) {
        return null
    }
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }.getOrElse {
        NPLogger.w(TAG, "resolveSizeFromAssetDescriptor failed for $uri: ${it.message}")
        null
    }
}

internal fun LocalMediaSupport.inspectAudioTrackInfo(context: Context, uri: Uri): AudioTrackTechInfo? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, emptyMap())
        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val trackMimeType = format.getOptionalString(MediaFormat.KEY_MIME)
            if (trackMimeType?.startsWith("audio/") != true) continue

            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
                    .div(1_000L)
                    .takeIf { it > 0L }
            } else {
                null
            }
            val bitrateKbps = format.getOptionalInt(MediaFormat.KEY_BIT_RATE)
                ?.let { max(0, (it + 500) / 1000) }
            val sampleRateHz = format.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getOptionalInt(MediaFormat.KEY_CHANNEL_COUNT)
            return AudioTrackTechInfo(
                audioMimeType = trackMimeType,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                durationMs = durationMs
            )
        }
        null
    } catch (error: Exception) {
        NPLogger.w(TAG, "inspectAudioTrackInfo failed for $uri: ${error.message}")
        null
    } finally {
        runCatching { extractor.release() }
    }
}

internal fun LocalMediaSupport.readRetrieverTextMetadata(context: Context, uri: Uri): RetrieverTextMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        RetrieverTextMetadata(
            title = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            composer = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            genre = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
            year = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.extractYear(),
            trackNumber = parseIndexedMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            ),
            discNumber = parseIndexedMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            ),
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull(),
            mimeType = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.let { max(0, (it + 500) / 1000) },
            sampleRateHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull()
            } else {
                null
            }
        )
    } catch (error: Exception) {
        NPLogger.d(TAG, "read retriever metadata unavailable for $uri: ${error.message}")
        RetrieverTextMetadata()
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun LocalMediaSupport.inspectTagLibMetadata(
    context: Context,
    uri: Uri,
    file: File?,
    includeEmbeddedAssets: Boolean = true,
    includeEmbeddedLyrics: Boolean = includeEmbeddedAssets,
    includeAudioProperties: Boolean = true
): TagLibMetadata? {
    return openTagLibDescriptor(context, uri, file)?.use { descriptor ->
        val metadata = runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), includeEmbeddedAssets)
        }.getOrElse {
            NPLogger.w(TAG, "TagLib metadata failed for $uri: ${it.message}")
            null
        }
        val audioProperties = if (includeAudioProperties) {
            runCatching {
                TagLib.getAudioProperties(descriptor.dup().detachFd())
            }.getOrElse {
                NPLogger.w(TAG, "TagLib audio properties failed for $uri: ${it.message}")
                null
            }
        } else {
            null
        }

        if (metadata == null && audioProperties == null) {
            return@use null
        }

        val propertyMap = metadata?.propertyMap
        val coverBytes = if (includeEmbeddedAssets) {
            metadata?.pictures
                ?.firstOrNull { it.pictureType.equals("Front Cover", ignoreCase = true) }
                ?.data
                ?: metadata?.pictures?.firstOrNull()?.data
        } else {
            null
        }

        TagLibMetadata(
            title = propertyMap.readFirstValue("TITLE", "TRACKTITLE", "SUBTITLE"),
            artist = propertyMap.readFirstValue("ARTIST", "ARTISTS", "PERFORMER", "AUTHOR"),
            album = propertyMap.readFirstValue("ALBUM", "ALBUMTITLE"),
            albumArtist = propertyMap.readFirstValue("ALBUMARTIST", "ALBUM ARTIST", "ENSEMBLE"),
            composer = propertyMap.readFirstValue("COMPOSER", "WRITER"),
            genre = propertyMap.readFirstValue("GENRE"),
            year = propertyMap.readFirstValue("DATE", "YEAR", "ORIGINALDATE")?.extractYear(),
            trackNumber = parseIndexedMetadata(propertyMap.readFirstValue("TRACKNUMBER", "TRACK", "TRACKNUM")),
            discNumber = parseIndexedMetadata(propertyMap.readFirstValue("DISCNUMBER", "DISC", "DISCNUM")),
            durationMs = audioProperties?.length?.toLong()?.takeIf { it > 0L },
            bitrateKbps = audioProperties?.bitrate?.takeIf { it > 0 },
            sampleRateHz = audioProperties?.sampleRate?.takeIf { it > 0 },
            channelCount = audioProperties?.channels?.takeIf { it > 0 },
            lyrics = if (includeEmbeddedLyrics) {
                propertyMap.readFirstValue(
                    NERI_ORIGINAL_LYRICS_METADATA_KEY,
                    "LYRICS",
                    "UNSYNCEDLYRICS",
                    "DESCRIPTION"
                )
            } else {
                null
            },
            translatedLyrics = if (includeEmbeddedLyrics) {
                propertyMap.readFirstValue(*translatedLyricsMetadataKeys.toTypedArray())
            } else {
                null
            },
            romanizedLyrics = if (includeEmbeddedLyrics) {
                propertyMap.readFirstValue(NERI_ROMANIZED_LYRICS_METADATA_KEY)
            } else {
                null
            },
            coverBytes = coverBytes?.takeIf { it.isNotEmpty() },
            sourceStableKey = propertyMap.readNeriSourceStableKey()
        )
    }
}

internal fun LocalMediaSupport.openTagLibDescriptor(
    context: Context,
    uri: Uri,
    file: File?
): ParcelFileDescriptor? {
    if (!uri.isSupportedLocalMediaUri()) {
        return null
    }
    return openLocalMediaDescriptor(context, uri, file, "r", ParcelFileDescriptor.MODE_READ_ONLY)
        ?: run {
            NPLogger.w(TAG, "openTagLibDescriptor failed for $uri")
            null
        }
}

internal fun LocalMediaSupport.openWritableTagLibDescriptor(
    context: Context,
    uri: Uri,
    file: File?
): ParcelFileDescriptor? {
    return openLocalMediaDescriptor(context, uri, file, "rw", ParcelFileDescriptor.MODE_READ_WRITE)
        ?: run {
            NPLogger.w(TAG, "open writable metadata descriptor failed for $uri")
            null
        }
}

private fun openLocalMediaDescriptor(
    context: Context,
    uri: Uri,
    file: File?,
    resolverMode: String,
    fileMode: Int
): ParcelFileDescriptor? {
    if (uri.scheme.equals("content", ignoreCase = true)) {
        return openResolverDescriptor(context, uri, resolverMode) ?: openFileDescriptorOrNull(file, fileMode)
    }
    return openFileDescriptorOrNull(file, fileMode) ?: openResolverDescriptor(context, uri, resolverMode)
}

private fun openResolverDescriptor(context: Context, uri: Uri, mode: String): ParcelFileDescriptor? {
    return runCatching { context.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
}

private fun openFileDescriptorOrNull(file: File?, mode: Int): ParcelFileDescriptor? {
    return file?.let { runCatching { ParcelFileDescriptor.open(it, mode) }.getOrNull() }
}

internal fun LocalMediaSupport.loadTagLibPropertyMap(descriptor: ParcelFileDescriptor): PropertyMap? {
    return runCatching {
        TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap
    }.getOrNull()
}
