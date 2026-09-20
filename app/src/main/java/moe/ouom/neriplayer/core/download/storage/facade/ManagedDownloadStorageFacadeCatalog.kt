package moe.ouom.neriplayer.core.download.storage.facade

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.storage.operation.content.ensureManagedLibraryManifestForRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.fastIndexRootIdentity
import moe.ouom.neriplayer.core.download.storage.operation.content.fastIndexShardStorage
import moe.ouom.neriplayer.core.download.storage.operation.content.hasManagedDownloadPathHint
import moe.ouom.neriplayer.core.download.storage.operation.content.isDocumentWithinManagedTree
import moe.ouom.neriplayer.core.download.storage.operation.content.isDurableCoreMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.isMediaStoreSongWithinManagedRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.isPathInside
import moe.ouom.neriplayer.core.download.storage.operation.content.missingFastIndexManifestResult
import moe.ouom.neriplayer.core.download.storage.operation.content.normalizeCoverReference
import moe.ouom.neriplayer.core.download.storage.operation.content.parseDownloadedAudioMetadataBatch
import moe.ouom.neriplayer.core.download.storage.operation.content.pendingArtifactLogicalName
import moe.ouom.neriplayer.core.download.storage.operation.content.readManagedLibraryIdForRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.readTextInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.restoreFastIndexPreviewBlocking
import moe.ouom.neriplayer.core.download.storage.operation.content.rootKeyForResolvedRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.writeFastIndexShardBlocking
import moe.ouom.neriplayer.core.download.storage.operation.findAudioEntry
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.composeSnapshot
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.refreshDownloadSidecarSnapshotBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.shouldIndexMetadataLessAudio
import moe.ouom.neriplayer.core.download.storage.operation.listSubdirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootForOperation
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.PendingAudioWriteScanResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.PendingArtifactScanResult
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_INDEX_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadCoverLookup
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.metadata.resolveCreatedAtConfidence
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadStoredReferenceLookup
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexEntryFactory
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardWriteResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryIndexEntry
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.io.IOException
import java.util.Locale

internal fun ManagedDownloadStorage.isLikelyManagedDownloadSongImpl(context: Context, song: SongItem): Boolean {
    if (hasManagedDownloadIdentityHint(song)) {
        return true
    }
    if (peekDownloadedAudio(song) != null) {
        return true
    }
    val configuredRoot = ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
    val directPaths = listOfNotNull(song.localFilePath, song.mediaUri)
        .mapNotNull { reference ->
            when {
                reference.startsWith("/") -> reference
                reference.startsWith("file:", ignoreCase = true) -> {
                    runCatching { reference.toUri().path }.getOrNull()
                }
                else -> null
            }
        }
    if (directPaths.any { directPath ->
            isPathInside(directPath, configuredRoot) ||
                isPathInside(directPath, LEGACY_DOWNLOAD_ROOT_PATH)
        }
    ) {
        return true
    }
    val configuredTree = settings.configuredDirectoryUri
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { it.toUri() }.getOrNull() }
    val songReferences = listOfNotNull(song.mediaUri, song.localFilePath)
        .filter { it.startsWith("content://", ignoreCase = true) }
        .mapNotNull { rawReference -> runCatching { rawReference.toUri() }.getOrNull() }
    val treeDocumentId = configuredTree
        ?.let { tree -> runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() }
    if (
        isMediaStoreSongWithinManagedRoot(
            context = context,
            songReferences = songReferences,
            treeDocumentId = treeDocumentId
        )
    ) {
        return true
    }
    if (songReferences.any { songReference ->
            val documentId = runCatching {
                DocumentsContract.getDocumentId(songReference)
            }.getOrNull()
            documentId != null && isKnownManagedDownloadDocumentId(
                documentId = documentId,
                treeDocumentId = treeDocumentId
            )
        }
    ) {
        return true
    }
    if (configuredTree != null && songReferences.isNotEmpty()) {
        if (!treeDocumentId.isNullOrBlank()) {
            val treeAuthority = configuredTree.authority
            if (songReferences.any { songReference ->
                    songReference.authority == treeAuthority &&
                        isDocumentWithinManagedTree(
                            context = context,
                            songReference = songReference,
                            treeDocumentId = treeDocumentId
                        )
                }
            ) {
                return true
            }
        }
    }
    return false
}

internal fun ManagedDownloadStorage.isLikelyManagedDownloadSongFastImpl(
    context: Context,
    song: SongItem
): Boolean {
    if (!LocalSongSupport.isLocalSong(song, null)) {
        return false
    }
    if (hasManagedDownloadIdentityHint(song) || peekDownloadedAudio(song) != null) {
        return true
    }

    val configuredRoot = settings.configuredDirectoryUri
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { it.toUri() }.getOrNull() }
    val songTree = listOfNotNull(song.mediaUri, song.localFilePath)
        .asSequence()
        .mapNotNull(::managedDownloadTreeUri)
        .firstOrNull()
    if (configuredRoot != null && songTree != null) {
        return areEquivalentDirectoryUris(songTree.toString(), configuredRoot.toString())
    }

    val defaultRoot = runCatching {
        ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
    }.getOrNull()
    val directPaths = listOfNotNull(song.localFilePath, song.mediaUri)
        .mapNotNull { reference ->
            when {
                reference.startsWith("/") -> reference
                reference.startsWith("file:", ignoreCase = true) -> {
                    runCatching { reference.toUri().path }.getOrNull()
                }
                else -> null
            }
        }
    return directPaths.any { path ->
        (defaultRoot != null && isPathInside(path, defaultRoot)) ||
            isPathInside(path, LEGACY_DOWNLOAD_ROOT_PATH)
    }
}

