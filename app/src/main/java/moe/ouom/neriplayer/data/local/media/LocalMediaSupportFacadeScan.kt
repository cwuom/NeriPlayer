package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.RetrieverTextMetadata
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import kotlin.math.max

internal fun LocalMediaSupport.inspectLyricsForScanImpl(
    context: Context,
    uri: Uri
): LocalLyricsScanMetadata {
    val resolved = resolveInspectableLocalMedia(
        context = context,
        uri = uri,
        allowDescriptorFallback = true
    )
    val localMetadata = readLocalMetadataSidecar(
        context = context,
        sourceUri = uri,
        file = resolved.file,
        displayName = resolved.displayName
    )
    val nearbyFiles = findNearbyLyricFiles(resolved.file)
    val nearbyReferences = findNearbyLyricReferences(
        context = context,
        uri = uri,
        file = resolved.file,
        displayName = resolved.displayName
    )
    fun read(reference: String?, fallback: File?, label: String): String? {
        return readNearbyLyricContent(
            context = context,
            reference = reference ?: fallback?.absolutePath,
            label = label
        )
    }
    val nearbyLyric = read(
        nearbyReferences.original,
        nearbyFiles.original,
        "quick scan lyric"
    )
    val nearbyTranslatedLyric = read(
        nearbyReferences.translated,
        nearbyFiles.translated,
        "quick scan translated lyric"
    )
    val nearbyRomanizedLyric = read(
        nearbyReferences.romanized,
        nearbyFiles.romanized,
        "quick scan romanized lyric"
    )
    val needsEmbeddedLyrics =
        (nearbyLyric == null && localMetadata?.hasLyricOverride != true) ||
            (nearbyTranslatedLyric == null &&
                localMetadata?.hasTranslatedLyricOverride != true) ||
            (nearbyRomanizedLyric == null &&
                localMetadata?.hasRomanizedLyricOverride != true)
    val embedded = if (needsEmbeddedLyrics) {
        runCatching {
            inspectTagLibMetadata(
                context = context,
                uri = resolved.playableUri,
                file = resolved.file,
                includeEmbeddedAssets = false,
                includeEmbeddedLyrics = true,
                includeAudioProperties = false
            )
        }.onFailure {
            NPLogger.w(
                TAG,
                "quick scan embedded lyrics inspection failed for $uri: ${it.message}"
            )
        }.getOrNull()
    } else {
        null
    }
    return LocalLyricsScanMetadata(
        lyric = nearbyLyric
            ?: embedded?.lyrics
            ?: localMetadata?.takeIf { it.hasLyricOverride }?.lyric,
        translatedLyric = nearbyTranslatedLyric
            ?: embedded?.translatedLyrics
            ?: localMetadata?.takeIf { it.hasTranslatedLyricOverride }?.translatedLyric,
        romanizedLyric = nearbyRomanizedLyric
            ?: embedded?.romanizedLyrics
            ?: localMetadata?.takeIf { it.hasRomanizedLyricOverride }?.romanizedLyric,
        hasOriginalSidecar = nearbyFiles.original != null || nearbyReferences.original != null,
        hasTranslatedSidecar = nearbyFiles.translated != null || nearbyReferences.translated != null,
        hasRomanizedSidecar = nearbyFiles.romanized != null || nearbyReferences.romanized != null,
        embeddedLyric = embedded?.lyrics,
        embeddedTranslatedLyric = embedded?.translatedLyrics,
        embeddedRomanizedLyric = embedded?.romanizedLyrics
    )
}

