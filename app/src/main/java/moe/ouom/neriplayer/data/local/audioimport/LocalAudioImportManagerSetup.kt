package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager.LocalSidecarDirectoryIndex
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager.LyricSidecarKind
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.local.media.isMediaStoreSidecarReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreUri
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal fun LocalAudioImportManager.isLocalSidecarIndexCandidate(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in lyricExtensions ||
        extension in imageExtensions ||
        extension == "json"
}

internal suspend fun LocalAudioImportManager.scanFolderSongsInternal(
    context: Context,
    folderUri: Uri,
    onProgress: (LocalAudioScanProgress) -> Unit,
    mediaStoreScan: suspend (LocalAudioScanProgressEmitter) -> LocalAudioImportResult?
): LocalAudioImportResult {
    val scanStartedAt = SystemClock.elapsedRealtime()
    val progress = LocalAudioScanProgressEmitter(
        scanId = scanIdGenerator.incrementAndGet(),
        startedAt = scanStartedAt,
        onProgress = onProgress
    )
    NPLogger.d(TAG, "scanFolderSongs start: uri=$folderUri")
    progress.emit(
        phase = LocalAudioScanPhase.PREPARING,
        processed = 0,
        total = 0,
        discoveredSongs = 0,
        visitedDirectories = 0,
        force = true
    )
    awaitScanStage(
        progress = progress,
        phase = LocalAudioScanPhase.PREPARING
    ) {
        LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context.applicationContext)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mediaStoreResult = mediaStoreScan(progress)
        if (shouldUseMediaStoreScanResult(mediaStoreResult)) {
            return requireNotNull(mediaStoreResult)
        }
    }

    val root = DocumentFile.fromTreeUri(context, folderUri)
    if (root == null) {
        NPLogger.w(TAG, "scanFolderSongs skipped unreadable folder: $folderUri")
        return LocalAudioImportResult(
            songs = emptyList(),
            failedCount = 1,
            completed = false
        )
    }
    val managedDownloadGate = ManagedDownloadCandidatePublicationGate(
        snapshot = loadManagedDownloadSnapshotForScan(context, progress),
        treeDocumentId = configuredManagedDownloadTreeDocumentId()
    )

    val traversalStartedAt = SystemClock.elapsedRealtime()
    val traversalResult = try {
        collectFolderCandidatesWithDocumentsContract(
            context = context,
            folderUri = folderUri,
            rootDisplayName = root.name,
            progress = progress,
            managedDownloadGate = managedDownloadGate
        )
    } catch (error: Exception) {
        if (!shouldFallbackToDocumentFileAfterTraversalFailure(error)) {
            throw error
        }
        NPLogger.w(
            TAG,
            "scanFolderSongs fast traversal unavailable, fallback DocumentFile: ${error.message}"
        )
        collectFolderCandidatesWithDocumentFile(
            context = context,
            root = root,
            progress = progress,
            managedDownloadGate = managedDownloadGate
        )
    }
    val traversalElapsedMs = SystemClock.elapsedRealtime() - traversalStartedAt
    NPLogger.d(
        TAG,
        "scanFolderSongs traversal finished: mode=${traversalResult.mode}, directories=${traversalResult.visitedDirectoryCount}, audioCandidates=${traversalResult.candidates.size}, failed=${traversalResult.failedCount}, elapsed=${traversalElapsedMs}ms"
    )

    val candidateCount = traversalResult.candidates.size
    progress.emit(
        phase = LocalAudioScanPhase.BUILDING_ENTRIES,
        processed = 0,
        total = candidateCount,
        discoveredSongs = candidateCount,
        visitedDirectories = traversalResult.visitedDirectoryCount,
        force = true
    )
    NPLogger.d(
        TAG,
        "scanFolderSongs builds filename index: candidates=$candidateCount"
    )
    val unknownArtistLabel = context.getString(R.string.music_unknown_artist)
    val quickSongs = buildList {
        traversalResult.candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            runCatching {
                buildQuickFolderScannedSong(
                    candidate = candidate,
                    unknownArtistLabel = unknownArtistLabel
                )
            }.getOrRethrowCancellation {
                NPLogger.w(TAG, "scanFolderSongs skipped ${candidate.uri}: ${it.message}")
            }?.let(::add)
            val processed = index + 1
            progress.emit(
                phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                processed = processed,
                total = candidateCount,
                discoveredSongs = size,
                visitedDirectories = traversalResult.visitedDirectoryCount,
                force = processed == candidateCount
            )
        }
    }
    val completion = run {
        completeScannedSongs(
            context = context,
            songs = orderScannedSongs(quickSongs),
            progress = progress,
            visitedDirectories = traversalResult.visitedDirectoryCount,
            knownSidecarReferences = traversalResult.candidates.associate {
                it.uri.toString() to it.knownSidecarReferences
            }
        )
    }
    val songs = completion.songs
    val failed = traversalResult.failedCount + (candidateCount - quickSongs.size)
    val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
    NPLogger.d(
        TAG,
        "scanFolderSongs finished: mode=${traversalResult.mode}, songs=${songs.size}, failed=$failed, metadataDeferred=${completion.metadataDeferred}, totalElapsed=${totalElapsedMs}ms"
    )

    progress.emit(
        phase = LocalAudioScanPhase.COMPLETED,
        processed = candidateCount,
        total = candidateCount,
        discoveredSongs = songs.size,
        visitedDirectories = traversalResult.visitedDirectoryCount,
        force = true
    )

    return LocalAudioImportResult(
        songs = if (completion.metadataDeferred) {
            distinctSongsPreservingOrder(songs)
        } else {
            orderScannedSongs(songs)
        },
        failedCount = failed,
        completed = true,
        metadataDeferred = completion.metadataDeferred
    )
}