internal fun ManagedDownloadStorage.hasManagedDownloadIdentityHintImpl(song: SongItem): Boolean {
    if (!LocalSongSupport.isLocalSong(song, null)) {
        return false
    }
    val rawChannel = song.channelId?.trim()?.takeIf(String::isNotBlank)
    if (rawChannel?.equals("local", ignoreCase = true) == true) {
        return false
    }
    val sourceChannel = rawChannel
        ?.trim()
        ?.takeIf { !it.equals("local", ignoreCase = true) }
    if (song.remoteSourceIdentityOrNull() != null) {
        return true
    }
    if (
        sourceChannel != null &&
            (
                !song.audioId.isNullOrBlank() ||
                    !song.subAudioId.isNullOrBlank() ||
                    song.id > 0L
                )
    ) {
        return true
    }
    return hasManagedDownloadPathHint(song)
}

internal fun ManagedDownloadStorage.isKnownManagedDownloadDocumentIdImpl(
    documentId: String,
    treeDocumentId: String?
): Boolean {
    val normalizedDocumentId = documentId.trim().takeIf(String::isNotBlank)
        ?: return false
    val normalizedTreeDocumentId = treeDocumentId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    // Provider 文档 ID 是不透明值，其中的斜线和冒号都是数据，不能拿来推断层级
    return normalizedDocumentId == normalizedTreeDocumentId
}

internal fun ManagedDownloadStorage.isManagedDownloadRelativePathImpl(
    relativePath: String?,
    treeDocumentId: String?
): Boolean {
    val normalizedRelativePath = relativePath
        ?.replace('\\', '/')
        ?.trim()
        ?.trim('/')
        ?.takeIf(String::isNotBlank)
        ?: return false
    val managedRoots = buildList {
        treeDocumentId
            ?.substringAfter(':', missingDelimiterValue = treeDocumentId)
            ?.replace('\\', '/')
            ?.trim('/')
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        add(LEGACY_DOWNLOAD_ROOT_RELATIVE_PATH)
    }.distinct()
    return managedRoots.any { managedRoot ->
        normalizedRelativePath == managedRoot ||
            normalizedRelativePath.startsWith("$managedRoot/") ||
            normalizedRelativePath.endsWith("/$managedRoot") ||
            normalizedRelativePath.contains("/$managedRoot/")
    }
}

internal fun ManagedDownloadStorage.normalizeManagedAudioFileNameImpl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (value.startsWith("/")) {
        return File(value).name.takeIf(String::isNotBlank)
    }
    val segment: String = runCatching {
        value.toUri().lastPathSegment
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: value.substringAfterLast('/')
    val decoded = runCatching { Uri.decode(segment) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: runCatching {
            java.net.URLDecoder.decode(segment, Charsets.UTF_8.name())
        }.getOrDefault(segment)
    return decoded
        .substringAfterLast('/')
        .substringAfterLast(':')
        .takeIf(String::isNotBlank)
}

internal fun ManagedDownloadStorage.resolveManagedAudioDisplayNameImpl(
    context: Context,
    song: SongItem
): String? {
    val rawFileName = song.localFileName?.trim()?.takeIf(String::isNotBlank)
    val normalizedHint = normalizeManagedAudioFileName(rawFileName)
        ?: normalizeManagedAudioFileName(song.localFilePath)
        ?: normalizeManagedAudioFileName(song.mediaUri)
    val contentUri = listOfNotNull(song.mediaUri, song.localFilePath)
        .firstOrNull { it.startsWith("content://", ignoreCase = true) }
        ?.let { runCatching { it.toUri() }.getOrNull() }
    val hintLooksUsable = (
        normalizedHint
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.let { it in audioExtensions }
            == true
        )
    if (contentUri == null || (hintLooksUsable && '%' !in rawFileName.orEmpty())) {
        return normalizedHint
    }
    val queriedName = try {
        context.contentResolver.query(
            contentUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf(String::isNotBlank)
            } else {
                null
            }
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    return queriedName
        ?: try {
            DocumentFile.fromSingleUri(context, contentUri)?.name
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        ?: normalizedHint
}

internal suspend fun ManagedDownloadStorage.findCoverReferenceByFileNameImpl(
    context: Context,
    fileName: String?,
    forceRefresh: Boolean = true,
    preferSidecarRefresh: Boolean = false
): String? = withContext(Dispatchers.IO) {
    val normalizedName = fileName
        ?.trim()
        ?.takeIf { name ->
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name
        }
        ?: return@withContext null
    fun findReference(snapshot: DownloadLibrarySnapshot): String? {
        return snapshot.coverEntriesByName[normalizedName]?.reference
            ?: snapshot.coverEntriesByName.values.firstOrNull { entry ->
                entry.name.equals(normalizedName, ignoreCase = true)
            }?.reference
    }

    val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
        context = context,
        restorePersisted = true
    )
    if (shouldUseSidecarRefreshForCoverLookup(
            forceRefresh = forceRefresh,
            preferSidecarRefresh = preferSidecarRefresh,
            hasCachedSnapshot = cachedSnapshot != null
        )
    ) {
        // 播放侧只需刷新歌词和封面目录，不要在大 SAF 目录上重新解析每条音频元数据
        // 这样切歌时不会被整棵目录拖慢
        val snapshotForRefresh = cachedSnapshot ?: return@withContext null
        val refreshedSnapshot = refreshDownloadSidecarSnapshotBlocking(
            context = context,
            snapshot = snapshotForRefresh,
            respectThrottle = true
        )
        return@withContext findReference(refreshedSnapshot)
    }
    val snapshot = if (forceRefresh) {
        buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = true
        )
    } else {
        cachedSnapshot ?: buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = false
        )
    }
    findReference(snapshot)
}

