package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LocalLyricsCacheEntry
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.QuickLocalMetadataSelection
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import com.kyant.taglib.PropertyMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import java.io.File

internal fun LocalMediaSupport.selectQuickLocalMetadataImpl(
    title: String,
    queriedArtist: String?,
    queriedAlbum: String?,
    queriedDurationMs: Long?,
    unknownArtistLabel: String,
    defaultAlbumLabel: String
): QuickLocalMetadataSelection {
    val artist = queriedArtist
        .takeMeaningfulLocalMetadata()
        ?: unknownArtistLabel
    val album = queriedAlbum
        .takeMeaningfulLocalMetadata()
    val resolvedAlbum = album ?: defaultAlbumLabel
    return QuickLocalMetadataSelection(
        title = title,
        artist = artist,
        album = resolvedAlbum,
        usesFallbackAlbum = album == null,
        durationMs = queriedDurationMs?.coerceAtLeast(0L) ?: 0L
    )
}

internal fun LocalMediaSupport.inspectImpl(context: Context, song: SongItem): LocalMediaDetails? {
    for (uri in song.localMediaUriCandidates()) {
        if (!uri.isSupportedLocalMediaUri()) {
            continue
        }
        runCatching { inspect(context, uri) }
            .onSuccess { return it }
            .onFailure {
                NPLogger.w(TAG, "inspect candidate failed for $uri: ${it.message}")
            }
    }
    return null
}

internal fun LocalMediaSupport.inspectMetadataOnlyImpl(
    context: Context,
    song: SongItem,
    resolveCoverFallback: Boolean = true
): LocalMediaDetails? {
    for (uri in song.localMediaUriCandidates()) {
        if (!uri.isSupportedLocalMediaUri()) {
            continue
        }
        runCatching {
            inspectMetadataOnly(
                context = context,
                uri = uri,
                resolveCoverFallback = resolveCoverFallback
            )
        }
            .onSuccess { return it }
            .onFailure {
                NPLogger.w(TAG, "inspect metadata-only candidate failed for $uri: ${it.message}")
            }
    }
    return null
}