internal fun LocalAudioImportManager.buildKnownManagedSidecarReferences(
    context: Context,
    songs: List<SongItem>
): Map<String, LocalKnownSidecarReferences> {
    val snapshot = runCatching {
        ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
    }.getOrNull() ?: return emptyMap()
    val metadataByName = snapshot.metadataEntriesByAudioName.entries.associateBy(
        keySelector = { it.key.lowercase(Locale.ROOT) },
        valueTransform = { it.value }
    )
    val metadataValuesByName = snapshot.metadataByAudioName.entries.associateBy(
        keySelector = { it.key.lowercase(Locale.ROOT) },
        valueTransform = { it.value }
    )
    return songs.mapNotNull { song ->
        val localPath = song.localFilePath
            ?.takeIf { path ->
                path == LEGACY_DOWNLOAD_ROOT ||
                    path.startsWith(LEGACY_DOWNLOAD_ROOT + File.separator)
            }
            ?: return@mapNotNull null
        val displayName = song.localFileName
            ?.takeIf(String::isNotBlank)
            ?: File(localPath).name
        val normalizedName = displayName.lowercase(Locale.ROOT)
        val metadataReference = metadataByName[normalizedName]
            ?.reference
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isMediaStoreSidecarReference)
        val coverReference = metadataValuesByName[normalizedName]
            ?.coverPath
            ?.takeIf(snapshot.knownReferences::contains)
        if (metadataReference == null && coverReference == null) {
            return@mapNotNull null
        }
        val source = song.mediaUri?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        source to LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(
            original = null,
            translated = null,
            romanized = null
            ),
            metadata = metadataReference,
            cover = coverReference
        )
    }.toMap()
}

internal fun LocalAudioImportManager.configuredManagedDownloadTreeDocumentId(): String? {
    val configuredUri = ManagedDownloadStorage.configuredDirectoryUri()
        ?.takeIf(String::isNotBlank)
        ?.toUri()
        ?: return null
    return runCatching {
        DocumentsContract.getTreeDocumentId(configuredUri)
    }.getOrNull()
}