internal suspend fun ManagedDownloadStorage.isManagedCoverReferenceImpl(
    context: Context,
    reference: String?
): Boolean = withContext(Dispatchers.IO) {
    val normalizedReference = normalizeCoverReference(reference) ?: return@withContext false
    val snapshot = buildDownloadLibrarySnapshotBlocking(
        context = context,
        forceRefresh = false
    )
    snapshot.coverEntriesByName.values.any { entry ->
        listOf(entry.reference, entry.mediaUri, entry.localFilePath)
            .any { candidate ->
                normalizeCoverReference(candidate) == normalizedReference
            }
    }
}

internal suspend fun ManagedDownloadStorage.findCoverReferenceByAssetHashImpl(
    context: Context,
    assetHash: String?
): String? = withContext(Dispatchers.IO) {
    val normalizedHash = assetHash
        ?.trim()
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        ?: return@withContext null
    val snapshot = buildDownloadLibrarySnapshotBlocking(
        context = context,
        forceRefresh = true
    )
    snapshot.coverEntriesByName.values.firstOrNull { entry ->
        entry.name.substringBeforeLast('.', entry.name)
            .equals(normalizedHash, ignoreCase = true)
    }?.reference
}

internal suspend fun ManagedDownloadStorage.scanPendingAudioWritesImpl(
    context: Context,
    forceRefresh: Boolean = false,
    directoryUri: String? = null,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): PendingAudioWriteScanResult = withContext(Dispatchers.IO) {
    val root = resolveRootForOperation(
        context = context,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
        unavailableMessage = "pending 音频源目录不可用，暂缓恢复"
    ) ?: return@withContext PendingAudioWriteScanResult(
        entries = emptyList(),
        isComplete = false
    )
    val refresh = treeDirectories.refreshRootEntries(context, root)
    if (!refresh.isComplete && forceRefresh) {
        NPLogger.w(TAG, "pending 音频枚举不完整，跳过恢复: root=${root.javaClass.simpleName}")
        return@withContext PendingAudioWriteScanResult(
            entries = emptyList(),
            isComplete = false
        )
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = forceRefresh,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete && forceRefresh) {
        NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过 pending 恢复")
        return@withContext PendingAudioWriteScanResult(
            entries = emptyList(),
            isComplete = false
        )
    }
    PendingAudioWriteScanResult(
        entries = (refresh.entries + temporary.entries)
        .asSequence()
        .filterNot(StoredEntry::isDirectory)
        .filter(StoredEntry::isPendingAudioWrite)
        .distinctBy(StoredEntry::reference)
        .toList(),
        isComplete = refresh.isComplete && temporary.isComplete
    )
}

internal suspend fun ManagedDownloadStorage.scanPendingDownloadArtifactsImpl(
    context: Context,
    protectedReferences: Set<String> = emptySet(),
    directoryUri: String? = null,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): PendingArtifactScanResult = withContext(Dispatchers.IO) {
    val normalizedProtectedReferences = protectedReferences
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val root = resolveRootForOperation(
        context = context,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
        unavailableMessage = "pending 源目录不可用，无法证明临时文件已收敛"
    ) ?: return@withContext PendingArtifactScanResult(
        count = 0,
        isComplete = false
    )
    val refresh = treeDirectories.refreshRootEntries(context, root)
    if (!refresh.isComplete) {
        return@withContext PendingArtifactScanResult(0, isComplete = false)
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = true,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete) {
        return@withContext PendingArtifactScanResult(0, isComplete = false)
    }
    val pendingEntries = (refresh.entries + temporary.entries)
        .asSequence()
        .filterNot(StoredEntry::isDirectory)
        .filter { entry ->
            entry.isPendingAudioWrite ||
                entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
                entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)
        }
        .toList()
    val migrationPendingArtifacts =
        ManagedDownloadMigrationEntryCollector.classifyPendingArtifacts(
            rootEntries = refresh.entries,
            temporaryEntries = temporary.entries
        )
    val count = pendingEntries.size
    val cachedDurableAudioNames = if (
        directoryUri?.trim()?.isNotBlank() == true ||
        useDefaultRootWhenDirectoryUriMissing
    ) {
        // 显式源目录可能已经不是当前配置根，不能拿目标目录缓存保护同名 pending
        emptySet()
    } else {
        snapshotCacheStore
            .cachedSnapshot(context, restorePersisted = false)
            ?.pendingMetadataByAudioName
            .orEmpty()
            .filterValues(::isDurableCoreMetadata)
            .keys
    }
    val cachedProtectedReferences = pendingEntries
        .asSequence()
        .filter { entry ->
            pendingArtifactLogicalName(entry) in cachedDurableAudioNames
        }
        .mapTo(linkedSetOf(), StoredEntry::reference)
    val allProtectedReferences = normalizedProtectedReferences + cachedProtectedReferences
    val protectedCount = pendingEntries.count { entry ->
        entry.reference in allProtectedReferences
    }
    PendingArtifactScanResult(
        count = count,
        isComplete = true,
        protectedCount = protectedCount,
        migrationBlockingArtifactCount = migrationPendingArtifacts.blockingNames.size,
        migrationMetadataOnlyArtifactCount = migrationPendingArtifacts.metadataOnlyNames.size
    )
}

