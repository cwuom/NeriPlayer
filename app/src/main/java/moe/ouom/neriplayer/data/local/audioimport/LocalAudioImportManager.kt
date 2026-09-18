package moe.ouom.neriplayer.data.local.audioimport

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.audioimport/LocalAudioImportManager
 * Updated: 2026/3/23
 */


import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.policy.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.parseManagedDownloadBaseName
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.local.media.CoverReferenceValidation
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreSidecarReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreUri
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.local.media.validateCoverReference
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocalAudioImportManager {
    internal const val TAG = "LocalAudioImport"
    internal val scanIdGenerator = AtomicLong(0L)
    internal const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
        "com.android.externalstorage.documents"
    internal const val EXTERNAL_STORAGE_PRIMARY_VOLUME_ID = "primary"
    internal const val SCAN_PROGRESS_LOG_INTERVAL = 200
    internal const val SLOW_SCAN_ITEM_THRESHOLD_MS = 120L
    internal const val COMPLETE_SCAN_PARALLELISM = 24
    // 限制协程和临时结果数量，避免超大曲库放大内存占用
    internal const val COMPLETE_SCAN_BATCH_SIZE = COMPLETE_SCAN_PARALLELISM * 4
    // SAF 在几百首规模就可能因逐首容器解析超过刷新预算，超过阈值先返回
    // 目录行和侧车元数据，嵌入标签在后台或详情页按需读取
    // 对没有 _data 的 MediaStore 行做有界可读性确认，超出上限后信任同次查询返回的 content URI
    internal const val COMPLETE_SCAN_CONTENT_PROBE_LIMIT = 4_096
    internal const val SCAN_WAIT_HEARTBEAT_MS = 500L
    // 超过小批量后不在刷新路径解析音频容器，避免大曲库拖慢首屏和扫描
    internal const val COMPLETE_SCAN_METADATA_DEFER_THRESHOLD = 256
    internal const val LOCAL_SIDECAR_INDEX_MAX_ENTRIES = 8_192
    internal const val LEGACY_DOWNLOAD_ROOT = "/storage/emulated/0/neriplayer-download"
    internal const val MAX_EXTERNAL_IMPORT_COUNT = 500
    internal const val MAX_EXTERNAL_IMPORT_BYTES = 2L * 1024L * 1024L * 1024L
    internal val audioExtensions = LOCAL_AUDIO_FILE_EXTENSIONS
    internal val lyricExtensions = listOf("lrc", "txt")
    internal val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
    internal val coverNames = listOf("cover", "folder", "front")


    internal val quickMetadataPlaceholders = setOf(
        "<unknown>",
        "<unknown artist>",
        "<unknown album>",
        "unknown",
        "unknown artist",
        "unknown album",
        "未知",
        "未知歌手",
        "未知艺术家",
        "未知专辑"
    )
    internal val managedDownloadSourceNames = setOf(
        "netease",
        "bilibili",
        "youtube",
        "youtube_music",
        "youtubemusic"
    )

    internal data class LocalSidecarDirectoryIndex(
        val filesByName: Map<String, String>,
        val metadataByAudioName: Map<String, String>
    )

    suspend fun importExternalSongs(context: Context, uris: List<Uri>): LocalAudioImportResult = withContext(Dispatchers.IO) {
        LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context.applicationContext)
        val songs = mutableListOf<SongItem>()
        var failedCount = 0
        var withheldManagedCount = 0
        val managedDownloadGate = ManagedDownloadCandidatePublicationGate(
            snapshot = loadManagedDownloadSnapshotForScan(context),
            treeDocumentId = configuredManagedDownloadTreeDocumentId()
        )

        val distinctUris = uris.distinctBy { it.toString() }
        if (distinctUris.size > MAX_EXTERNAL_IMPORT_COUNT) {
            NPLogger.w(
                TAG,
                "external import clipped: count=${distinctUris.size}, limit=$MAX_EXTERNAL_IMPORT_COUNT"
            )
        }

        distinctUris.take(MAX_EXTERNAL_IMPORT_COUNT).forEach { uri ->
            val sourceFile = resolveSourceFile(context, uri)
            val sourceCopyInfo = if (
                uri.scheme.equals("content", ignoreCase = true) &&
                    uri.authority != MediaStore.AUTHORITY
            ) {
                queryExternalAudioCopyInfo(context, uri, sourceFile)
            } else {
                null
            }
            val displayName = sourceFile?.name
                ?: sourceCopyInfo?.displayName
                ?: uri.lastPathSegment
                ?: uri.toString()
            val candidateReferences = buildList {
                add(uri.toString())
                sourceFile?.absolutePath?.let(::add)
                sourceFile?.toURI()?.toString()?.let(::add)
            }
            if (!managedDownloadGate.evaluate(
                    isInsideManagedRoot = isManagedExternalAudioCandidate(
                        context = context,
                        uri = uri,
                        sourceFile = sourceFile
                    ),
                    displayName = displayName,
                    candidateReferences = candidateReferences
                ).shouldPublish
            ) {
                withheldManagedCount++
                NPLogger.d(TAG, "skip unfinalized managed external audio: $uri")
                return@forEach
            }
            val stabilizedAudio = runCatching {
                stabilizeExternalUri(
                    context = context,
                    uri = uri,
                    sourceFile = sourceFile,
                    copyInfo = sourceCopyInfo
                )
            }.getOrRethrowCancellation {
                NPLogger.e(TAG, "Failed to stabilize external audio: $uri", it)
            }

            if (stabilizedAudio == null) {
                failedCount++
                return@forEach
            }

            val song = runCatching {
                val imported = buildQuickImportedSong(context, stabilizedAudio.uri).let { song ->
                    song.copy(sourceModifiedAtMs = stabilizedAudio.sourceModifiedAtMs ?: song.sourceModifiedAtMs)
                }
                val sourceAddedAt = stabilizedAudio.sourceAddedAt
                if (sourceAddedAt == null || sourceAddedAt <= 0L) {
                    imported
                } else {
                    imported.copy(
                        addedAt = sourceAddedAt,
                        logicalCreatedAtMs = sourceAddedAt.takeUnless {
                            isNonCreationTimestampSource(stabilizedAudio.sourceAddedAtSource)
                        },
                        createdAtSource = stabilizedAudio.sourceAddedAtSource,
                        createdAtConfidence = stabilizedAudio.sourceAddedAtConfidence
                    )
                }
            }.getOrRethrowCancellation {
                NPLogger.e(
                    TAG,
                    "Failed to import stabilized external audio: ${stabilizedAudio.uri}",
                    it
                )
            }

            if (song != null) {
                songs += song
            } else {
                failedCount++
            }
        }

        if (withheldManagedCount > 0) {
            NPLogger.d(
                TAG,
                "external import withheld unfinalized managed audio: $withheldManagedCount"
            )
        }

        LocalAudioImportResult(
            songs = orderScannedSongs(songs),
            failedCount = failedCount,
            completed = true,
            metadataDeferred = false
        )
    }

    suspend fun scanFolderSongs(
        context: Context,
        folderUri: Uri,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        scanFolderSongsInternal(
            context = context,
            folderUri = folderUri,
            onProgress = onProgress,
            mediaStoreScan = { progress ->
                scanExternalStorageFolderWithMediaStore(context, folderUri, progress)
            }
        )
    }

    internal suspend fun scanFolderSongsWithMediaStoreResultForTest(
        context: Context,
        folderUri: Uri,
        mediaStoreResult: LocalAudioImportResult?,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        scanFolderSongsInternal(
            context = context,
            folderUri = folderUri,
            onProgress = onProgress,
            mediaStoreScan = { mediaStoreResult }
        )
    }









    internal suspend fun <T> awaitScanStage(
        progress: LocalAudioScanProgressEmitter,
        phase: LocalAudioScanPhase,
        block: suspend () -> T
    ): T = coroutineScope {
        progress.emitWaitingHeartbeat(phase)
        val pending = async(Dispatchers.IO) { block() }
        while (!pending.isCompleted) {
            withTimeoutOrNull(SCAN_WAIT_HEARTBEAT_MS) {
                pending.join()
            }
            if (!pending.isCompleted) {
                progress.emitWaitingHeartbeat(phase)
            }
        }
        pending.await()
    }

    internal suspend fun queryMediaStoreWithProgress(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        progress: LocalAudioScanProgressEmitter
    ): MediaStoreQueryResult? = awaitScanStage(
        progress = progress,
        phase = LocalAudioScanPhase.QUERYING_MEDIA_STORE
    ) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    cancellationSignal
                )
                val result = cursor?.let { queriedCursor ->
                    MediaStoreQueryResult(
                        cursor = queriedCursor,
                        totalCount = queriedCursor.count
                    )
                }
                continuation.resume(result) { _, cancelledResult, _ ->
                    cancelledResult?.close()
                }
            } catch (error: Throwable) {
                cursor?.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }









    /** 去重不再重复排序，供已经按来源时间排序的扫描结果使用 */






    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun parseExternalStorageFolderMediaStoreScope(
        documentId: String,
        knownVolumeNames: Set<String>
    ): ExternalStorageFolderMediaStoreScope? {
        val separatorIndex = documentId.indexOf(':')
        if (separatorIndex <= 0) {
            return null
        }
        val volumeId = documentId.substring(0, separatorIndex)
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        val volumeName = if (volumeId.equals(EXTERNAL_STORAGE_PRIMARY_VOLUME_ID, ignoreCase = true)) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            knownVolumeNames.firstOrNull { knownName ->
                knownName.equals(volumeId, ignoreCase = true)
            }
        } ?: return null
        val relativePath = documentId
            .substring(separatorIndex + 1)
            .trim()
            .trim('/')
            .takeIf(String::isNotBlank)
            ?.plus('/')
            .orEmpty()
        return ExternalStorageFolderMediaStoreScope(
            volumeName = volumeName,
            relativePath = relativePath
        )
    }

    internal fun isMediaStoreRowInFolderScope(
        rowRelativePath: String?,
        selectedRelativePath: String
    ): Boolean {
        fun normalize(path: String?): String {
            return path
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.takeIf(String::isNotBlank)
                ?.plus('/')
                .orEmpty()
        }
        val selected = normalize(selectedRelativePath)
        val row = normalize(rowRelativePath)
        return if (selected.isBlank()) {
            row.isBlank()
        } else {
            row == selected || row.startsWith(selected)
        }
    }



    /**
     * 全盘扫描设备上的本地音频 (常见音乐格式)
     */
    suspend fun scanDeviceSongs(
        context: Context,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val scanStartedAt = SystemClock.elapsedRealtime()
        val progress = LocalAudioScanProgressEmitter(
            scanId = scanIdGenerator.incrementAndGet(),
            startedAt = scanStartedAt,
            onProgress = onProgress
        )
        NPLogger.d(TAG, "scanDeviceSongs start")
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
        val songs = mutableListOf<SongItem>()
        var failed = 0
        var completed = false
        var rawRowCount = 0
        var acceptedRowCount = 0
        var slowItemCount = 0
        var mediaStoreCoverHitCount = 0
        var withheldManagedRowCount = 0
        var mediaStoreQueryTotalCount = 0
        val managedSidecarIndex = ManagedMediaStoreSidecarIndex(
            snapshot = loadManagedDownloadSnapshotForScan(context, progress),
            treeDocumentId = configuredManagedDownloadTreeDocumentId()
        )
        val indexedManagedSidecarReferences = HashMap<String, LocalKnownSidecarReferences>()

        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val includeRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (includeRelativePath) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add("_data")
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"

        runCatching {
            queryMediaStoreWithProgress(
                context = context,
                uri = audioUri,
                projection = projection,
                selection = selection,
                selectionArgs = null,
                progress = progress
            )?.use { query ->
                val cursor = query.cursor
                mediaStoreQueryTotalCount = query.totalCount.coerceAtLeast(0)
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val idxArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val idxAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val idxAlbumId = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val idxDisplayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val idxRelativePath = if (includeRelativePath) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val idxDateAdded = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val idxDateModified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val idxData = cursor.getColumnIndex("_data")
                progress.emit(
                    phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                    processed = 0,
                    total = mediaStoreQueryTotalCount,
                    discoveredSongs = 0,
                    visitedDirectories = 0,
                    force = true
                )
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val itemStartedAt = SystemClock.elapsedRealtime()
                    rawRowCount++
                    progress.emit(
                        phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                        processed = rawRowCount,
                        total = mediaStoreQueryTotalCount.coerceAtLeast(rawRowCount),
                        discoveredSongs = songs.size,
                        visitedDirectories = 0
                    )
                    val id = cursor.getLong(idxId)
                    val duration = cursor.getLong(idxDuration)
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val relativePath = idxRelativePath
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getString)
                    val resolvedPath = resolveScannedFilePath(
                        rawPath = idxData.takeIf { it >= 0 }?.let(cursor::getString),
                        relativePath = relativePath,
                        displayName = idxDisplayName.takeIf { it >= 0 }?.let(cursor::getString)
                    )
                    val resolvedFile = resolvedPath?.let(::File)?.takeIf(File::isFile)
                    val displayName = resolvedFile?.name
                        ?: idxDisplayName.takeIf { it >= 0 }?.let(cursor::getString)
                        ?: contentUri.lastPathSegment
                        ?: contentUri.toString()
                    val candidateReferences = buildList {
                        add(contentUri.toString())
                        resolvedFile?.absolutePath?.let(::add)
                        resolvedFile?.toURI()?.toString()?.let(::add)
                    }
                    if (!managedSidecarIndex.shouldPublish(
                            relativePath = relativePath,
                            displayName = displayName,
                            candidateReferences = candidateReferences
                        )
                    ) {
                        withheldManagedRowCount++
                        continue
                    }
                    val shouldProbeContentReference = shouldProbeMediaStoreContentReference(
                        rowOrdinal = rawRowCount,
                        hasResolvedFile = resolvedFile != null,
                        probeLimit = COMPLETE_SCAN_CONTENT_PROBE_LIMIT
                    )
                    val hasReadableMediaStoreReference = hasUsableMediaStoreContentReference(
                        rowOrdinal = rawRowCount,
                        hasResolvedFile = resolvedFile != null,
                        probeSucceeded = !shouldProbeContentReference ||
                            probeReadableContentReference(context, contentUri),
                        probeLimit = COMPLETE_SCAN_CONTENT_PROBE_LIMIT
                    )
                    if (!shouldKeepMediaStoreAudioRow(
                            hasResolvedFile = resolvedFile != null,
                            hasProviderAudioReference = false,
                            hasReadableMediaStoreReference = hasReadableMediaStoreReference
                        )
                    ) {
                        NPLogger.d(TAG, "skip stale MediaStore audio row: uri=$contentUri, name=$displayName")
                        continue
                    }
                    acceptedRowCount++
                    progress.emit(
                        phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                        processed = rawRowCount,
                        total = mediaStoreQueryTotalCount.coerceAtLeast(rawRowCount),
                        discoveredSongs = songs.size,
                        visitedDirectories = 0
                    )
                    val mediaStoreCoverUri = idxAlbumId
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                        ?.takeIf { it > 0L }
                        ?.let(LocalMediaSupport::mediaStoreAlbumArtUri)
                        ?.also { mediaStoreCoverHitCount++ }
                    val managedSidecarReferences = managedSidecarIndex.resolve(
                        relativePath = relativePath,
                        displayName = displayName
                    )
                    managedSidecarReferences?.let { references ->
                        indexedManagedSidecarReferences[contentUri.toString()] = references
                    }
                    val dateAddedSeconds = idxDateAdded
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                    val dateModifiedSeconds = idxDateModified
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                    val filesystemCreation = resolvedFile
                        ?.let(::resolveFilesystemCreationObservation)
                    val mediaStoreSourceAddedAt = resolveMediaStoreSourceAddedAt(
                        dateAddedSeconds = dateAddedSeconds,
                        dateModifiedSeconds = dateModifiedSeconds
                    )
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
                    val sourceAddedAtConfidence = when (sourceAddedAtSource) {
                        "FILESYSTEM_BIRTH" -> filesystemCreation?.confidence ?: "EXACT"
                        "MEDIASTORE_DATE_ADDED" -> "PROVIDER_REPORTED"
                        "MEDIASTORE_DATE_MODIFIED" -> "INFERRED"
                        else -> "UNKNOWN"
                    }

                    songs += buildQuickImportedSong(
                        seed = QuickImportedSongSeed(
                            sourceRef = contentUri.toString(),
                            displayName = displayName,
                            title = idxTitle.takeIf { it >= 0 }?.let(cursor::getString),
                            artist = idxArtist.takeIf { it >= 0 }?.let(cursor::getString),
                            album = idxAlbum.takeIf { it >= 0 }?.let(cursor::getString),
                            durationMs = duration,
                            sourceAddedAt = sourceAddedAt,
                            sourceModifiedAtMs = dateModifiedSeconds.toEpochMillisOrNull(),
                            sourceAddedAtSource = sourceAddedAtSource,
                            sourceAddedAtConfidence = sourceAddedAtConfidence,
                            localFile = resolvedFile,
                            nearbyCoverUri = managedSidecarReferences?.cover,
                            mediaStoreCoverUri = mediaStoreCoverUri
                        ),
                        unknownArtistLabel = context.getString(R.string.music_unknown_artist)
                    )
                    val costMs = SystemClock.elapsedRealtime() - itemStartedAt
                    if (costMs >= SLOW_SCAN_ITEM_THRESHOLD_MS) {
                        slowItemCount++
                        NPLogger.d(TAG, "scanDeviceSongs slow item: cost=${costMs}ms, uri=$contentUri")
                    }
                    if (rawRowCount % SCAN_PROGRESS_LOG_INTERVAL == 0) {
                        NPLogger.d(
                            TAG,
                            "scanDeviceSongs progress: rows=$rawRowCount, songs=${songs.size}, slowItems=$slowItemCount, elapsed=${SystemClock.elapsedRealtime() - scanStartedAt}ms"
                        )
                    }
                }
                completed = true
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.e(TAG, "scanDeviceSongs failed: ${error.message}", error)
            failed++
        }
        val completion = if (completed) {
            val knownSidecarReferences = buildKnownManagedSidecarReferences(
                context = context,
                songs = songs
            ) + indexedManagedSidecarReferences
            completeScannedSongs(
                context = context,
                songs = orderScannedSongs(songs),
                progress = progress,
                visitedDirectories = 0,
                knownSidecarReferences = knownSidecarReferences
            )
        } else {
            CompletedScanSongs(
                songs = songs,
                metadataDeferred = false
            )
        }
        val completedSongs = completion.songs
        progress.emit(
            phase = LocalAudioScanPhase.COMPLETED,
            processed = maxOf(mediaStoreQueryTotalCount, rawRowCount),
            total = maxOf(mediaStoreQueryTotalCount, rawRowCount),
            discoveredSongs = completedSongs.size,
            visitedDirectories = 0,
            force = true
        )
        val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
        NPLogger.d(
            TAG,
            "scanDeviceSongs finished: rows=$rawRowCount, songs=${completedSongs.size}, " +
                "acceptedRows=$acceptedRowCount, " +
                "withheldManagedRows=$withheldManagedRowCount, " +
                "failed=$failed, slowItems=$slowItemCount, " +
                "mediaStoreCoverHits=$mediaStoreCoverHitCount, completed=$completed, " +
                "totalElapsed=${totalElapsedMs}ms"
        )

        LocalAudioImportResult(
            songs = if (completion.metadataDeferred) {
                distinctSongsPreservingOrder(completedSongs)
            } else {
                orderScannedSongs(completedSongs)
            },
            failedCount = failed,
            completed = completed,
            metadataDeferred = completion.metadataDeferred
        )
    }

    internal fun buildQuickImportedSong(
        seed: QuickImportedSongSeed,
        unknownArtistLabel: String
    ): SongItem {
        val resolvedSource = seed.localFile?.absolutePath ?: seed.sourceRef
        val resolvedDisplayName = seed.localFile?.name ?: seed.displayName
        val fallbackTitle = resolvedDisplayName.substringBeforeLast('.').ifBlank {
            resolvedDisplayName.ifBlank {
                resolvedSource.substringAfterLast(File.separatorChar, resolvedSource)
            }
        }
        val parsedFileName = parseFileNameMetadata(resolvedDisplayName)
        val queriedTitle = seed.title
            ?.trim()
            ?.takeIf(::isReadableQuickImportedTitle)
        val resolvedParsedTitle = resolveParsedTitleFallback(
            currentTitle = queriedTitle,
            fallbackTitle = fallbackTitle,
            fileTitle = fallbackTitle,
            parsed = parsedFileName
        )
        val resolvedTitle = resolvedParsedTitle
            ?: queriedTitle
            ?: fallbackTitle
        val queriedArtist = normalizeQuickImportedMetadata(seed.artist)
        val resolvedArtist = resolveParsedArtistFallback(
            currentArtist = queriedArtist,
            fallbackArtist = unknownArtistLabel,
            parsed = parsedFileName
        ) ?: queriedArtist
            ?: unknownArtistLabel
        val resolvedAlbumSeed = resolveParsedAlbumFallback(
            currentAlbum = normalizeQuickImportedMetadata(seed.album),
            fallbackAlbum = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            parsed = parsedFileName
        ) ?: normalizeQuickImportedMetadata(seed.album)
        val resolvedAlbum = normalizeLocalAlbumIdentity(
            album = resolvedAlbumSeed,
            usesFallbackAlbum = resolvedAlbumSeed.isNullOrBlank()
        )
        val stableId = computeStableSongId(seed.stableIdentitySource ?: resolvedSource)
        val filesystemCreation = seed.localFile?.let(::resolveFilesystemCreationObservation)
        val seedIsNonCreationTime = isNonCreationTimestampSource(seed.sourceAddedAtSource)
        val useFilesystemCreation = filesystemCreation != null &&
            (seed.sourceAddedAt == null || seedIsNonCreationTime)
        val sourceAddedAt = resolveScannedSourceAddedAt(
            preferredTimestampMs = if (useFilesystemCreation) filesystemCreation.timestampMs else seed.sourceAddedAt,
            fallbackTimestampMs = filesystemCreation?.timestampMs
        )
        val logicalCreatedAt = sourceAddedAt.takeIf { it > 0L && (!seedIsNonCreationTime || useFilesystemCreation) }
        val createdAtSource = if (useFilesystemCreation) "FILESYSTEM_BIRTH" else seed.sourceAddedAtSource
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: when {
                seed.sourceAddedAt?.let { it > 0L } == true -> "PROVIDER_NATIVE"
                filesystemCreation != null -> "FILESYSTEM_BIRTH"
                else -> "UNKNOWN"
            }
        val createdAtConfidence = if (useFilesystemCreation) filesystemCreation.confidence else seed.sourceAddedAtConfidence
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: when (createdAtSource) {
                "FILESYSTEM_BIRTH" -> filesystemCreation?.confidence ?: "EXACT"
                "PROVIDER_NATIVE" -> "PROVIDER_REPORTED"
                "MTIME_FALLBACK" -> "INFERRED"
                else -> "UNKNOWN"
            }

        return SongItem(
            id = stableId,
            name = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumId = 0L,
            durationMs = seed.durationMs?.takeIf { it > 0L } ?: 0L,
            coverUrl = seed.nearbyCoverUri ?: seed.mediaStoreCoverUri,
            mediaUri = preferredLocalMediaReference(
                localFilePath = seed.localFile?.absolutePath,
                mediaUri = seed.sourceRef
            ) ?: resolvedSource,
            originalName = resolvedTitle,
            originalArtist = resolvedArtist,
            originalCoverUrl = seed.nearbyCoverUri ?: seed.mediaStoreCoverUri,
            matchedLyric = seed.matchedLyric,
            matchedTranslatedLyric = seed.matchedTranslatedLyric,
            matchedRomanizedLyric = seed.matchedRomanizedLyric,
            originalLyric = seed.originalLyric,
            originalTranslatedLyric = seed.originalTranslatedLyric,
            originalRomanizedLyric = seed.originalRomanizedLyric,
            localFileName = resolvedDisplayName.ifBlank { null },
            localFilePath = seed.localFile?.absolutePath,
            channelId = "local",
            audioId = stableId.toString(),
            sourceStableKey = seed.sourceStableKey,
            addedAt = sourceAddedAt,
            sourceModifiedAtMs = seed.sourceModifiedAtMs.toValidTimestampMsOrNull()
                ?: seed.localFile?.lastModified().toValidTimestampMsOrNull()
                ?: seed.sourceAddedAt.takeIf {
                    isModificationTimestampSource(seed.sourceAddedAtSource)
                }.toValidTimestampMsOrNull(),
            logicalCreatedAtMs = logicalCreatedAt,
            createdAtSource = createdAtSource,
            createdAtConfidence = createdAtConfidence
        )
    }

    internal fun mergeImportedSongMetadata(
        quickSong: SongItem,
        detailedSong: SongItem
    ): SongItem {
        val resolvedName = detailedSong.name
            .takeIf(::isReadableQuickImportedTitle)
            ?: quickSong.name
        val resolvedArtist = normalizeQuickImportedMetadata(detailedSong.artist)
            ?: quickSong.artist
        val resolvedAlbum = normalizeLocalAlbumIdentity(
            album = normalizeQuickImportedMetadata(detailedSong.album) ?: quickSong.album,
            usesFallbackAlbum = false
        )
        val resolvedCoverUrl = selectMergedImportedCoverReference(
            quickCover = quickSong.coverUrl,
            detailedCover = detailedSong.coverUrl
        )
        val quickLocalPath = quickSong.localFilePath?.takeIf { it.isNotBlank() }
        val detailedLocalPath = detailedSong.localFilePath?.takeIf { it.isNotBlank() }
        val resolvedLocalPath = quickLocalPath ?: detailedLocalPath
        val shouldAdoptDetailedIdentity = quickLocalPath == null && detailedLocalPath != null
        val resolvedId = if (shouldAdoptDetailedIdentity) detailedSong.id else quickSong.id
        val resolvedAudioId = if (shouldAdoptDetailedIdentity) {
            detailedSong.audioId ?: detailedSong.id.toString()
        } else {
            quickSong.audioId ?: detailedSong.audioId
        }
        val resolvedMediaUri = preferredLocalMediaReference(
            localFilePath = resolvedLocalPath,
            mediaUri = quickSong.mediaUri ?: detailedSong.mediaUri
        ) ?: quickSong.mediaUri ?: detailedSong.mediaUri
        val resolvedSourceStableKey = quickSong.sourceStableKey ?: detailedSong.sourceStableKey

        return quickSong.copy(
            id = resolvedId,
            name = resolvedName,
            artist = resolvedArtist,
            album = resolvedAlbum,
            durationMs = detailedSong.durationMs.takeIf { it > 0L } ?: quickSong.durationMs,
            coverUrl = resolvedCoverUrl,
            matchedLyric = detailedSong.matchedLyric,
            matchedTranslatedLyric = detailedSong.matchedTranslatedLyric,
            matchedRomanizedLyric = detailedSong.matchedRomanizedLyric,
            originalName = quickSong.originalName?.takeIf { it.isNotBlank() }
                ?: detailedSong.originalName?.takeIf { it.isNotBlank() }
                ?: resolvedName,
            originalArtist = normalizeQuickImportedMetadata(quickSong.originalArtist)
                ?: normalizeQuickImportedMetadata(detailedSong.originalArtist)
                ?: resolvedArtist,
            originalCoverUrl = selectMergedImportedCoverReference(
                quickCover = quickSong.originalCoverUrl,
                detailedCover = detailedSong.originalCoverUrl
            ) ?: resolvedCoverUrl,
            originalLyric = quickSong.originalLyric ?: detailedSong.originalLyric,
            originalTranslatedLyric = quickSong.originalTranslatedLyric
                ?: detailedSong.originalTranslatedLyric,
            originalRomanizedLyric = quickSong.originalRomanizedLyric
                ?: detailedSong.originalRomanizedLyric,
            mediaUri = resolvedMediaUri,
            localFileName = quickSong.localFileName ?: detailedSong.localFileName,
            localFilePath = resolvedLocalPath,
            channelId = quickSong.channelId ?: detailedSong.channelId ?: "local",
            audioId = resolvedAudioId,
            sourceStableKey = resolvedSourceStableKey
        )
    }

    fun hydrateLocalSongMetadata(
        context: Context,
        song: SongItem,
        includeEmbeddedAssets: Boolean = true
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        val details = runCatching {
            if (includeEmbeddedAssets) {
                LocalMediaSupport.inspect(context, song)
            } else {
                LocalMediaSupport.inspectMetadataOnly(context, song)
            }
        }.getOrRethrowCancellation {
            NPLogger.w(TAG, "hydrate local metadata failed for ${song.name}: ${it.message}")
        } ?: return song

        return mergeImportedSongMetadata(
            quickSong = song,
            detailedSong = LocalMediaSupport.toSongItem(details)
        )
    }

    /**
     * 只补全标题、歌手和专辑, 用于快速扫描发现占位元信息的条目
     */
    fun hydrateLocalSongIdentityMetadata(
        context: Context,
        song: SongItem
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) return song
        val sidecarHydrated = hydrateLocalSongFastIdentity(context, song)
        if (!needsLocalIdentityMetadataProbe(sidecarHydrated)) {
            return sidecarHydrated
        }
        val details = runCatching {
            LocalMediaSupport.inspectMetadataOnly(
                context = context,
                song = sidecarHydrated,
                resolveCoverFallback = false
            )
        }.getOrRethrowCancellation {
            NPLogger.w(TAG, "hydrate local identity metadata failed for ${song.name}: ${it.message}")
        } ?: return sidecarHydrated
        return mergeImportedSongMetadata(
            quickSong = sidecarHydrated,
            detailedSong = LocalMediaSupport.toSongItem(details)
        )
    }

    internal fun needsLocalIdentityMetadataProbe(song: SongItem): Boolean {
        val artistNeedsProbe = isQuickMetadataPlaceholder(song.artist) ||
            song.artist.isBlank()
        val fileName = song.localFileName
            ?.substringBeforeLast('.', song.localFileName)
            ?.trim()
            .orEmpty()
        val titleNeedsProbe = song.name.isBlank() ||
            isQuickMetadataPlaceholder(song.name) ||
            (fileName.isNotBlank() && song.name.trim().equals(fileName, ignoreCase = true))
        val albumNeedsProbe = song.album.isBlank() ||
            isQuickMetadataPlaceholder(song.album) ||
            song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY
        return artistNeedsProbe || titleNeedsProbe || albumNeedsProbe
    }











    fun hydrateLocalSongTextMetadata(
        context: Context,
        song: SongItem,
        resolveCoverFallback: Boolean = true,
        includeEmbeddedFallback: Boolean = true,
        includeEmbeddedCoverFallback: Boolean = true,
        resolveDurationFallback: Boolean = true
    ): SongItem {
        val identityHydrated = hydrateLocalSongFastIdentity(context, song)
        return hydrateLocalSongTextMetadataWithKnownSidecars(
            context = context,
            song = identityHydrated,
            resolveCoverFallback = resolveCoverFallback,
            includeEmbeddedFallback = includeEmbeddedFallback,
            includeEmbeddedCoverFallback = includeEmbeddedCoverFallback,
            resolveDurationFallback = resolveDurationFallback,
            knownSidecarReferences = null
        )
    }

    fun hydrateLocalSongDurationMetadata(
        context: Context,
        song: SongItem
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context) || song.durationMs > 0L) {
            return song
        }
        val durationMs = song.localMediaUri()?.let { uri ->
            runCatching { LocalMediaSupport.resolveDurationFast(context, uri) }
                .onFailure {
                    NPLogger.d(TAG, "local duration probe unavailable for ${song.name}: ${it.message}")
                }
                .getOrDefault(0L)
        } ?: 0L
        return durationMs.takeIf { it > 0L }?.let { song.copy(durationMs = it) } ?: song
    }

    internal fun hydrateLocalSongTextMetadataWithKnownSidecars(
        context: Context,
        song: SongItem,
        resolveCoverFallback: Boolean = true,
        includeEmbeddedFallback: Boolean = true,
        includeEmbeddedCoverFallback: Boolean = true,
        resolveDurationFallback: Boolean = true,
        knownSidecarReferences: LocalKnownSidecarReferences? = null
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        val lyricMetadata = runCatching {
            LocalMediaSupport.inspectLyricsFast(
                context = context,
                song = song,
                includeStoredFallback = false,
                includeEmbeddedFallback = includeEmbeddedFallback,
                knownSidecarReferences = knownSidecarReferences
            )
        }.getOrRethrowCancellation {
            NPLogger.w(TAG, "hydrate local text metadata failed for ${song.name}: ${it.message}")
        } ?: return song
        val knownCover = knownSidecarReferences?.cover?.takeIf(String::isNotBlank)
        val nearbyCover = if (knownCover != null) {
            knownCover
        } else if (resolveCoverFallback && song.coverUrl.isNullOrBlank()) {
            runCatching {
                LocalMediaSupport.resolveNearbyCoverUri(context, song)
                    ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song)
                    ?: includeEmbeddedCoverFallback
                        .takeIf { it }
                        ?.let { LocalMediaSupport.resolveCoverUri(context, song) }
            }.getOrNull()
        } else {
            null
        }
        val durationMs = if (song.durationMs > 0L || !resolveDurationFallback) {
            song.durationMs
        } else {
            runCatching {
                LocalMediaSupport.inspectQuick(
                    context = context,
                    uri = song.localMediaUri() ?: return@runCatching 0L,
                    includeAudioTrackInfo = true
                ).durationMs.takeIf { it > 0L } ?: 0L
            }.onFailure {
                NPLogger.d(TAG, "local duration fallback unavailable for ${song.name}: ${it.message}")
            }.getOrDefault(0L)
        }
        return song.copy(
            coverUrl = knownCover ?: song.coverUrl ?: nearbyCover,
            originalCoverUrl = knownCover ?: song.originalCoverUrl ?: nearbyCover,
            durationMs = durationMs,
            matchedLyric = lyricMetadata.lyric ?: song.matchedLyric,
            matchedTranslatedLyric = lyricMetadata.translatedLyric
                ?: song.matchedTranslatedLyric,
            matchedRomanizedLyric = lyricMetadata.romanizedLyric
                ?: song.matchedRomanizedLyric,
            originalLyric = lyricMetadata.lyric ?: song.originalLyric,
            originalTranslatedLyric = lyricMetadata.translatedLyric
                ?: song.originalTranslatedLyric,
            originalRomanizedLyric = lyricMetadata.romanizedLyric
                ?: song.originalRomanizedLyric,
            matchedLyricSource = if (lyricMetadata.lyric != null) {
                null
            } else {
                song.matchedLyricSource
            },
            matchedSongId = if (lyricMetadata.lyric != null) {
                null
            } else {
                song.matchedSongId
            }
        )
    }

    /**
     * 只补全已经可直接访问的本地封面，避免扫描后台读取并持久化整段歌词
     */
    fun hydrateLocalSongCoverMetadata(
        context: Context,
        song: SongItem
    ): SongItem = hydrateLocalSongCoverMetadataResult(context, song).song

    internal fun hydrateLocalSongCoverMetadataResult(
        context: Context,
        song: SongItem
    ): LocalCoverHydrationResult {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return LocalCoverHydrationResult(song)
        }
        val existingCover = song.coverUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val existingCoverValidation = existingCover?.let {
            validateCoverReference(context, it)
        }
        val originalCover = song.originalCoverUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val originalCoverValidation = originalCover?.let {
            validateCoverReference(context, it)
        }
        if (existingCoverValidation == CoverReferenceValidation.USABLE) {
            if (originalCover == null || originalCoverValidation != CoverReferenceValidation.INVALID) {
                return LocalCoverHydrationResult(song)
            }
            return LocalCoverHydrationResult(song.copy(originalCoverUrl = existingCover))
        }
        val metadata = runCatching {
            LocalMediaSupport.readLocalMetadataSidecarFast(
                context = context,
                song = song
            )
        }.getOrNull()
        val resolvedCover = sequence<String?> {
            song.customCoverUrl?.let { yield(it) }
            metadata?.coverPath?.let { yield(it) }
            metadata?.customCoverUrl?.let { yield(it) }
            val managedCover = runCatching {
                resolveCachedManagedCoverReference(
                    song = song,
                    metadata = metadata ?: return@runCatching null
                )
            }.getOrNull()
            if (managedCover != null) yield(managedCover)
            val nearbyCover = runCatching {
                LocalMediaSupport.resolveNearbyCoverUri(context, song)
            }.getOrNull()
            if (nearbyCover != null) yield(nearbyCover)
            val mediaStoreCover = runCatching {
                LocalMediaSupport.peekMediaStoreAlbumArtUri(context, song)
            }.getOrNull()
            if (mediaStoreCover != null) yield(mediaStoreCover)
            val cachedEmbeddedCover = runCatching {
                LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song)
            }.getOrNull()
            if (cachedEmbeddedCover != null) yield(cachedEmbeddedCover)
            val resolvedLocalCover = runCatching {
                LocalMediaSupport.resolveCoverUri(context, song)
            }.getOrNull()
            if (resolvedLocalCover != null) yield(resolvedLocalCover)
            metadata?.coverUrl?.let { yield(it) }
            metadata?.originalCoverUrl?.let { yield(it) }
            song.originalCoverUrl?.let { yield(it) }
            }
            .mapNotNull { candidate -> candidate?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull { isUsableCoverReference(context, it) }
        if (resolvedCover == null) {
            if (
                existingCoverValidation != CoverReferenceValidation.INVALID &&
                originalCoverValidation != CoverReferenceValidation.INVALID
            ) {
                return LocalCoverHydrationResult(song)
            }
            return LocalCoverHydrationResult(
                song = song.copy(
                    coverUrl = existingCover
                        ?.takeUnless { existingCoverValidation == CoverReferenceValidation.INVALID },
                    originalCoverUrl = originalCover
                        ?.takeUnless { originalCoverValidation == CoverReferenceValidation.INVALID }
                ),
                clearCoverUrl = existingCoverValidation == CoverReferenceValidation.INVALID,
                clearOriginalCoverUrl =
                    originalCoverValidation == CoverReferenceValidation.INVALID
            )
        }
        val resolvedOriginalCover = originalCover
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { isUsableCoverReference(context, it) }
            ?: resolvedCover
        return LocalCoverHydrationResult(
            song.copy(
                coverUrl = resolvedCover,
                originalCoverUrl = resolvedOriginalCover
            )
        )
    }





















































    internal enum class LyricSidecarKind {
        ORIGINAL,
        TRANSLATED,
        ROMANIZED
    }































    internal fun copyNearbySidecars(sourceFile: File, targetFile: File) {
        buildNearbySidecarCopyPlans(
            sourceFile = sourceFile,
            targetFile = targetFile,
            lyricExtensions = lyricExtensions,
            imageExtensions = imageExtensions,
            coverNames = coverNames
        ).forEach { plan ->
            copyIfExists(plan.source, plan.target)
        }
    }





    internal fun DocumentFile.isSupportedAudioDocument(): Boolean {
        val mimeType = type?.lowercase()
        if (mimeType?.startsWith("audio/") == true) {
            return true
        }

        val extension = name
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return extension in audioExtensions
    }

    internal fun QueriedFolderChild.isSupportedAudioDocument(): Boolean {
        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            return true
        }

        val extension = displayName
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() }
            ?: return false
        return extension in audioExtensions
    }
}