internal suspend fun LocalAudioImportManager.loadManagedDownloadSnapshotForScan(
    context: Context,
    progress: LocalAudioScanProgressEmitter? = null
): ManagedDownloadStorage.DownloadLibrarySnapshot? {
    val cachedSnapshot = runCatching {
        if (progress == null) {
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
        } else {
            awaitScanStage(
                progress = progress,
                phase = LocalAudioScanPhase.READING_DOWNLOAD_INDEX
            ) {
                ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
            }
        }
    }.getOrNull()
    if (
        cachedSnapshot != null &&
            cachedSnapshot.rootEntriesComplete &&
            cachedSnapshot.sidecarEntriesComplete
    ) {
        return cachedSnapshot
    }
    return runCatching {
        if (progress == null) {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        } else {
            awaitScanStage(
                progress = progress,
                phase = LocalAudioScanPhase.READING_DOWNLOAD_INDEX
            ) {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = context,
                    forceRefresh = true
                )
            }
        }
    }.getOrRethrowCancellation { error ->
        NPLogger.w(TAG, "managed download snapshot unavailable for local scan: ${error.message}")
    }
}

internal fun LocalAudioImportManager.isManagedExternalAudioCandidate(
    context: Context,
    uri: Uri,
    sourceFile: File?
): Boolean {
    val displayName = sourceFile?.name
        ?: uri.lastPathSegment
        ?: uri.toString()
    val probe = SongItem(
        id = 0L,
        name = displayName,
        artist = "",
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        mediaUri = uri.toString(),
        localFileName = sourceFile?.name,
        localFilePath = sourceFile?.absolutePath,
        channelId = "local"
    )
    return runCatching {
        ManagedDownloadStorage.isLikelyManagedDownloadSong(context, probe)
    }.getOrRethrowCancellation { error ->
        NPLogger.d(TAG, "managed external audio check unavailable for $uri: ${error.message}")
    } ?: false
}