internal fun ManagedDownloadStorage.isKnownTransientPendingMetadataImpl(
    metadata: DownloadedAudioMetadata
): Boolean {
    if (metadata.downloadFinalized == true) return false
    val state = metadata.artifactState
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?: return false
    return state in KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES
}

internal suspend fun ManagedDownloadStorage.findDownloadedAudioByCandidateBaseNamesImpl(
    context: Context,
    candidateBaseNames: List<String>
): StoredEntry? = withContext(Dispatchers.IO) {
    val normalizedBaseNames = candidateBaseNames
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (normalizedBaseNames.isEmpty()) {
        return@withContext null
    }
    val root = resolveRootBlocking(context)
    val refresh = treeDirectories.refreshRootEntries(context, root)
    if (!refresh.isComplete) {
        NPLogger.w(TAG, "取消清理跳过不完整根目录查询: candidates=${normalizedBaseNames.size}")
        return@withContext null
    }
    findAudioEntry(
        audioEntries = refresh.entries,
        baseNames = normalizedBaseNames
    )
}

internal fun ManagedDownloadStorage.findDownloadedAudioIncludingMetadataLessImpl(
    snapshot: DownloadLibrarySnapshot,
    song: SongItem
): StoredEntry? {
    return findAudioEntry(snapshot, song)
        ?: ManagedDownloadStorageLookup.findAudioEntry(
            audioEntries = snapshot.audioEntriesWithoutMetadata,
            baseNames = candidateManagedDownloadBaseNames(song, settings.fileNameTemplate)
        )
}

internal fun ManagedDownloadStorage.findPendingDownloadedAudioImpl(
    snapshot: DownloadLibrarySnapshot,
    song: SongItem
): StoredEntry? {
    val pendingEntries = snapshot.pendingAudioEntries
    if (pendingEntries.isEmpty()) {
        return null
    }
    val localReferences = listOfNotNull(song.localFilePath, song.mediaUri)
        .filter { reference ->
            reference.startsWith("/") || reference.startsWith("content://", ignoreCase = true)
        }
        .distinct()
    localReferences.firstNotNullOfOrNull { reference ->
        pendingEntries.firstOrNull { entry ->
            entry.reference == reference ||
                entry.mediaUri == reference ||
                entry.localFilePath == reference
        }
    }?.let { return it }

    val stableKeys = setOfNotNull(
        song.stableKey().takeIf(String::isNotBlank),
        song.sourceStableKey?.trim()?.takeIf(String::isNotBlank)
    ).toMutableSet().apply {
        song.remoteDownloadIdentityOrNull()
            ?.stableKey()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
    val metadataMatch = pendingEntries.mapNotNull { entry ->
        val metadata = metadataForAudioEntry(snapshot, entry) ?: return@mapNotNull null
        val matchesIdentity = metadata.stableKey in stableKeys ||
            (song.id > 0L && metadata.songId == song.id) ||
            metadata.mediaUri != null && metadata.mediaUri == song.mediaUri
        if (matchesIdentity) entry to metadata else null
    }.maxWithOrNull(
        compareBy<Pair<StoredEntry, DownloadedAudioMetadata>> {
            it.second.downloadFinalized == true
        }
            .thenBy { it.first.sizeBytes }
            .thenBy { it.first.lastModifiedMs }
    )
    if (metadataMatch != null) {
        return metadataMatch.first
    }
    return ManagedDownloadStorageLookup.findPendingAudioEntry(
        audioEntries = pendingEntries,
        // pending 音频也要遵循当前模板及历史模板, 自定义命名不能漏掉
        baseNames = candidateManagedDownloadBaseNames(song, settings.fileNameTemplate)
    )
}

internal suspend fun ManagedDownloadStorage.queryStoredEntryImpl(context: Context, reference: String?): StoredEntry? = withContext(Dispatchers.IO) {
    val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
    val root = resolveRootBlocking(context)
    val knownEntry = snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)
        ?.takeIf { snapshotCacheStore.currentKey(context) == rootKeyForResolvedRoot(root) }
        ?.audioEntriesByLookupKey?.get(target)
    ManagedDownloadStoredReferenceLookup.query(
        context = context,
        root = root,
        reference = target,
        knownEntry = knownEntry
    )
}

internal suspend fun ManagedDownloadStorage.buildDownloadLibrarySnapshotImpl(
    context: Context,
    forceRefresh: Boolean = false,
    includeMetadataLessAudioForLegacyUpgrade: Boolean = false
): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
    buildDownloadLibrarySnapshotBlocking(
        context = context,
        forceRefresh = forceRefresh,
        includeMetadataLessAudioForLegacyUpgrade =
            includeMetadataLessAudioForLegacyUpgrade
    )
}