internal suspend fun LocalMediaSupport.writeEditableMetadataImpl(
    context: Context,
    song: SongItem,
    coverReference: String? = song.customCoverUrl,
    writeCover: Boolean = coverReference != null,
    writeLyrics: Boolean = false,
    embeddedPropertyMapOverride: PropertyMap? = null,
    requiredEmbeddedPropertyKeys: Set<String> = emptySet(),
    persistCompanionSidecars: Boolean = true,
    embeddedPropertyPlanFactory: ((PropertyMap) -> EmbeddedMetadataPropertyPlan)? = null
): LocalMediaMetadataWriteOutcome {
    return try {
        val candidates = withContext(Dispatchers.IO) { editableLocalMediaUriCandidates(context, song) }
        if (candidates.isEmpty()) return LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        LocalMediaMetadataRecoveryStore.withRecoveredTargets(
            context = context.applicationContext,
            targetReferences = candidates.map(Uri::toString),
            blockedResult = LocalMediaMetadataWriteOutcome.FAILED
        ) {
            writeEditableMetadataInternal(
                context = context,
                song = song,
                coverReference = coverReference,
                writeCover = writeCover,
                writeLyrics = writeLyrics,
                embeddedPropertyMapOverride = embeddedPropertyMapOverride,
                requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys,
                persistCompanionSidecars = persistCompanionSidecars,
                embeddedPropertyPlanFactory = embeddedPropertyPlanFactory,
                candidates = candidates
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        throw error
    } catch (error: Throwable) {
        logEditableMetadataFailure(
            "unhandled",
            song.localMediaUri() ?: Uri.EMPTY,
            error
        )
        LocalMediaMetadataWriteOutcome.FAILED
    }
}

internal fun LocalMediaSupport.needsLyricSidecarRepairImpl(
    context: Context,
    song: SongItem
): Boolean {
    val expectedOriginal = song.matchedLyric ?: song.originalLyric
    val expectedTranslated = song.matchedTranslatedLyric
        ?: song.originalTranslatedLyric
    val expectedRomanized = song.matchedRomanizedLyric
        ?: song.originalRomanizedLyric
    if (expectedOriginal == null && expectedTranslated == null && expectedRomanized == null) {
        return false
    }
    val inspected = runCatching {
        inspectLyricsFast(
            context = context,
            song = song,
            includeStoredFallback = false,
            includeEmbeddedFallback = false,
            forceRefresh = true
        )
    }.getOrElse { error ->
        NPLogger.w(TAG, "check lyric sidecar repair failed: ${error.message}")
        return true
    }
    return shouldRebuildLyricSidecars(
        expectedOriginal = expectedOriginal != null,
        expectedTranslated = expectedTranslated != null,
        expectedRomanized = expectedRomanized != null,
        hasOriginalSidecar = inspected.hasOriginalSidecar,
        hasTranslatedSidecar = inspected.hasTranslatedSidecar,
        hasRomanizedSidecar = inspected.hasRomanizedSidecar
    )
}

internal fun LocalMediaSupport.shouldRebuildLyricSidecarsImpl(
    expectedOriginal: Boolean,
    expectedTranslated: Boolean,
    expectedRomanized: Boolean,
    hasOriginalSidecar: Boolean,
    hasTranslatedSidecar: Boolean,
    hasRomanizedSidecar: Boolean
): Boolean {
    return (expectedOriginal && !hasOriginalSidecar) ||
        (expectedTranslated && !hasTranslatedSidecar) ||
        (expectedRomanized && !hasRomanizedSidecar)
}

internal fun LocalMediaSupport.resolveLocalLyricsTargetDirectoryImpl(
    file: File,
    nearby: NearbyLyricFiles,
    legacyRoot: File = File(LEGACY_DOWNLOAD_ROOT),
    isLegacyDownload: Boolean = runCatching {
        isFileInsideDirectory(file, legacyRoot)
    }.getOrDefault(false)
): File {
    val parent = file.parentFile ?: return file
    if (isLegacyDownload) {
        return File(legacyRoot, "Lyrics")
    }
    val lyricsDirectory = File(parent, "Lyrics")
    if (lyricsDirectory.isDirectory) {
        return lyricsDirectory
    }
    return nearby.original?.parentFile
        ?: nearby.translated?.parentFile
        ?: nearby.romanized?.parentFile
        ?: parent
}

internal fun LocalMediaSupport.inspectLyricsFastImpl(
    song: SongItem,
    includeStoredFallback: Boolean = true
): LocalLyricsScanMetadata {
    return inspectLyricsFast(
        context = null,
        song = song,
        includeStoredFallback = includeStoredFallback,
        includeEmbeddedFallback = false,
        knownSidecarReferences = null,
        forceRefresh = false
    )
}

internal fun LocalMediaSupport.inspectLyricsFastImpl(
    context: Context?,
    song: SongItem,
    includeStoredFallback: Boolean = true,
    includeEmbeddedFallback: Boolean = context != null,
    knownSidecarReferences: LocalKnownSidecarReferences? = null,
    forceRefresh: Boolean = false
): LocalLyricsScanMetadata {
    val startedAt = SystemClock.elapsedRealtime()
    if (forceRefresh) {
        clearLyricsLookupCache()
    }
    val stored = if (includeStoredFallback) {
        LocalLyricsScanMetadata(
            lyric = song.matchedLyric ?: song.originalLyric,
            translatedLyric = song.matchedTranslatedLyric
                ?: song.originalTranslatedLyric,
            romanizedLyric = song.matchedRomanizedLyric
                ?: song.originalRomanizedLyric
        )
    } else {
        LocalLyricsScanMetadata(null, null, null)
    }

    val source = song.localMediaUri()
    val rawContentSource = song.mediaUri?.startsWith("content://", ignoreCase = true) == true ||
        song.localFilePath?.startsWith("content://", ignoreCase = true) == true
    val contentSource = source?.scheme.equals("content", ignoreCase = true) || rawContentSource
    val directFile = song.localFilePath
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)
        ?.takeIf(::isReadableLocalFile)
        ?: source
            ?.takeIf { !it.scheme.equals("content", ignoreCase = true) }
            ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?.path
            ?.let(::File)
            ?.takeIf(::isReadableLocalFile)
    // 当媒体同时有可读绝对路径和 MediaStore content URI 时，绝对路径才是
    // Lyrics/Covers 的权威来源。跳过一次昂贵的 provider 查询，也避免权限
    // 变化让编辑入口错误地读到旧的媒体索引
    // content URI 没有可靠的文件时间戳，因此只保留很短的缓存
    // 避免每帧重复扫描 Provider，同时让外部编辑或删除在下一次交互时可见
    val cacheable = knownSidecarReferences == null
    val cacheKey = buildLocalLyricsCacheKey(
        song = song,
        source = source,
        includeEmbeddedFallback = includeEmbeddedFallback,
        includeStoredFallback = includeStoredFallback
    )
    if (cacheable && !forceRefresh) {
            synchronized(localLyricsLookupCache) {
            localLyricsLookupCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.cachedAtMs <= LOCAL_LYRICS_CACHE_TTL_MS) {
                    logLyricsInspection(
                        song = song,
                        source = source,
                        stage = "cache",
                        startedAt = startedAt,
                        result = cached.value
                    )
                    return cached.value
                }
                localLyricsLookupCache.remove(cacheKey)
            }
        }
    }

    val directScanned = if (knownSidecarReferences != null && context != null) {
        try {
            inspectLyricsFromKnownReferences(
                context = context,
                references = knownSidecarReferences
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "known local lyrics inspection failed for $source: ${error.message}")
            null
        }
    } else if (directFile != null) {
        try {
            inspectLyricsFromDirectFile(
                file = directFile
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "fast local lyrics inspection failed for $source: ${error.message}")
            null
        }
    } else {
        null
    }

    val shouldProbeContentSource = directFile == null && knownSidecarReferences == null
    val contentScanned = if (
        context != null &&
        contentSource &&
        source != null &&
        shouldProbeContentSource
    ) {
        try {
            inspectLyricsFromContentUri(
                context = context,
                sourceUri = source,
                displayName = ManagedDownloadStorage.resolveManagedAudioDisplayName(
                    context = context,
                    song = song
                ) ?: source.lastPathSegment.orEmpty()
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "fast SAF lyrics inspection failed for $source: ${error.message}")
            null
        }
    } else {
        null
    }
    val resolvedScan = mergeLyricsInspections(
        primary = directScanned,
        fallback = contentScanned
    )
    val needsEmbeddedFallback = context != null && includeEmbeddedFallback && (
        resolvedScan == null ||
            !resolvedScan.hasOriginalSidecar ||
            !resolvedScan.hasTranslatedSidecar ||
            !resolvedScan.hasRomanizedSidecar
        )
    val embedded = context?.takeIf { needsEmbeddedFallback }?.let { embeddedContext ->
        try {
            inspectEmbeddedLyrics(embeddedContext, song)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "fast embedded lyrics inspection failed for $source: ${error.message}")
            null
        }
    }
    val result = LocalLyricsScanMetadata(
        lyric = resolvedScan?.original
            ?: embedded?.lyric
            ?: resolvedScan?.metadataOriginal
            ?: stored.lyric,
        translatedLyric = resolvedScan?.translated
            ?: embedded?.translatedLyric
            ?: resolvedScan?.metadataTranslated
            ?: stored.translatedLyric,
        romanizedLyric = resolvedScan?.romanized
            ?: embedded?.romanizedLyric
            ?: resolvedScan?.metadataRomanized
            ?: stored.romanizedLyric,
        hasOriginalSidecar = resolvedScan?.hasOriginalSidecar == true,
        hasTranslatedSidecar = resolvedScan?.hasTranslatedSidecar == true,
        hasRomanizedSidecar = resolvedScan?.hasRomanizedSidecar == true,
        embeddedLyric = embedded?.lyric,
        embeddedTranslatedLyric = embedded?.translatedLyric,
        embeddedRomanizedLyric = embedded?.romanizedLyric,
        sourceResolved = isLocalLyricsSourceResolved(
            scannedSource = resolvedScan != null,
            embeddedSource = embedded != null
        )
    )
    if (cacheable && !forceRefresh) {
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache[cacheKey] = LocalLyricsCacheEntry(
                value = result,
                cachedAtMs = System.currentTimeMillis()
            )
        }
    }
    logLyricsInspection(
        song = song,
        source = source,
        stage = when {
            directFile != null -> "file"
            contentScanned != null -> "saf"
            embedded != null -> "embedded"
            else -> "stored"
        },
        startedAt = startedAt,
        result = result
    )
    return result
}