internal suspend fun LocalAudioImportManager.completeScannedSongs(
    context: Context,
    songs: List<SongItem>,
    progress: LocalAudioScanProgressEmitter,
    visitedDirectories: Int,
    knownSidecarReferences: Map<String, LocalKnownSidecarReferences?> = emptyMap()
): CompletedScanSongs {
    if (songs.isEmpty()) {
        return CompletedScanSongs(
            songs = emptyList(),
            metadataDeferred = false
        )
    }
    val total = songs.size
    val indexedSidecarReferences = buildKnownSidecarReferencesForSongs(
        songs = songs,
        knownSidecarReferences = knownSidecarReferences
    )
    if (shouldDeferExpensiveScanMetadata(
            songCount = total,
            threshold = COMPLETE_SCAN_METADATA_DEFER_THRESHOLD
        )
    ) {
        // 大批量扫描先读取轻量 metadata 侧车, 音频容器解析放到播放或编辑时
        val dispatcher = Dispatchers.IO.limitedParallelism(COMPLETE_SCAN_PARALLELISM)
        val sidecarHydrated = coroutineScope {
            val hydrated = ArrayList<SongItem>(total)
            // 不要为数万首歌曲同时保留 Deferred，分批仍保持输入顺序
            // 并让取消在批次边界及时生效
            songs.chunked(COMPLETE_SCAN_BATCH_SIZE).forEach { batch ->
                val hydratedBatch = batch.map { song ->
                    async(dispatcher) {
                        val metadataReference = song.mediaUri
                            ?.let(indexedSidecarReferences::get)
                            ?.metadata
                        // 大目录首屏只消费目录行和已经建立的侧车索引
                        // 没有侧车时探测 SAF 音频本身会退化为每首一次
                        // DocumentsProvider 查询，将嵌入元信息留到后续详情页
                        val canHydrateWithoutAudioProbe = metadataReference != null ||
                            !song.localFilePath.isNullOrBlank()
                        if (!canHydrateWithoutAudioProbe ||
                            !shouldHydrateLocalSongFastIdentity(song, metadataReference)
                        ) {
                            song
                        } else {
                            hydrateLocalSongFastIdentity(
                                context = context,
                                song = song,
                                metadataReference = metadataReference
                            )
                        }
                    }
                }.awaitAll()
                hydrated += hydratedBatch
            }
            hydrated
        }
        progress.emit(
            phase = LocalAudioScanPhase.HYDRATING_METADATA,
            processed = total,
            total = total,
            discoveredSongs = total,
            visitedDirectories = visitedDirectories,
            force = true
        )
        return CompletedScanSongs(
            songs = sidecarHydrated,
            metadataDeferred = true
        )
    }
    val allowExpensiveFallback = !shouldDeferExpensiveScanMetadata(
        songCount = total,
        threshold = COMPLETE_SCAN_METADATA_DEFER_THRESHOLD
    )
    progress.emit(
        phase = LocalAudioScanPhase.HYDRATING_METADATA,
        processed = 0,
        total = total,
        discoveredSongs = total,
        visitedDirectories = visitedDirectories,
        force = true
    )
    val dispatcher = Dispatchers.IO.limitedParallelism(COMPLETE_SCAN_PARALLELISM)
    val completed = coroutineScope {
        val hydratedSongs = ArrayList<SongItem>(total)
        var processed = 0
        songs.chunked(COMPLETE_SCAN_PARALLELISM).forEach { batch ->
            val hydratedBatch = batch.map { song ->
                async(dispatcher) {
                    val knownReferences = song.mediaUri
                        ?.let(indexedSidecarReferences::get)
                    val canProbe = allowExpensiveFallback ||
                        !song.localFilePath.isNullOrBlank() ||
                        knownReferences != null
                    if (!canProbe) {
                        song
                    } else {
                        try {
                            hydrateLocalSongTextMetadataWithKnownSidecars(
                                context = context,
                                song = hydrateLocalSongFromMetadataSidecar(
                                    context = context,
                                    song = song,
                                    metadataReference = knownReferences?.metadata
                                ),
                                resolveCoverFallback = allowExpensiveFallback ||
                                    !song.localFilePath.isNullOrBlank(),
                                includeEmbeddedFallback = allowExpensiveFallback,
                                includeEmbeddedCoverFallback = false,
                                resolveDurationFallback = allowExpensiveFallback ||
                                    !song.localFilePath.isNullOrBlank(),
                                knownSidecarReferences = knownReferences
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            NPLogger.w(
                                TAG,
                                "complete local scan metadata failed for ${song.name}: " +
                                    error.message
                            )
                            song
                        }
                    }
                }
            }.awaitAll()
            hydratedSongs += hydratedBatch
            processed += batch.size
            progress.emit(
                phase = LocalAudioScanPhase.HYDRATING_METADATA,
                processed = processed,
                total = total,
                discoveredSongs = total,
                visitedDirectories = visitedDirectories,
                force = true
            )
        }
        hydratedSongs
    }
    progress.emit(
        phase = LocalAudioScanPhase.HYDRATING_METADATA,
        processed = total,
        total = total,
        discoveredSongs = total,
        visitedDirectories = visitedDirectories,
        force = true
    )
    return CompletedScanSongs(
        songs = completed,
        metadataDeferred = false
    )
}

internal fun LocalAudioImportManager.buildKnownSidecarReferencesForSongs(
    songs: List<SongItem>,
    knownSidecarReferences: Map<String, LocalKnownSidecarReferences?>
): Map<String, LocalKnownSidecarReferences?> {
    if (songs.isEmpty()) return knownSidecarReferences

    val resolved = HashMap<String, LocalKnownSidecarReferences?>(knownSidecarReferences)
    val directoryIndexes = HashMap<String, LocalSidecarDirectoryIndex>()
    val legacyRoot = File(LEGACY_DOWNLOAD_ROOT)

    fun index(directory: File): LocalSidecarDirectoryIndex {
        val key = directory.absolutePath
        return directoryIndexes.getOrPut(key) {
            val filesByName = directory.listFiles { file ->
                file.isFile && isLocalSidecarIndexCandidate(file.name)
            }
                ?.asSequence()
                ?.take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
                .orEmpty()
                .mapNotNull { file ->
                    file.name.takeIf(String::isNotBlank)?.let { name ->
                        name.lowercase(Locale.ROOT) to file.absolutePath
                    }
                }
                .toMap()
            val metadataByAudioName = buildLocalSidecarMetadataIndex(filesByName)
            LocalSidecarDirectoryIndex(
                filesByName = filesByName,
                metadataByAudioName = metadataByAudioName
            )
        }
    }

    fun isInside(file: File, directory: File): Boolean {
        val filePath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val directoryPath = runCatching { directory.canonicalPath }
            .getOrElse { directory.absolutePath }
            .trimEnd(File.separatorChar)
        return filePath == directoryPath ||
            filePath.startsWith(directoryPath + File.separatorChar)
    }

    songs.forEach { song ->
        val key = song.mediaUri ?: return@forEach
        val existing = resolved[key]
        val mediaStoreSource = runCatching { key.toUri() }
            .getOrNull()
            ?.let(::isMediaStoreUri) == true
        val localFile = if (mediaStoreSource) {
            null
        } else {
            song.localFilePath
                ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                ?.let { path ->
                    if (path.startsWith("/")) {
                        File(path)
                    } else {
                        runCatching { path.toUri().path }
                            .getOrNull()
                            ?.takeIf { it.startsWith("/") }
                            ?.let(::File)
                    }
                }
                ?: key.takeIf { it.startsWith("/") }
                    ?.let(::File)
        }
        val parent = localFile?.parentFile ?: return@forEach
        val baseName = localFile.nameWithoutExtension
        val searchDirectories = buildList {
            if (isInside(localFile, legacyRoot)) {
                add(File(legacyRoot, "Lyrics"))
            }
            add(File(parent, "Lyrics"))
            add(parent)
            if (isInside(localFile, legacyRoot)) {
                add(legacyRoot)
            }
        }.distinctBy(File::getAbsolutePath)
        val indexes = searchDirectories.map(::index)

        fun find(kind: LyricSidecarKind): String? {
            val names = lyricSidecarNames(baseName, kind)
            return indexes.asSequence()
                .mapNotNull { directoryIndex ->
                    names.firstNotNullOfOrNull { name ->
                        directoryIndex.filesByName[name.lowercase()]
                    }
                }
                .firstOrNull()
        }

        val metadataReference =
            searchDirectories.firstNotNullOfOrNull { directory ->
                index(directory).metadataByAudioName[localFile.name.lowercase(Locale.ROOT)]
            }
        resolved[key] = LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(
                original = existing?.lyrics?.original
                    ?: find(LyricSidecarKind.ORIGINAL),
                translated = existing?.lyrics?.translated
                    ?: find(LyricSidecarKind.TRANSLATED),
                romanized = existing?.lyrics?.romanized
                    ?: find(LyricSidecarKind.ROMANIZED)
            ),
            metadata = existing?.metadata ?: metadataReference,
            cover = existing?.cover
        )
    }
    return resolved
}