internal suspend fun ManagedDownloadStorage.buildLegacyUpgradeSnapshotImpl(
    context: Context
): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
    synchronized(snapshotBuildLock) {
        val root = resolveRootBlocking(context)
        val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = true
        )
        val rootRefresh = treeDirectories.refreshRootEntries(context, root)
        val rootEntries = rootRefresh.entries.filterNot(StoredEntry::isDirectory)
        val pendingAudioEntries = rootEntries.filter(StoredEntry::isPendingAudioWrite)
        val audioEntries = rootEntries.filter { entry ->
            !entry.isPendingAudioWrite && entry.extension in audioExtensions
        }
        val metadataEntriesByAudioName = rootEntries.asSequence()
            .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
            .groupBy { (audioName, _) -> audioName }
            .mapValues { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<Pair<String, StoredEntry>>(
                        {
                            ManagedDownloadTreeNaming.metadataNameOrdinal(
                                it.second.name,
                                audioName
                            ) ?: Int.MAX_VALUE
                        },
                        { it.second.name }
                    )
                )!!.second
            }
        val reusableCachedMetadata = selectReusableCachedDownloadedMetadata(
            currentEntries = metadataEntriesByAudioName,
            cachedSnapshot = cachedSnapshot
        )
        val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
        composeSnapshot(
            audioEntries = audioEntries,
            metadataEntries = metadataEntriesByAudioName.values.toList(),
            metadataByAudioName = reusableCachedMetadata,
            coverEntries = coverEntries,
            lyricEntries = emptyList(),
            rootEntriesComplete = rootRefresh.isComplete,
            pendingAudioEntries = pendingAudioEntries
        )
    }
}

internal suspend fun ManagedDownloadStorage.upsertCompleteFastIndexEntryImpl(
    context: Context,
    entry: ManagedLibraryIndexEntry
): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val rootIdentity = fastIndexRootIdentity(root)
    val libraryId = ensureManagedLibraryManifestForRoot(
        context = appContext,
        root = root,
        rootIdentity = rootIdentity
    )
    fastIndexMutationCoordinator.mutate(rootIdentity) {
        fastIndexMutator.upsertCompleteEntry(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            entry = entry,
            storage = fastIndexShardStorage(appContext, root, rootIdentity)
        )
    }
}

internal suspend fun ManagedDownloadStorage.upsertCompleteFastIndexEntryImpl(
    context: Context,
    song: SongItem,
    audio: StoredEntry,
    state: String,
    coverPath: String?
): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val rootIdentity = fastIndexRootIdentity(root)
    val libraryId = ensureManagedLibraryManifestForRoot(
        context = appContext,
        root = root,
        rootIdentity = rootIdentity
    )
    fastIndexMutationCoordinator.mutate(rootIdentity) {
        fastIndexMutator.upsertCompleteEntry(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            entry = ManagedLibraryFastIndexEntryFactory.fromCompletedDownload(
                libraryId = libraryId,
                song = song,
                audio = audio,
                state = state,
                coverPath = coverPath
            ),
            storage = fastIndexShardStorage(appContext, root, rootIdentity)
        )
    }
}

internal suspend fun ManagedDownloadStorage.updateExistingFastIndexEntryImpl(
    context: Context,
    stableKey: String,
    transform: (ManagedLibraryIndexEntry) -> ManagedLibraryIndexEntry
): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val rootIdentity = fastIndexRootIdentity(root)
    val libraryId = readManagedLibraryIdForRoot(appContext, root, rootIdentity)
        ?: return@withContext missingFastIndexManifestResult(stableKey)
    fastIndexMutationCoordinator.mutate(rootIdentity) {
        fastIndexMutator.updateExistingEntry(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            stableKey = stableKey,
            storage = fastIndexShardStorage(appContext, root, rootIdentity),
            transform = transform
        )
    }
}

internal suspend fun ManagedDownloadStorage.removeFastIndexEntriesImpl(
    context: Context,
    stableKeys: Collection<String>
): List<ManagedLibraryFastIndexMutationResult> = withContext(Dispatchers.IO) {
    val normalizedKeys = stableKeys
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    if (normalizedKeys.isEmpty()) return@withContext emptyList()
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val rootIdentity = fastIndexRootIdentity(root)
    val libraryId = readManagedLibraryIdForRoot(appContext, root, rootIdentity)
        ?: return@withContext listOf(
            ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = IOException("managed library manifest is unavailable")
            )
        )
    fastIndexMutationCoordinator.mutate(rootIdentity) {
        fastIndexMutator.removeEntries(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            stableKeys = normalizedKeys,
            storage = fastIndexShardStorage(appContext, root, rootIdentity)
        )
    }
}

internal suspend fun ManagedDownloadStorage.clearFastIndexForConfirmedEmptyLibraryImpl(
    context: Context
): Boolean = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val rebuildToken = runCatching {
        captureFastIndexRebuildToken(appContext)
    }.getOrElse { error ->
        NPLogger.w(TAG, "全库删除读取 fast index 根失败: ${error.message}", error)
        return@withContext false
    }
    runCatching {
        persistFastIndex(
            context = appContext,
            snapshot = emptyDownloadLibrarySnapshot(),
            rebuildToken = rebuildToken
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "全库删除清空 fast index 失败: ${error.message}", error)
    }.getOrDefault(false)
}