internal fun LocalMediaSupport.inspectMetadataOnlyImpl(
    context: Context,
    uri: Uri,
    resolveCoverFallback: Boolean = true
): LocalMediaDetails {
    val resolved = resolveInspectableLocalMedia(
        context = context,
        uri = uri,
        allowDescriptorFallback = true
    )
    val queried = resolved.queried
    val file = resolved.file
    val containerMetadata = file?.let(::parseContainerMetadata)
    val tagLibMetadata = inspectTagLibMetadata(
        context = context,
        uri = resolved.playableUri,
        file = file,
        includeEmbeddedAssets = false,
        includeEmbeddedLyrics = true,
        includeAudioProperties = false
    )
    val retrieverMetadata = if (
        shouldProbeRetrieverTextMetadata(resolved.playableUri.toString(), file)
    ) {
        readRetrieverTextMetadata(context, resolved.playableUri)
    } else {
        RetrieverTextMetadata()
    }
    val localMetadata = readLocalMetadataSidecar(
        context = context,
        sourceUri = uri,
        file = file,
        displayName = resolved.displayName
    )
    val title = pickReadableLocalTitle(
        sourceUri = uri,
        fallbackTitle = resolved.fallbackTitle,
        tagLibMetadata?.title,
        retrieverMetadata.title,
        containerMetadata?.title,
        queried.title,
        localMetadata?.customName,
        localMetadata?.name
    ) ?: resolved.fallbackTitle
    val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
        ?: retrieverMetadata.artist.takeMeaningfulLocalMetadata()
        ?: retrieverMetadata.albumArtist.takeMeaningfulLocalMetadata()
        ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
        ?: queried.artist.takeMeaningfulLocalMetadata()
        ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
        ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
        ?: context.getString(R.string.music_unknown_artist)
    val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
        ?: retrieverMetadata.album.takeMeaningfulLocalMetadata()
        ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
        ?: queried.album.takeMeaningfulLocalMetadata()
        ?: localMetadata?.album.takeMeaningfulLocalMetadata()
    val usesFallbackAlbum = rawAlbum == null
    val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
    val nearbyLyricFiles = findNearbyLyricFiles(file)
    val nearbyLyricReferences = findNearbyLyricReferences(
        context = context,
        uri = uri,
        file = file,
        displayName = resolved.displayName
    )
    fun readLyric(reference: String?, fallback: File?, label: String): String? {
        return readNearbyLyricContent(
            context = context,
            reference = reference ?: fallback?.absolutePath,
            label = label
        )
    }
    val nearbyLyric = readLyric(
        reference = nearbyLyricReferences.original,
        fallback = nearbyLyricFiles.original,
        label = "metadata-only lyric"
    )
    val nearbyTranslatedLyric = readLyric(
        reference = nearbyLyricReferences.translated,
        fallback = nearbyLyricFiles.translated,
        label = "metadata-only translated lyric"
    )
    val nearbyRomanizedLyric = readLyric(
        reference = nearbyLyricReferences.romanized,
        fallback = nearbyLyricFiles.romanized,
        label = "metadata-only romanized lyric"
    )
    val effectiveLyric = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyLyric,
        embeddedContent = tagLibMetadata?.lyrics,
        metadataFallback = localMetadata?.takeIf { it.hasLyricOverride }?.lyric
    )
    val effectiveTranslatedLyric = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyTranslatedLyric,
        embeddedContent = tagLibMetadata?.translatedLyrics,
        metadataFallback = localMetadata
            ?.takeIf { it.hasTranslatedLyricOverride }
            ?.translatedLyric
    )
    val effectiveRomanizedLyric = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyRomanizedLyric,
        embeddedContent = tagLibMetadata?.romanizedLyrics,
        metadataFallback = localMetadata
            ?.takeIf { it.hasRomanizedLyricOverride }
            ?.romanizedLyric
    )
    val lyricReference = nearbyLyricReferences.original
        ?: nearbyLyricFiles.original?.absolutePath
        ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride }
    val coverUri = if (resolveCoverFallback) {
        runCatching {
            resolveCoverUri(context, uri)
        }.onFailure {
            NPLogger.w(TAG, "resolve metadata-only cover failed for $uri: ${it.message}")
        }.getOrNull()
    } else {
        null
    }

    return LocalMediaDetails(
        sourceUri = uri,
        displayName = resolved.displayName,
        title = title,
        artist = artist,
        album = resolvedAlbum,
        usesFallbackAlbum = usesFallbackAlbum,
        albumArtist = tagLibMetadata?.albumArtist
            ?: retrieverMetadata.albumArtist
            ?: containerMetadata?.albumArtist,
        composer = tagLibMetadata?.composer
            ?: retrieverMetadata.composer
            ?: containerMetadata?.composer,
        genre = tagLibMetadata?.genre
            ?: retrieverMetadata.genre
            ?: containerMetadata?.genre,
        year = tagLibMetadata?.year ?: retrieverMetadata.year ?: containerMetadata?.year,
        trackNumber = tagLibMetadata?.trackNumber
            ?: retrieverMetadata.trackNumber
            ?: containerMetadata?.trackNumber,
        discNumber = tagLibMetadata?.discNumber
            ?: retrieverMetadata.discNumber
            ?: containerMetadata?.discNumber,
        durationMs = tagLibMetadata?.durationMs
            ?: retrieverMetadata.durationMs
            ?: queried.durationMs
            ?: 0L,
        fileExtension = resolved.fileExtension,
        mimeType = queried.mimeType ?: retrieverMetadata.mimeType,
        audioMimeType = null,
        bitrateKbps = tagLibMetadata?.bitrateKbps ?: retrieverMetadata.bitrateKbps,
        sampleRateHz = tagLibMetadata?.sampleRateHz ?: retrieverMetadata.sampleRateHz,
        channelCount = tagLibMetadata?.channelCount,
        bitsPerSample = null,
        sizeBytes = queried.sizeBytes ?: file?.length(),
        lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
        filePath = file?.absolutePath ?: queried.filePath,
        coverUri = coverUri,
        coverSource = null,
        lyricContent = effectiveLyric,
        lyricPath = resolveEffectiveLocalLyricPath(
            reference = lyricReference,
            content = effectiveLyric
        ),
        lyricSource = when {
            nearbyLyric != null -> context.getString(R.string.local_song_lyric_external)
            !effectiveLyric.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
            else -> null
        },
        originalTitle = title,
        originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: retrieverMetadata.artist.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: queried.artist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: artist,
        embeddedCover = false,
        sourceStableKey = tagLibMetadata?.sourceStableKey,
        translatedLyricContent = effectiveTranslatedLyric,
        romanizedLyricContent = effectiveRomanizedLyric
    )
}