internal fun LocalMediaSupport.inspectEmbeddedLyricsImpl(
    context: Context,
    song: SongItem
): LocalLyricsScanMetadata? {
    val options = embeddedLyricsReadOptions
    song.localMediaUriCandidates().forEach { sourceUri ->
        val resolved = try {
            resolveInspectableLocalMedia(
                context = context,
                uri = sourceUri,
                allowDescriptorFallback = true
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return@forEach
        val metadata = try {
            inspectTagLibMetadata(
                context = context,
                uri = resolved.playableUri,
                file = resolved.file,
                includeEmbeddedAssets = options.includeEmbeddedAssets,
                includeEmbeddedLyrics = options.includeEmbeddedLyrics,
                includeAudioProperties = options.includeAudioProperties
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return@forEach
        return LocalLyricsScanMetadata(
            lyric = metadata.lyrics,
            translatedLyric = metadata.translatedLyrics,
            romanizedLyric = metadata.romanizedLyrics,
            embeddedLyric = metadata.lyrics,
            embeddedTranslatedLyric = metadata.translatedLyrics,
            embeddedRomanizedLyric = metadata.romanizedLyrics
        )
    }
    return null
}

internal fun LocalMediaSupport.invalidateSongAssetCachesImpl(song: SongItem) {
    val keyParts = setOf(
        song.songStableKey(),
        song.mediaUri.orEmpty(),
        song.localFilePath.orEmpty()
    ).filter(String::isNotBlank)
    if (keyParts.isEmpty()) return
    val localParentPath = song.localFilePath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    synchronized(localLyricsLookupCache) {
        localLyricsLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
    }
    synchronized(localCoverLookupCache) {
        localCoverLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
    }
    synchronized(nearbyCoverLookupCache) {
        nearbyCoverLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
    }
    synchronized(directoryCoverLookupCache) {
        directoryCoverLookupCache.keys.removeAll { key ->
            keyParts.any(key::contains) ||
                localParentPath?.let { key.startsWith("$it|") } == true
        }
    }
    synchronized(directoryFileIndexCache) {
        directoryFileIndexCache.keys.removeAll { key ->
            keyParts.any(key::contains) ||
                localParentPath?.let { key == it } == true
        }
    }
}

internal fun LocalMediaSupport.clearCoverLookupCacheImpl() {
    synchronized(localCoverLookupCache) {
        localCoverLookupCache.clear()
    }
    synchronized(nearbyCoverLookupCache) {
        nearbyCoverLookupCache.clear()
    }
    synchronized(directoryCoverLookupCache) {
        directoryCoverLookupCache.clear()
    }
    synchronized(directoryFileIndexCache) {
        directoryFileIndexCache.clear()
    }
    synchronized(mediaStoreAlbumArtCache) {
        mediaStoreAlbumArtCache.clear()
    }
    invalidateSafReadCaches()
}

internal fun LocalMediaSupport.invalidateSafReadCachesImpl() {
    synchronized(documentChildrenCache) {
        documentChildrenCache.clear()
    }
    consecutiveEmptyDocumentRefreshes.clear()
    synchronized(documentNavigationCache) {
        documentNavigationCache.clear()
    }
}

internal fun LocalMediaSupport.shouldUseTransactionalStagedWriteImpl(
    sourceScheme: String?,
    sourcePath: String?
): Boolean {
    return sourceScheme.equals("content", ignoreCase = true) ||
        sourceScheme.equals("file", ignoreCase = true) ||
        (sourceScheme.isNullOrBlank() && sourcePath?.startsWith('/') == true)
}

internal fun LocalMediaSupport.retryEditableMetadataReadbackImpl(
    sourceScheme: String?,
    readBack: () -> Boolean
): Boolean {
    val attemptCount = if (sourceScheme.equals("content", ignoreCase = true)) {
        SAF_WRITE_READBACK_RETRY_COUNT
    } else {
        1
    }
    repeat(attemptCount) { attempt ->
        if (readBack()) return true
        if (attempt + 1 < attemptCount) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
        }
    }
    return false
}

internal fun LocalMediaSupport.inspectQuickImpl(
    context: Context,
    uri: Uri,
    includeAudioTrackInfo: Boolean = false
): LocalMediaDetails {
    val resolved = resolveInspectableLocalMedia(
        context = context,
        uri = uri,
        allowDescriptorFallback = true
    )
    val audioTrackTechInfo = if (includeAudioTrackInfo) {
        inspectAudioTrackInfo(context, resolved.playableUri)
    } else {
        null
    }
    return buildQuickLocalMediaDetails(
        context = context,
        sourceUri = uri,
        resolved = resolved,
        audioTrackTechInfo = audioTrackTechInfo
    )
}

internal fun LocalMediaSupport.resolveDurationFastImpl(context: Context, uri: Uri): Long {
    val queriedDuration = runCatching {
        queryContentInfo(context, uri).durationMs
    }.getOrNull()?.takeIf { it > 0L }
    if (queriedDuration != null) {
        return queriedDuration
    }
    return runCatching {
        inspectAudioTrackInfo(context, uri)?.durationMs ?: 0L
    }.getOrDefault(0L)
}

internal fun LocalMediaSupport.resolveMediaStoreDurationsFastImpl(
    context: Context,
    sources: List<Uri>
): Map<String, Long> {
    val sourcesByCollection = (
        sources.asSequence()
            .filter(::isMediaStoreUri)
            .mapNotNull { source ->
                val id = source.lastPathSegment
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                val collectionUri = source.mediaStoreAudioCollectionUri()
                    ?: return@mapNotNull null
                collectionUri to (id to source.toString())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }
    )
    if (sourcesByCollection.isEmpty()) return emptyMap()

    val result = HashMap<String, Long>(sourcesByCollection.values.sumOf { it.size })
    sourcesByCollection.forEach { (audioUri, sourceById) ->
        sourceById.keys.chunked(MAX_MEDIASTORE_DURATION_QUERY_IDS).forEach { ids ->
            runCatching {
                val placeholders = ids.joinToString(",") { "?" }
                context.contentResolver.query(
                    audioUri,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION),
                    "${MediaStore.Audio.Media._ID} IN ($placeholders)",
                    ids.map(Long::toString).toTypedArray(),
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    if (idIndex < 0 || durationIndex < 0) return@use
                    while (cursor.moveToNext()) {
                        val durationMs = cursor.getLong(durationIndex)
                            .takeIf { it > 0L }
                            ?: continue
                        sourceById[cursor.getLong(idIndex)]?.let { source ->
                            result[source] = durationMs
                        }
                    }
                }
            }.onFailure { error ->
                NPLogger.d(
                    TAG,
                    "bulk MediaStore duration query unavailable for $audioUri: " +
                        error.message
                )
            }
        }
    }
    return result
}