internal suspend fun ManagedDownloadStorage.persistFastIndexImpl(
    context: Context,
    snapshot: DownloadLibrarySnapshot,
    rebuildToken: ManagedLibraryFastIndexRebuildToken
) = withContext(Dispatchers.IO) {
    if (!shouldPersistFastIndex(snapshot)) return@withContext false
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val rootIdentity = fastIndexRootIdentity(root)
    val libraryId = ensureManagedLibraryManifestForRoot(
        context = appContext,
        root = root,
        rootIdentity = rootIdentity
    )
    val entries = snapshot.audioEntries.mapNotNull { audio ->
        val metadata = metadataForAudioEntry(snapshot, audio)
        if (
            !isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = snapshot.rootEntriesComplete,
                isPendingAudioWrite = audio.isPendingAudioWrite,
                metadata = metadata
            )
        ) {
            return@mapNotNull null
        }
        val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val logicalCreatedAtMs = metadata.createdAtMs
            ?: metadata.downloadTimeMs
            ?: audio.lastModifiedMs.takeIf { it > 0L }
        val createdAtSource = metadata.createdAtSource
            ?: when {
                metadata.createdAtMs != null -> "MANAGED_COMMIT"
                metadata.downloadTimeMs != null -> "DOWNLOAD_TIME"
                audio.lastModifiedMs > 0L -> "MTIME"
                else -> null
            }
        ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = metadata.artifactId ?: "managed:$libraryId:$stableKey",
            audioName = audio.name,
            audioReference = audio.reference,
            metadataName = snapshot.metadataEntriesByAudioName[audio.name]?.name,
            state = metadata.artifactState ?: "FINALIZED",
            metadataEmbeddingState = metadata.metadataEmbeddingState,
            downloadTimeMs = metadata.downloadTimeMs,
            updatedAtMs = metadata.sourceModifiedAtMs
                ?: metadata.createdAtMs
                ?: audio.lastModifiedMs,
            songId = metadata.songId,
            title = metadata.name,
            artist = metadata.artist,
            album = metadata.album ?: metadata.identityAlbum,
            mediaUri = metadata.mediaUri,
            channelId = metadata.channelId,
            audioId = metadata.audioId,
            subAudioId = metadata.subAudioId,
            playlistContextId = metadata.playlistContextId,
            durationMs = metadata.durationMs.takeIf { it > 0L },
            coverPath = ManagedDownloadCoverLookup.findCoverReference(snapshot, audio),
            logicalCreatedAtMs = logicalCreatedAtMs,
            createdAtSource = createdAtSource,
            createdAtConfidence = metadata.createdAtConfidence
                ?: createdAtSource?.let(::resolveCreatedAtConfidence)
        )
    }
    val entriesByShard = entries.groupBy { entry -> ManagedLibraryFastIndex.shardFor(entry.stableKey) }
    val rebuildResult = fastIndexMutationCoordinator.rebuild(
        token = rebuildToken,
        currentRootIdentity = rootIdentity
    ) {
        val existingShards = listSubdirectoryEntries(
            context = appContext,
            root = root,
            subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME
        ).asSequence()
            .filter { entry ->
                entry.name.startsWith("shard-") && entry.name.endsWith(".json")
            }
            .mapNotNull { entry ->
                readTextInternal(appContext, entry.reference)
                    ?.let(ManagedLibraryFastIndex::decode)
            }
            .filter { shard -> shard.libraryId == libraryId }
            .associate { shard -> shard.shard to shard.entries }
        val allShards = (existingShards.keys + entriesByShard.keys).associateWith { shard ->
            entriesByShard[shard].orEmpty()
        }
        val changedShards = ManagedLibraryFastIndex.changedShards(existingShards, allShards)
        val nowMs = System.currentTimeMillis()
        changedShards.forEach { shard ->
            val payload = ManagedLibraryFastIndex.encode(
                libraryId = libraryId,
                shard = shard,
                entries = allShards[shard].orEmpty(),
                generatedAtMs = nowMs
            )
            when (
                val writeResult = writeFastIndexShardBlocking(
                    context = appContext,
                    root = root,
                    shard = shard,
                    payload = payload
                )
            ) {
                ManagedLibraryFastIndexShardWriteResult.Written -> Unit
                is ManagedLibraryFastIndexShardWriteResult.Unavailable -> {
                    throw IOException(
                        "fast index shard write failed: $shard",
                        writeResult.error
                    )
                }
            }
        }
    }
    when (rebuildResult) {
        is ManagedLibraryFastIndexRebuildResult.Applied -> true
        ManagedLibraryFastIndexRebuildResult.Stale -> false
    }
}

internal suspend fun ManagedDownloadStorage.restoreFastIndexPreviewImpl(
    context: Context
): DownloadLibrarySnapshot? = withContext(Dispatchers.IO) {
    if (!restoreFastIndexPreviewBlocking(context)) {
        return@withContext null
    }
    snapshotCacheStore.cachedSnapshot(
        context = context,
        restorePersisted = false
    )
}

internal suspend fun ManagedDownloadStorage.refreshDownloadSidecarSnapshotImpl(
    context: Context,
    snapshot: DownloadLibrarySnapshot,
    forceRefresh: Boolean = false
): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
    refreshDownloadSidecarSnapshotBlocking(
        context = context,
        snapshot = snapshot,
        respectThrottle = !forceRefresh
    )
}