internal fun LocalMediaSupport.resolveCoverUriImpl(context: Context, song: SongItem): String? {
    return song.localMediaUriCandidates()
        .asSequence()
        .mapNotNull { candidate -> resolveCoverUri(context, candidate) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .firstOrNull { isUsableCoverReference(context, it) }
}

internal fun LocalMediaSupport.resolveCoverReferenceByPriorityImpl(
    sidecarReference: String?,
    embeddedReference: String?,
    fallbackReference: String? = null
): String? {
    return sequenceOf(sidecarReference, embeddedReference, fallbackReference)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
}

internal fun LocalMediaSupport.resolveNearbyCoverUriImpl(context: Context, song: SongItem): String? {
    return try {
        resolveNearbyCoverUriInternal(context, song)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        invalidateSafReadCaches()
        NPLogger.w(
            TAG,
            "SAF 封面只读探测失败，降级为空: song=${song.songStableKey()}, " +
                "message=${error.message}"
        )
        null
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "本地封面只读探测失败，降级为空: song=${song.songStableKey()}, " +
                "type=${error::class.simpleName}, message=${error.message}"
        )
        null
    }
}

internal fun LocalMediaSupport.peekMediaStoreAlbumArtUriImpl(context: Context, source: Uri): String? {
    if (!isMediaStoreAuthority(source.authority)) {
        return null
    }
    val cacheKey = source.toString()
    val cachedCoverUri = synchronized(mediaStoreAlbumArtCache) {
        mediaStoreAlbumArtCache[cacheKey]
    }
    if (cachedCoverUri != null) return cachedCoverUri
    val coverUri = runCatching {
        context.contentResolver.query(
            source,
            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            if (index < 0 || cursor.isNull(index)) return@use null
            cursor.getLong(index).takeIf { it > 0L }
        }?.let { albumId ->
            mediaStoreAlbumArtUri(albumId)
        }
    }.onFailure {
        NPLogger.d(TAG, "MediaStore album art hint unavailable for $source: ${it.message}")
    }.getOrNull()?.takeIf { isUsableCoverReference(context, it) }
    if (coverUri != null) {
        synchronized(mediaStoreAlbumArtCache) {
            mediaStoreAlbumArtCache[cacheKey] = coverUri
        }
    }
    return coverUri
}

internal fun LocalMediaSupport.peekCachedEmbeddedCoverUriImpl(context: Context, source: Uri): String? {
    return listOfNotNull(source.toString(), source.path)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .asSequence()
        .flatMap { key -> sequenceOf(key, "$key#taglib") }
        .firstNotNullOfOrNull { key -> findCachedEmbeddedCover(context, key) }
}