internal fun LocalAudioImportManager.orderScannedSongs(songs: List<SongItem>): List<SongItem> {
    return songs
        .distinctBy { it.identity() }
        .sortedWith(localSongSourceCreationComparator())
}

internal fun LocalAudioImportManager.distinctSongsPreservingOrder(songs: List<SongItem>): List<SongItem> {
    return songs.distinctBy { it.identity() }
}

internal suspend fun LocalAudioImportManager.scanExternalStorageFolderWithMediaStore(
    context: Context,
    folderUri: Uri,
    progress: LocalAudioScanProgressEmitter
): LocalAudioImportResult? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return null
    }
    val scope = resolveExternalStorageFolderMediaStoreScope(context, folderUri) ?: return null
    // 空的相对路径表示整个存储卷，不能当作安全的 MediaStore 子目录查询
    // 交给 DocumentsContract 遍历来确认准确范围
    if (scope.relativePath.isBlank()) return null
    val audioUri = MediaStore.Audio.Media.getContentUri(scope.volumeName)
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        "_data"
    )
    val selection = if (scope.relativePath.isBlank()) {
        null
    } else {
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
    }
    val selectionArgs = scope.relativePath
        .takeIf(String::isNotBlank)
        ?.let { relativePath -> arrayOf("${escapeMediaStoreLikeValue(relativePath)}%") }
    val managedDownloadGate = ManagedDownloadCandidatePublicationGate(
        snapshot = loadManagedDownloadSnapshotForScan(context, progress),
        treeDocumentId = configuredManagedDownloadTreeDocumentId()
    )
    val knownSidecarReferences = HashMap<String, LocalKnownSidecarReferences>()
    val sidecarResolver = MediaStoreSidecarResolver(
        context = context,
        folderUri = folderUri,
        selectedRelativePath = scope.relativePath
    )
    var processedRows = 0
    var scannedRowCount = 0
    var acceptedRows = 0
    var skippedStaleRows = 0
    var withheldManagedRows = 0
    var safAudioFallbackRows = 0
    var mediaStoreQueryTotalCount = 0

    val rawResult = try {
        queryMediaStoreWithProgress(
            context = context,
            uri = audioUri,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            progress = progress
        )?.use { query ->
            val cursor = query.cursor
            val totalCount = query.totalCount
            mediaStoreQueryTotalCount = totalCount.coerceAtLeast(0)
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateAddedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val dateModifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataPathIndex = cursor.getColumnIndex("_data")
            val unknownArtistLabel = context.getString(R.string.music_unknown_artist)
            val songs = ArrayList<SongItem>(totalCount.coerceAtLeast(0))
            var resolvedPathCount = 0
            var mediaStoreCoverHitCount = 0
            var slowRowCount = 0
            val startedAt = SystemClock.elapsedRealtime()

            progress.emit(
                phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                processed = 0,
                total = totalCount.coerceAtLeast(0),
                discoveredSongs = 0,
                visitedDirectories = 0,
                force = true
            )
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                scannedRowCount++
                progress.emit(
                    phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                    processed = scannedRowCount,
                    total = totalCount.coerceAtLeast(scannedRowCount),
                    discoveredSongs = songs.size,
                    visitedDirectories = 0
                )
                val rowStartedAt = SystemClock.elapsedRealtime()
                val contentUri = Uri.withAppendedPath(
                    audioUri,
                    cursor.getLong(idIndex).toString()
                )
                val displayName = displayNameIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                    ?.takeIf(String::isNotBlank)
                    ?: contentUri.lastPathSegment
                    ?: contentUri.toString()
                val rowRelativePath = relativePathIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                if (!isMediaStoreRowInFolderScope(
                        rowRelativePath = rowRelativePath,
                        selectedRelativePath = scope.relativePath
                    )
                ) {
                    skippedStaleRows++
                    continue
                }
                val resolvedPath = resolveScannedFilePath(
                    rawPath = dataPathIndex
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getString),
                    relativePath = rowRelativePath,
                    displayName = displayName
                )
                val resolvedFile = resolvedPath?.let(::File)?.takeIf(File::isFile)
                if (resolvedFile != null) {
                    resolvedPathCount++
                }
                // MediaStore 的 _data 或 content URI 可能在切换存储权限后暂时失效
                // 目录索引中的同名音频文档作为有界 SAF 回退，避免出现不可播放条目
                val safAudioReference = sidecarResolver.resolveAudioReference(
                    relativePath = rowRelativePath,
                    displayName = displayName
                )
                val candidateReferences = buildList {
                    add(contentUri.toString())
                    resolvedFile?.absolutePath?.let(::add)
                    resolvedFile?.toURI()?.toString()?.let(::add)
                    safAudioReference?.takeIf(String::isNotBlank)?.let(::add)
                }
                if (!managedDownloadGate.evaluateRelativePath(
                        relativePath = rowRelativePath,
                        displayName = displayName,
                        candidateReferences = candidateReferences
                    ).shouldPublish
                ) {
                    withheldManagedRows++
                    continue
                }
                val mediaStoreCoverUri = albumIdIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it > 0L }
                    ?.let(LocalMediaSupport::mediaStoreAlbumArtUri)
                val sidecarReferences = sidecarResolver.resolve(
                    relativePath = rowRelativePath,
                    displayName = displayName
                )
                val shouldProbeContentReference = shouldProbeMediaStoreContentReference(
                    rowOrdinal = scannedRowCount,
                    hasResolvedFile = resolvedFile != null,
                    probeLimit = COMPLETE_SCAN_CONTENT_PROBE_LIMIT
                )
                val hasReadableMediaStoreReference = hasUsableMediaStoreContentReference(
                    rowOrdinal = scannedRowCount,
                    hasResolvedFile = resolvedFile != null,
                    probeSucceeded = !shouldProbeContentReference ||
                        probeReadableContentReference(context, contentUri),
                    probeLimit = COMPLETE_SCAN_CONTENT_PROBE_LIMIT
                )
                if (!shouldKeepMediaStoreAudioRow(
                        hasResolvedFile = resolvedFile != null,
                        hasProviderAudioReference = !safAudioReference.isNullOrBlank(),
                        hasReadableMediaStoreReference = hasReadableMediaStoreReference
                    )
                ) {
                    skippedStaleRows++
                    continue
                }
                mediaStoreCoverUri?.let { mediaStoreCoverHitCount++ }
                val nearbyCoverUri = sidecarResolver.resolveNearbyCoverReference(
                    relativePath = rowRelativePath,
                    displayName = displayName
                )
                val sourceReference = if (
                    resolvedFile == null &&
                        !hasReadableMediaStoreReference &&
                        !safAudioReference.isNullOrBlank()
                ) {
                    safAudioReference
                } else {
                    contentUri.toString()
                }
                if (sourceReference == safAudioReference) {
                    safAudioFallbackRows++
                }
                sidecarReferences?.let { references ->
                    knownSidecarReferences[sourceReference] = references
                    if (sourceReference != contentUri.toString()) {
                        knownSidecarReferences[contentUri.toString()] = references
                    }
                }
                val dateAddedSeconds = dateAddedIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                val dateModifiedSeconds = dateModifiedIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                val filesystemCreation = resolvedFile
                    ?.let(::resolveFilesystemCreationObservation)
                val mediaStoreSourceAddedAt = run {
                    resolveMediaStoreSourceAddedAt(
                        dateAddedSeconds = dateAddedSeconds,
                        dateModifiedSeconds = dateModifiedSeconds
                    )
                }
                val sourceAddedAt = resolveScannedSourceAddedAt(
                    preferredTimestampMs = filesystemCreation?.timestampMs,
                    fallbackTimestampMs = mediaStoreSourceAddedAt
                )
                val sourceAddedAtSource = when {
                    filesystemCreation != null -> "FILESYSTEM_BIRTH"
                    dateAddedSeconds?.let { it > 0L } == true -> "MEDIASTORE_DATE_ADDED"
                    dateModifiedSeconds?.let { it > 0L } == true -> "MEDIASTORE_DATE_MODIFIED"
                    else -> "UNKNOWN"
                }
                val sourceAddedAtConfidence = run {
                    when (sourceAddedAtSource) {
                        "FILESYSTEM_BIRTH" -> filesystemCreation?.confidence ?: "EXACT"
                        "MEDIASTORE_DATE_ADDED" -> "PROVIDER_REPORTED"
                        "MEDIASTORE_DATE_MODIFIED" -> "INFERRED"
                        else -> "UNKNOWN"
                    }
                }
                songs += buildQuickImportedSong(
                    seed = QuickImportedSongSeed(
                        sourceRef = sourceReference,
                        displayName = displayName,
                        title = titleIndex
                            .takeIf { !cursor.isNull(it) }
                            ?.let(cursor::getString),
                        artist = artistIndex
                            .takeIf { !cursor.isNull(it) }
                            ?.let(cursor::getString),
                        album = albumIndex
                            .takeIf { !cursor.isNull(it) }
                            ?.let(cursor::getString),
                        durationMs = durationIndex
                            .takeIf { !cursor.isNull(it) }
                            ?.let(cursor::getLong),
                        sourceAddedAt = sourceAddedAt,
                        sourceAddedAtSource = sourceAddedAtSource,
                        sourceAddedAtConfidence = sourceAddedAtConfidence,
                        localFile = resolvedFile,
                        nearbyCoverUri = nearbyCoverUri,
                        mediaStoreCoverUri = mediaStoreCoverUri,
                        stableIdentitySource = contentUri.toString()
                    ),
                    unknownArtistLabel = unknownArtistLabel
                )
                acceptedRows++
                processedRows++
                val rowElapsedMs = SystemClock.elapsedRealtime() - rowStartedAt
                if (rowElapsedMs >= SLOW_SCAN_ITEM_THRESHOLD_MS) {
                    slowRowCount++
                    NPLogger.d(
                        TAG,
                        "scanFolderSongs MediaStore slow row: " +
                            "cost=${rowElapsedMs}ms, uri=$contentUri"
                    )
                }
                progress.emit(
                    phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                    processed = scannedRowCount,
                    total = totalCount.coerceAtLeast(scannedRowCount),
                    discoveredSongs = songs.size,
                    visitedDirectories = 0
                )
            }
            NPLogger.d(
                TAG,
                    "scanFolderSongs MediaStore finished: volume=${scope.volumeName}, " +
                    "relativePath=${scope.relativePath}, songs=${songs.size}, " +
                    "queryRows=$totalCount, acceptedRows=$acceptedRows, " +
                    "resolvedPaths=$resolvedPathCount, " +
                    "skippedStaleRows=$skippedStaleRows, " +
                    "withheldManagedRows=$withheldManagedRows, " +
                    "safAudioFallbackRows=$safAudioFallbackRows, " +
                    "mediaStoreCoverHits=$mediaStoreCoverHitCount, " +
                    "slowRows=$slowRowCount, elapsed=" +
                    "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            LocalAudioImportResult(
                songs = orderScannedSongs(songs),
                failedCount = 0,
                completed = true,
                metadataDeferred = false
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "scanFolderSongs MediaStore fast path unavailable for $folderUri: ${error.message}"
        )
        null
    }
    if (rawResult == null) return null
    val completion = completeScannedSongs(
        context = context,
        songs = rawResult.songs,
        progress = progress,
        visitedDirectories = 0,
        knownSidecarReferences = knownSidecarReferences
    )
    val completedSongs = completion.songs
    progress.emit(
        phase = LocalAudioScanPhase.COMPLETED,
        processed = maxOf(mediaStoreQueryTotalCount, acceptedRows),
        total = maxOf(mediaStoreQueryTotalCount, acceptedRows),
        discoveredSongs = completedSongs.size,
        visitedDirectories = 0,
        force = true
    )
    return rawResult.copy(
        songs = if (completion.metadataDeferred) {
            distinctSongsPreservingOrder(completedSongs)
        } else {
            orderScannedSongs(completedSongs)
        },
        metadataDeferred = completion.metadataDeferred
    )
}