internal fun ManagedDownloadStorage.buildDownloadLibrarySnapshotBlockingImpl(
    context: Context,
    forceRefresh: Boolean = false,
    includeMetadataLessAudioForLegacyUpgrade: Boolean = false
): DownloadLibrarySnapshot {
    if (!forceRefresh && !includeMetadataLessAudioForLegacyUpgrade) {
        // 增量快照可独立读取，不能让已提交歌曲排在无关的全目录扫描之后
        // 仍核查当前根目录权限，缓存不用于绕过授权或换根
        resolveRootBlocking(context)
        snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)?.let { return it }
    }
    return rebuildDownloadLibrarySnapshotBlocking(
        context, forceRefresh, includeMetadataLessAudioForLegacyUpgrade
    )
}

private fun ManagedDownloadStorage.rebuildDownloadLibrarySnapshotBlocking(
    context: Context,
    forceRefresh: Boolean,
    includeMetadataLessAudioForLegacyUpgrade: Boolean
): DownloadLibrarySnapshot = synchronized(snapshotBuildLock) {
    // 先确认配置目录仍可写, 避免权限失效时恢复旧索引并误认为目录正常
    val root = resolveRootBlocking(context)
    val cacheKey = rootKeyForResolvedRoot(root)
    val captured = snapshotCacheStore.captureSnapshot(
        context = context,
        restorePersisted = true
    )
    val cachedSnapshot = captured.snapshot
    if (!forceRefresh && !includeMetadataLessAudioForLegacyUpgrade) {
        cachedSnapshot?.let { return@synchronized it }
    }

    val libraryRefresh = treeDirectories.refreshDownloadLibraryEntries(context, root)
    val rootEntries = libraryRefresh.rootEntries.filterNot(StoredEntry::isDirectory)
    val temporaryEntries = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = forceRefresh,
        rootAlreadyRefreshed = true
    )
    val pendingAudioEntries = (rootEntries + temporaryEntries.entries)
        .filter(StoredEntry::isPendingAudioWrite)
        .distinctBy(StoredEntry::reference)
    val pendingAudioLogicalNames = pendingAudioEntries
        .mapTo(hashSetOf()) { entry ->
            ManagedDownloadTreeNaming.canonicalLookupName(entry.logicalName)
        }
    val audioEntries = rootEntries.filter {
        !it.isPendingAudioWrite && it.extension in audioExtensions
    }
    val metadataEntries = (rootEntries + temporaryEntries.entries.filter { entry ->
        entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)
    }).filter { entry ->
        if (!ManagedDownloadTreeNaming.isMetadataName(entry.name)) {
            return@filter false
        }
        val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            ?: return@filter false
        !ManagedDownloadTreeNaming.isPendingMetadataName(
            actualName = entry.name,
            audioName = audioName
        ) || ManagedDownloadTreeNaming.canonicalLookupName(audioName) in
            pendingAudioLogicalNames
    }
    val metadataEntriesByAudioName = metadataEntries
        .mapNotNull { entry ->
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                audioName to entry
            }
        }
        .groupBy { it.first }
        .mapValues { (audioName, entries) ->
            entries.minWithOrNull(
                compareBy<Pair<String, StoredEntry>>(
                    { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                    { it.second.name }
                )
            )!!.second
    }
    var reusedMetadataCount = 0
    val metadataEntriesToParse = mutableListOf<Pair<String, StoredEntry>>()
    val metadataByAudioName = linkedMapOf<String, DownloadedAudioMetadata>()
    metadataEntriesByAudioName.forEach { (audioName, entry) ->
        val cachedEntry = cachedSnapshot?.metadataEntriesByAudioName?.get(audioName)
        val cachedMetadata = cachedSnapshot?.metadataByAudioName?.get(audioName)
        if (
            canReuseCachedDownloadedMetadata(
                cachedEntry = cachedEntry,
                currentEntry = entry,
                cachedMetadata = cachedMetadata
            )
        ) {
            reusedMetadataCount++
            metadataByAudioName[audioName] = requireNotNull(cachedMetadata)
        } else {
            metadataEntriesToParse += audioName to entry
        }
    }
    parseDownloadedAudioMetadataBatch(
        context = context,
        entries = metadataEntriesToParse
    ).forEach { (audioName, metadata) ->
        if (metadata != null) {
            metadataByAudioName[audioName] = metadata
        }
    }
    val pendingMetadataEntriesByAudioName = metadataEntries
        .mapNotNull { entry ->
            if (!entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)) {
                return@mapNotNull null
            }
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                audioName to entry
            }
        }
        .groupBy { it.first }
        .mapValues { (audioName, entries) ->
            entries.minWithOrNull(
                compareBy<Pair<String, StoredEntry>>(
                    { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                    { it.second.name }
                )
            )!!.second
        }
    val pendingMetadataEntriesToParse = pendingMetadataEntriesByAudioName
        .mapNotNull { (audioName, entry) ->
            if (metadataEntriesByAudioName[audioName] == entry) {
                return@mapNotNull null
            }
            audioName to entry
        }
    val pendingMetadataByAudioName = buildMap {
        pendingMetadataEntriesByAudioName.forEach { (audioName, entry) ->
            val metadata = if (metadataEntriesByAudioName[audioName] == entry) {
                metadataByAudioName[audioName]
            } else {
                null
            }
            if (metadata != null) {
                put(audioName, metadata)
            }
        }
        parseDownloadedAudioMetadataBatch(
            context = context,
            entries = pendingMetadataEntriesToParse
        ).forEach { (audioName, metadata) ->
            if (metadata != null) {
                put(audioName, metadata)
            }
        }
    }
    if (forceRefresh && reusedMetadataCount > 0) {
        NPLogger.d(
            TAG,
            "刷新下载目录复用未变化 metadata: reused=$reusedMetadataCount, total=${metadataEntries.size}"
        )
    }
    val coverEntries = if (libraryRefresh.sidecarEntriesComplete) {
        libraryRefresh.coverEntries
    } else {
        cachedSnapshot?.coverEntriesByName?.values?.toList()
            ?: libraryRefresh.coverEntries
    }
    val lyricEntries = if (libraryRefresh.sidecarEntriesComplete) {
        libraryRefresh.lyricEntries
    } else {
        cachedSnapshot?.lyricEntriesByName?.values?.toList()
            ?: libraryRefresh.lyricEntries
    }
    val coverEntriesByName = coverEntries.associateBy(StoredEntry::name)
    val lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name)
    val allowMetadataLessAudio = includeMetadataLessAudioForLegacyUpgrade ||
        shouldIndexMetadataLessAudio()
    val managedAudioNameIndex = ManagedDownloadManagedAudioPolicy.buildNameIndex(
            metadataAudioNames = metadataEntriesByAudioName.keys,
            coverEntryNames = coverEntriesByName.keys,
            lyricEntryNames = lyricEntriesByName.keys,
            allowMetadataLessAudio = allowMetadataLessAudio
    )
    val managedAudioEntries = audioEntries.filter { entry ->
        ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
            audioName = entry.name,
            nameIndex = managedAudioNameIndex
        )
    }
    val canonicalAudioEntries = ManagedDownloadStorageLookup.selectCanonicalAudioEntries(
        audioEntries = managedAudioEntries,
        metadataByAudioName = metadataByAudioName
    )
    val skippedForeignAudioCount = audioEntries.size - managedAudioEntries.size
    if (skippedForeignAudioCount > 0) {
        NPLogger.d(
            TAG,
            "跳过非托管音频扫描: total=${audioEntries.size}, managed=${managedAudioEntries.size}, skipped=$skippedForeignAudioCount"
        )
    }
    val snapshot = composeSnapshot(
        audioEntries = canonicalAudioEntries,
        metadataEntries = metadataEntriesByAudioName.values.toList(),
        metadataByAudioName = metadataByAudioName,
        coverEntries = coverEntries,
        lyricEntries = lyricEntries,
        rootEntriesComplete = libraryRefresh.rootEntriesComplete && temporaryEntries.isComplete,
        sidecarEntriesComplete = libraryRefresh.sidecarEntriesComplete,
        pendingAudioEntries = pendingAudioEntries,
        pendingMetadataByAudioName = pendingMetadataByAudioName
    )
    if (!includeMetadataLessAudioForLegacyUpgrade) {
        val publication = snapshotCacheStore.publishSnapshotIfUnchanged(
            context, cacheKey, snapshot, expectedRevision = captured.revision
        )
        // 并发提交或删除优先于本轮扫描，不能把未发布的旧目录视图交给调用方
        return@synchronized publication.snapshot
    }
    return@synchronized snapshot
}