internal fun LocalMediaSupport.embeddedCoverCacheLookupKeysImpl(song: SongItem): List<String> {
    val localUri = song.localMediaUri()
    return listOfNotNull(
        song.localFilePath,
        localUri
            ?.takeIf { uri -> uri.scheme.equals("file", ignoreCase = true) }
            ?.path,
        song.mediaUri,
        localUri?.toString()
    )
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

internal fun LocalMediaSupport.resolveCoverUriImpl(context: Context, uri: Uri): String? {
    return try {
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = uri,
                allowDescriptorFallback = true
            )
        }.getOrElse {
            NPLogger.w(TAG, "resolve cover source failed for $uri: ${it.message}")
            return null
        }
        val cacheKey = localCoverLookupKey(uri, resolved)
        cachedLocalCoverLookup(context, cacheKey)?.let { cached ->
            cached.coverUri?.let { return it }

            // 不要缓存空的邻近封面结果，用户可以在不重启应用的情况下补入同目录封面
            findNearbyCoverReference(
                context = context,
                uri = uri,
                file = resolved.file,
                displayName = resolved.displayName
            )?.takeIf { isUsableCoverReference(context, it) }?.let { nearbyCover ->
                rememberLocalCoverLookup(cacheKey, nearbyCover)
                return nearbyCover
            }
            return null
        }

        val resolvedCover = sequence {
            findNearbyCoverReference(
                context = context,
                uri = uri,
                file = resolved.file,
                displayName = resolved.displayName
            )?.let { yield(it) }
            peekMediaStoreAlbumArtUri(context, uri)?.let { yield(it) }
            findCachedEmbeddedCover(context, resolved.resolvedPath ?: uri.toString())
                ?.let { yield(it) }
            findCachedEmbeddedCover(context, "${resolved.resolvedPath ?: uri}#taglib")
                ?.let { yield(it) }
            extractEmbeddedCoverWithRetriever(context, uri, resolved)?.let { yield(it) }
            extractEmbeddedCoverWithTagLib(context, uri, resolved)?.let { yield(it) }
        }.firstOrNull { isUsableCoverReference(context, it) }
        rememberLocalCoverLookup(cacheKey, resolvedCover)
        resolvedCover
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        invalidateSafReadCaches()
        NPLogger.w(
            TAG,
            "SAF 封面解析权限不可用，降级为空: uri=$uri, message=${error.message}"
        )
        null
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "本地封面解析失败，降级为空: uri=$uri, " +
                "type=${error::class.simpleName}, message=${error.message}"
        )
        null
    }
}

internal fun LocalMediaSupport.inspectImpl(context: Context, uri: Uri): LocalMediaDetails {
    val resolved = resolveInspectableLocalMedia(context, uri)
    val queried = resolved.queried
    val resolvedPath = resolved.resolvedPath
    val file = resolved.file
    val playableUri = resolved.playableUri
    val displayName = resolved.displayName
    val fallbackTitle = resolved.fallbackTitle
    val fileExtension = resolved.fileExtension
    val containerMetadata = file?.let(::parseContainerMetadata)
    val tagLibMetadata = inspectTagLibMetadata(
        context = context,
        uri = playableUri,
        file = file
    )
    val nearbyCoverReference = findNearbyCoverReference(
        context = context,
        uri = uri,
        file = file,
        displayName = displayName
    )
    val nearbyLyricFiles = findNearbyLyricFiles(file)
    val nearbyLyricReferences = findNearbyLyricReferences(
        context = context,
        uri = uri,
        file = file,
        displayName = displayName
    )
    val nearbyLyricContent = readNearbyLyricContent(
        context = context,
        reference = nearbyLyricReferences.original
            ?: nearbyLyricFiles.original?.absolutePath,
        label = "lyric"
    )
    val nearbyTranslatedLyricContent = readNearbyLyricContent(
        context = context,
        reference = nearbyLyricReferences.translated
            ?: nearbyLyricFiles.translated?.absolutePath,
        label = "translated lyric"
    )
    val nearbyRomanizedLyricContent = readNearbyLyricContent(
        context = context,
        reference = nearbyLyricReferences.romanized
            ?: nearbyLyricFiles.romanized?.absolutePath,
        label = "romanized lyric"
    )
    val localMetadata = readLocalMetadataSidecar(
        context = context,
        sourceUri = uri,
        file = file,
        displayName = displayName
    )
    val hasEffectiveExternalLyric = nearbyLyricContent != null
    val effectiveLyricContent = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyLyricContent,
        embeddedContent = tagLibMetadata?.lyrics,
        metadataFallback = localMetadata?.takeIf { it.hasLyricOverride }?.lyric
    )
    val effectiveTranslatedLyricContent = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyTranslatedLyricContent,
        embeddedContent = tagLibMetadata?.translatedLyrics,
        metadataFallback = localMetadata
            ?.takeIf { it.hasTranslatedLyricOverride }
            ?.translatedLyric
    )
    val effectiveRomanizedLyricContent = resolveLocalLyricContentByPriority(
        sidecarContent = nearbyRomanizedLyricContent,
        embeddedContent = tagLibMetadata?.romanizedLyrics,
        metadataFallback = localMetadata
            ?.takeIf { it.hasRomanizedLyricOverride }
            ?.romanizedLyric
    )

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, playableUri)
        val audioTrackTechInfo = inspectAudioTrackInfo(context, playableUri)
        val retrieverTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val rawTitle = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = fallbackTitle,
            tagLibMetadata?.title,
            retrieverTitle,
            containerMetadata?.title,
            queried.title,
            localMetadata?.customName,
            localMetadata?.name
        )
        val title = rawTitle ?: fallbackTitle
        val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .takeMeaningfulLocalMetadata()
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                .takeMeaningfulLocalMetadata()
            ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: queried.artist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: context.getString(R.string.music_unknown_artist)
        val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                .takeMeaningfulLocalMetadata()
            ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
            ?: queried.album.takeMeaningfulLocalMetadata()
            ?: localMetadata?.album.takeMeaningfulLocalMetadata()
        val usesFallbackAlbum = rawAlbum == null
        val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
        val albumArtist = tagLibMetadata?.albumArtist
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: containerMetadata?.albumArtist?.takeIf { it.isNotBlank() }
        val composer = tagLibMetadata?.composer
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            ?.takeIf { it.isNotBlank() }
            ?: containerMetadata?.composer?.takeIf { it.isNotBlank() }
        val genre = tagLibMetadata?.genre
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ?.takeIf { it.isNotBlank() }
            ?: containerMetadata?.genre?.takeIf { it.isNotBlank() }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: tagLibMetadata?.durationMs
            ?: queried.durationMs
            ?: 0L
        val mimeType = queried.mimeType
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.takeIf { it.isNotBlank() }
        val bitrateKbps = audioTrackTechInfo?.bitrateKbps
            ?: tagLibMetadata?.bitrateKbps
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.let { max(0, (it + 500) / 1000) }
        val sampleRateHz = audioTrackTechInfo?.sampleRateHz
            ?: tagLibMetadata?.sampleRateHz
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull()
            } else {
                null
            }
        val bitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()
        } else {
            null
        }
        val year = tagLibMetadata?.year
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?.toIntOrNull()
            ?: containerMetadata?.year
        val trackNumber = tagLibMetadata?.trackNumber ?: parseIndexedMetadata(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        ) ?: containerMetadata?.trackNumber
        val discNumber = tagLibMetadata?.discNumber ?: (
            parseIndexedMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            )
        ) ?: containerMetadata?.discNumber

        val embeddedPicture = retriever.embeddedPicture
        val embeddedCoverUri = embeddedPicture
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { picture ->
                saveEmbeddedCover(context, resolvedPath ?: uri.toString(), picture)
            }
        val tagLibCoverUri = if (embeddedCoverUri == null) {
            tagLibMetadata?.coverBytes
                ?.takeIf { it.isNotEmpty() }
                ?.let { saveEmbeddedCover(context, "${resolvedPath ?: uri}#taglib", it) }
        } else {
            null
        }
        val effectiveNearbyCover = nearbyCoverReference

        LocalMediaDetails(
            sourceUri = uri,
            displayName = displayName,
            title = title,
            artist = artist,
            album = resolvedAlbum,
            usesFallbackAlbum = usesFallbackAlbum,
            albumArtist = albumArtist,
            composer = composer,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            fileExtension = fileExtension,
            mimeType = mimeType,
            audioMimeType = audioTrackTechInfo?.audioMimeType,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channelCount = audioTrackTechInfo?.channelCount,
            bitsPerSample = bitsPerSample,
            sizeBytes = queried.sizeBytes ?: file?.length() ?: resolveSizeFromAssetDescriptor(context, uri),
            lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
            filePath = file?.absolutePath ?: queried.filePath,
            coverUri = resolveCoverReferenceByPriority(
                sidecarReference = effectiveNearbyCover,
                embeddedReference = embeddedCoverUri ?: tagLibCoverUri
            ),
            coverSource = when {
                effectiveNearbyCover != null -> context.getString(R.string.local_song_cover_external)
                embeddedCoverUri != null || tagLibCoverUri != null -> {
                    context.getString(R.string.local_song_cover_embedded)
                }
                else -> null
            },
            lyricContent = effectiveLyricContent,
            lyricPath = resolveEffectiveLocalLyricPath(
                reference = nearbyLyricReferences.original
                    ?: nearbyLyricFiles.original?.absolutePath
                    ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride },
                content = effectiveLyricContent
            ),
            lyricSource = when {
                hasEffectiveExternalLyric -> context.getString(R.string.local_song_lyric_external)
                !effectiveLyricContent.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
                else -> null
            },
            translatedLyricContent = effectiveTranslatedLyricContent,
            romanizedLyricContent = effectiveRomanizedLyricContent,
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: artist,
            embeddedCover = embeddedCoverUri != null || tagLibCoverUri != null,
            sourceStableKey = tagLibMetadata?.sourceStableKey
        )
    } catch (error: Exception) {
        NPLogger.w(TAG, "inspect metadata fallback for $uri: ${error.message}")
        val rawTitle = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = fallbackTitle,
            tagLibMetadata?.title,
            containerMetadata?.title,
            queried.title,
            localMetadata?.customName,
            localMetadata?.name
        )
        val title = rawTitle ?: fallbackTitle
        val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: queried.artist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: context.getString(R.string.music_unknown_artist)
        val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
            ?: queried.album.takeMeaningfulLocalMetadata()
            ?: localMetadata?.album.takeMeaningfulLocalMetadata()
        val usesFallbackAlbum = rawAlbum == null
        val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
        val tagLibCoverUri = tagLibMetadata?.coverBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { saveEmbeddedCover(context, "${resolvedPath ?: uri}#taglib", it) }

        LocalMediaDetails(
            sourceUri = uri,
            displayName = displayName,
            title = title,
            artist = artist,
            album = resolvedAlbum,
            usesFallbackAlbum = usesFallbackAlbum,
            albumArtist = tagLibMetadata?.albumArtist ?: containerMetadata?.albumArtist,
            composer = tagLibMetadata?.composer ?: containerMetadata?.composer,
            genre = tagLibMetadata?.genre ?: containerMetadata?.genre,
            year = tagLibMetadata?.year ?: containerMetadata?.year,
            trackNumber = tagLibMetadata?.trackNumber ?: containerMetadata?.trackNumber,
            discNumber = tagLibMetadata?.discNumber ?: containerMetadata?.discNumber,
            durationMs = tagLibMetadata?.durationMs ?: queried.durationMs ?: 0L,
            fileExtension = fileExtension,
            mimeType = queried.mimeType,
            audioMimeType = null,
            bitrateKbps = tagLibMetadata?.bitrateKbps,
            sampleRateHz = tagLibMetadata?.sampleRateHz,
            channelCount = tagLibMetadata?.channelCount,
            bitsPerSample = null,
            sizeBytes = queried.sizeBytes ?: file?.length() ?: resolveSizeFromAssetDescriptor(context, uri),
            lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
            filePath = file?.absolutePath ?: queried.filePath,
            coverUri = resolveCoverReferenceByPriority(
                sidecarReference = nearbyCoverReference,
                embeddedReference = tagLibCoverUri
            ),
            coverSource = when {
                nearbyCoverReference != null -> {
                    context.getString(R.string.local_song_cover_external)
                }
                tagLibCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                else -> null
            },
            lyricContent = effectiveLyricContent,
            lyricPath = resolveEffectiveLocalLyricPath(
                reference = nearbyLyricReferences.original
                    ?: nearbyLyricFiles.original?.absolutePath
                    ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride },
                content = effectiveLyricContent
            ),
            lyricSource = when {
                hasEffectiveExternalLyric -> context.getString(R.string.local_song_lyric_external)
                !effectiveLyricContent.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
                else -> null
            },
            translatedLyricContent = effectiveTranslatedLyricContent,
            romanizedLyricContent = effectiveRomanizedLyricContent,
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: artist,
            embeddedCover = tagLibCoverUri != null,
            sourceStableKey = tagLibMetadata?.sourceStableKey
        )
    } finally {
        runCatching { retriever.release() }
    }
}