internal fun ManagedDownloadStorage.canReuseCachedDownloadedMetadataImpl(
    cachedEntry: StoredEntry?,
    currentEntry: StoredEntry,
    cachedMetadata: DownloadedAudioMetadata?
): Boolean {
    return cachedMetadata != null &&
        cachedEntry != null &&
        cachedEntry.reference == currentEntry.reference &&
        cachedEntry.sizeBytes == currentEntry.sizeBytes &&
        cachedEntry.lastModifiedMs > 0L &&
        cachedEntry.lastModifiedMs == currentEntry.lastModifiedMs
}

internal fun ManagedDownloadStorage.selectReusableCachedDownloadedMetadataImpl(
    currentEntries: Map<String, StoredEntry>,
    cachedSnapshot: DownloadLibrarySnapshot?
): Map<String, DownloadedAudioMetadata> {
    if (cachedSnapshot == null) return emptyMap()
    return currentEntries.mapNotNull { (audioName, currentEntry) ->
        val cachedEntry = cachedSnapshot.metadataEntriesByAudioName[audioName]
        val cachedMetadata = cachedSnapshot.metadataByAudioName[audioName]
        if (
            canReuseCachedDownloadedMetadata(
                cachedEntry = cachedEntry,
                currentEntry = currentEntry,
                cachedMetadata = cachedMetadata
            )
        ) {
            audioName to checkNotNull(cachedMetadata)
        } else {
            null
        }
    }.toMap()
}

internal fun ManagedDownloadStorage.shouldSkipRedundantForcedSidecarRefreshImpl(
    requestedSnapshot: DownloadLibrarySnapshot,
    activeSnapshot: DownloadLibrarySnapshot?,
    respectThrottle: Boolean
): Boolean {
    return !respectThrottle &&
        activeSnapshot === requestedSnapshot &&
        requestedSnapshot.sidecarEntriesComplete
}

internal fun ManagedDownloadStorage.emptyDownloadLibrarySnapshotImpl(): DownloadLibrarySnapshot {
    return composeSnapshot(
        audioEntries = emptyList(),
        metadataEntries = emptyList(),
        metadataByAudioName = emptyMap(),
        coverEntries = emptyList(),
        lyricEntries = emptyList()
    )
}
