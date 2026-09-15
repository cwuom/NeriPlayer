package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.TreeChildNameRefresh
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedLyricsBundle
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.LyricKind
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.RestoredMigrationManifest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.ValidatedMigrationCopyReceipts
import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeleteGuard
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetIndexBuilder
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationSourceEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.isCurrentMigrationSourceFingerprint
import moe.ouom.neriplayer.core.download.storage.migration.isSafeMigrationPlanName
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.storage.sidecar.ManagedDownloadLyricStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageMutationLocks
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.readPreservingBlockFailure
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import java.io.File
import java.io.IOException
import java.io.InputStream
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal suspend fun ManagedDownloadStorage.snapshotRootKeyForOperationImpl(
    context: Context,
    directoryUri: String? = null,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): String? = withContext(Dispatchers.IO) {
    val root = resolveRootForOperation(
        context = context.applicationContext,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
        unavailableMessage = "读取操作源目录身份失败, 保留恢复凭据"
    ) ?: return@withContext null
    rootKeyForResolvedRoot(root)
}

internal suspend fun ManagedDownloadStorage.hasReadableContentImpl(
    context: Context,
    entry: StoredEntry
): Boolean = withContext(Dispatchers.IO) {
    if (entry.isDirectory) {
        return@withContext false
    }
    if (entry.sizeBytes > 0L) {
        return@withContext inspectStorageReference(
            context,
            entry.reference
        ) == ManagedDownloadReferenceIo.AccessResult.Accessible
    }
    try {
        when (
            val result = readStoredEntryForMigration(context, entry) { input ->
                input.read() != -1
            }
        ) {
            is StorageLookupResult.Found -> result.value.getOrThrow()
            else -> false
        }
    } catch (error: java.util.concurrent.CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal suspend fun ManagedDownloadStorage.deleteReferencesImpl(
    context: Context,
    references: Collection<String?>,
    onDeleteAttemptFinished: (String, Boolean) -> Unit = { _, _ -> }
): Set<String> =
    withContext(Dispatchers.IO) {
        batchReferenceDeleteMutex.withLock {
            val deletePolicy = buildManagedDeletePolicy(context)
            deleteReferencesInternalConcurrently(
                context = context,
                references = resolveTrustedManagedReferences(references, deletePolicy),
                deletePolicy = deletePolicy,
                invalidateSnapshot = true,
                onDeleteAttemptFinished = { reference, deleted ->
                    onDeleteAttemptFinished(reference.externalReference, deleted)
                }
            )
        }
    }

internal suspend fun ManagedDownloadStorage.saveAudioFromTempImpl(
    context: Context,
    tempFile: File,
    fileName: String,
    mimeType: String?,
    expectedSizeBytes: Long? = null,
    transferSizeVerified: Boolean = false,
    seedMetadataJson: String? = null,
    pendingMetadataJson: String? = null
): StoredEntry = withContext(Dispatchers.IO) {
    saveAudioFromTempBlocking(
        context = context,
        tempFile = tempFile,
        fileName = fileName,
        mimeType = mimeType,
        expectedSizeBytes = expectedSizeBytes,
        transferSizeVerified = transferSizeVerified,
        seedMetadataJson = seedMetadataJson,
        pendingMetadataJson = pendingMetadataJson
    )
}

internal suspend fun ManagedDownloadStorage.promotePendingFileAudioImpl(
    root: File,
    pendingName: String,
    finalName: String,
    pendingRoot: File = root
): File? {
    val target = File(root, finalName)
    return FileStorageMutationLocks.withTargetLock(target) {
        val pending = File(pendingRoot, pendingName)
            .takeIf { it.isFile }
            ?: return@withTargetLock null
        when {
            target.isFile && target.length() == pending.length() -> {
                deletePendingFileAndConfirm(pending)?.let { cleanupError ->
                    throw IOException(
                        "下载目标已存在但重复 pending 清理未确认: $finalName",
                        cleanupError
                    )
                }
            }
            target.isFile -> throw IOException(
                "下载目标已存在且大小不一致，保留 pending 文件: $finalName"
            )
            target.exists() -> throw IOException(
                "下载目标不是普通文件，保留 pending 文件: $finalName"
            )
            else -> promoteFileTargetWithoutReplacement(pending, target, finalName)
        }
        target.takeIf { it.isFile && it.length() > 0L }
    }
}

internal suspend fun ManagedDownloadStorage.demotePublishedFileAudioImpl(
    root: File,
    publishedName: String,
    pendingName: String,
    pendingRoot: File = root
): File? {
    val published = File(root, publishedName)
    return FileStorageMutationLocks.withTargetLock(published) {
        val source = published.takeIf { it.isFile && it.length() > 0L }
            ?: return@withTargetLock null
        val sourceLength = source.length()
        val pending = File(pendingRoot, pendingName)
        if (pending.exists() || pending == source) {
            return@withTargetLock null
        }
        promoteFileTargetWithoutReplacement(
            pending = source,
            target = pending,
            displayName = pendingName
        )
        pending.takeIf { it.isFile && it.length() == sourceLength }
    }
}

internal fun ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacingImpl(
    enumerationComplete: Boolean,
    existingNames: Collection<String>,
    targetName: String
): Boolean {
    if (!enumerationComplete) return false
    return existingNames.none { actualName ->
        ManagedDownloadTreeNaming.isExactTreeStoredName(actualName, targetName) ||
            isTreePromotionBackupName(actualName, targetName)
    }
}

internal fun ManagedDownloadStorage.commitCoverBytesImpl(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mimeType: String?
): StoredEntry? {
    if (bytes.isEmpty()) {
        return null
    }
    return writeSubdirectoryBytesBlocking(
        context = context,
        subdirectory = COVER_SUBDIRECTORY,
        displayName = fileName,
        bytes = bytes,
        mimeType = mimeTypeFromName(fileName, mimeType)
    )
}

internal suspend fun ManagedDownloadStorage.persistRemoteCoverStreamImpl(
    context: Context,
    input: InputStream,
    fileName: String,
    mimeType: String?,
    expectedSizeBytes: Long? = null
): String? = withContext(Dispatchers.IO) {
    writeSubdirectoryStreamBlocking(
        context = context,
        subdirectory = COVER_SUBDIRECTORY,
        displayName = fileName,
        input = input,
        mimeType = mimeTypeFromName(fileName, mimeType),
        expectedSizeBytes = expectedSizeBytes
    )?.reference
}

internal fun ManagedDownloadStorage.findLyricLocationImpl(
    context: Context,
    songId: Long,
    candidateBaseNames: List<String>,
    translated: Boolean
): String? {
    val snapshot = resolveSnapshotForIndexedLookup(context)
        ?: buildDownloadLibrarySnapshotBlocking(context)
    return ManagedDownloadLyricStore.findLyricLocation(
        snapshot = snapshot,
        songId = songId,
        candidateBaseNames = candidateBaseNames,
        translated = translated
    )
}

internal fun ManagedDownloadStorage.writeLyricsImpl(
    context: Context,
    songId: Long,
    baseName: String,
    content: String,
    translated: Boolean
): String? {
    val fileNameByName = ManagedDownloadLyricStore.lyricFileName(baseName, translated)
    NPLogger.d(TAG, "写入歌词文件: fileName=$fileNameByName, translated=$translated, songId=$songId")
    return overwriteLyric(context, fileNameByName, content)
}

internal fun ManagedDownloadStorage.writeRomanizedLyricsImpl(
    context: Context,
    songId: Long,
    baseName: String,
    content: String
): String? {
    val fileName = ManagedDownloadLyricStore.romanizedLyricFileName(baseName)
    NPLogger.d(TAG, "写入音译歌词文件: fileName=$fileName, songId=$songId")
    return overwriteLyric(context, fileName, content)
}

internal fun ManagedDownloadStorage.readLyricsBundleImpl(context: Context, song: SongItem): DownloadedLyricsBundle {
    val fastLyrics = readLyricsBundleFastInternal(
        context = context,
        song = song,
        allowColdSafProbe = false
    )
    if (!hasManagedLocalReference(song)) {
        return fastLyrics
    }
    val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
        context = context,
        restorePersisted = false
    )
    val cachedAudio = cachedSnapshot?.let { snapshot -> findAudioEntry(snapshot, song) }
    if (cachedAudio != null) {
        if (hasCompleteLyricsSidecars(fastLyrics)) {
            return fastLyrics
        }

        // 音频索引可以比 Lyrics 目录先完成, 首轮读取必须补一次轻量侧载刷新
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "playback lyric sidecar refresh: song=${song.name}, " +
                "original=${fastLyrics.hasOriginalSidecar}, " +
                "translated=${fastLyrics.hasTranslatedSidecar}, " +
                "romanized=${fastLyrics.hasRomanizedSidecar}"
        )
        val refreshedSnapshot = refreshDownloadSidecarSnapshotBlocking(
            context = context,
            snapshot = cachedSnapshot,
            respectThrottle = true,
            refreshCovers = false
        )
        val refreshedLyrics = resolveDownloadedLyricsBundle(
            context = context,
            song = song,
            snapshot = refreshedSnapshot,
            readText = { reference -> readTextInternal(context, reference) },
            exists = { lookupContext, reference ->
                inspectStorageReference(lookupContext, reference) ==
                    ManagedDownloadReferenceIo.AccessResult.Accessible
            }
        )
        val refreshedDirectLyrics = if (hasCompleteLyricsSidecars(refreshedLyrics)) {
            refreshedLyrics
        } else {
            // 索引刷新可能被 provider 节流或并发扫描保守地保留旧值，当前歌曲仍需直读
            readLyricsBundleFromManagedRootFast(
                context = context,
                song = song
            )
        }
        return mergeLyricsBundles(
            preferred = refreshedDirectLyrics,
            fallback = mergeLyricsBundles(
                preferred = refreshedLyrics,
                fallback = fastLyrics
            )
        )
    }

    NPLogger.d(
        "ManagedDownloadLyricsPerf",
        "playback lyric backfill waits indexed snapshot: cached=${cachedSnapshot != null}, " +
            "song=${song.name}"
    )
    buildDownloadLibrarySnapshotBlocking(
        context = context,
        forceRefresh = cachedSnapshot != null
    )
    val completedLyrics = readLyricsBundleFastInternal(
        context = context,
        song = song,
        allowColdSafProbe = false
    )
    if (hasCompleteLyricsSidecars(completedLyrics)) {
        return completedLyrics
    }
    val directLyrics = readLyricsBundleFromManagedRootFast(
        context = context,
        song = song
    )
    val mergedLyrics = mergeLyricsBundles(
        preferred = directLyrics,
        fallback = completedLyrics
    )
    NPLogger.d(
        "ManagedDownloadLyricsPerf",
        "playback lyric backfill completed: original=${mergedLyrics.hasOriginalSidecar}, " +
            "translated=${mergedLyrics.hasTranslatedSidecar}, " +
            "romanized=${mergedLyrics.hasRomanizedSidecar}, song=${song.name}"
    )
    return mergedLyrics
}

internal fun ManagedDownloadStorage.readLyricsBundleFastImpl(
    context: Context,
    song: SongItem,
    allowColdSafProbe: Boolean = true
): DownloadedLyricsBundle {
    return readLyricsBundleFastInternal(
        context = context,
        song = song,
        allowColdSafProbe = allowColdSafProbe
    )
}

internal fun ManagedDownloadStorage.managedDownloadTreeReferenceImpl(rawReference: String?): String? {
    val rawUri = rawReference?.trim()?.takeIf(String::isNotBlank) ?: return null
    val schemeEnd = rawUri.indexOf("://")
    if (schemeEnd <= 0 || !rawUri.regionMatches(0, "content", 0, schemeEnd, true)) {
        return null
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = rawUri.indexOf('/', authorityStart)
        .takeIf { it >= 0 }
        ?: rawUri.length
    if (authorityEnd <= authorityStart) {
        return null
    }
    val treeMarkerStart = rawUri.indexOf("/tree/", authorityStart, ignoreCase = true)
    if (treeMarkerStart >= 0) {
        val documentMarkerStart = rawUri.indexOf(
            "/document/",
            treeMarkerStart,
            ignoreCase = true
        )
        val treeEnd = if (documentMarkerStart >= 0) {
            documentMarkerStart
        } else {
            rawUri.length
        }
        val treeReference = rawUri.substring(0, treeEnd).trimEnd('/')
        val treeDocumentId = treeReference
            .substring(treeMarkerStart + "/tree/".length)
            .takeIf(String::isNotBlank)
            ?: return null
        val authority = rawUri.substring(authorityStart, authorityEnd)
            .takeIf(String::isNotBlank)
            ?: return null
        return "content://$authority/tree/$treeDocumentId"
    }

    val sourceUri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
    val treeSegmentIndex = sourceUri.pathSegments.indexOfFirst { segment ->
        segment.equals("tree", ignoreCase = true)
    }
    val treeDocumentId = sourceUri.pathSegments
        .getOrNull(treeSegmentIndex.takeIf { it >= 0 }?.plus(1) ?: -1)
        ?.takeIf(String::isNotBlank)
        ?: runCatching { DocumentsContract.getTreeDocumentId(sourceUri) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        ?: return null
    return "${sourceUri.scheme}://${sourceUri.authority}/tree/$treeDocumentId"
}

internal fun ManagedDownloadStorage.resolveLyricsBundleFromReferencesImpl(
    metadata: DownloadedAudioMetadata?,
    originalReference: String?,
    translatedReference: String?,
    romanizedReference: String?,
    readText: (String) -> String?
): DownloadedLyricsBundle {
    fun read(reference: String?): Pair<String?, Boolean> {
        val normalized = reference?.takeIf(String::isNotBlank)
            ?: return null to false
        val content = try {
            readText(normalized)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return content to (content != null)
    }

    val original = read(originalReference)
    val translated = read(translatedReference)
    val romanized = read(romanizedReference)
    return DownloadedLyricsBundle(
        lyric = original.first ?: metadata?.matchedLyric ?: metadata?.originalLyric,
        translatedLyric = translated.first
            ?: metadata?.matchedTranslatedLyric
            ?: metadata?.originalTranslatedLyric,
        romanizedLyric = romanized.first
            ?: metadata?.matchedRomanizedLyric
            ?: metadata?.originalRomanizedLyric,
        hasOriginalSidecar = original.second,
        hasTranslatedSidecar = translated.second,
        hasRomanizedSidecar = romanized.second
    )
}

internal fun ManagedDownloadStorage.resolveLyricsBundleFromEntriesImpl(
    song: SongItem,
    candidateBaseNames: List<String> = buildManagedLyricBaseNames(song),
    lyricEntries: Collection<StoredEntry>,
    readText: (String) -> String?
): DownloadedLyricsBundle {
    val entriesByName = lyricEntries
        .asSequence()
        .filterNot(StoredEntry::isDirectory)
        .associateBy { it.name.lowercase() }

    fun read(kind: LyricKind): Pair<String?, Boolean> {
        val names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
            songId = song.id.takeIf { it > 0L },
            candidateBaseNames = candidateBaseNames,
            kind = when (kind) {
                LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
            }
        )
        names.firstNotNullOfOrNull { name ->
            val entry = entriesByName[name.lowercase()] ?: return@firstNotNullOfOrNull null
            readText(entry.reference)?.let { content -> content to true }
        }?.let { return it }
        return null to false
    }

    val original = read(LyricKind.ORIGINAL)
    val translated = read(LyricKind.TRANSLATED)
    val romanized = read(LyricKind.ROMANIZED)
    return DownloadedLyricsBundle(
        lyric = original.first,
        translatedLyric = translated.first,
        romanizedLyric = romanized.first,
        hasOriginalSidecar = original.second,
        hasTranslatedSidecar = translated.second,
        hasRomanizedSidecar = romanized.second
    )
}

internal fun ManagedDownloadStorage.resolveDownloadedLyricsBundleImpl(
    context: Context,
    song: SongItem,
    snapshot: DownloadLibrarySnapshot,
    readText: (String) -> String?,
    exists: (Context, String?) -> Boolean
): DownloadedLyricsBundle {
    val resolvedAudio = findAudioEntry(snapshot, song)
    val resolvedMetadata = resolvedAudio?.let { metadataForAudioEntry(snapshot, it) }

    fun readLyric(translated: Boolean): Pair<String?, Boolean> {
        val reference = ManagedDownloadLyricStore.resolveManagedLyricReference(
            context = context,
            snapshot = snapshot,
            song = song,
            resolvedAudio = resolvedAudio,
            resolvedMetadata = resolvedMetadata,
            translated = translated,
            fileNameTemplate = settings.fileNameTemplate,
            exists = exists
        )
        if (reference != null) {
            readText(reference)?.let { return it to true }
        }
        return (
            ManagedDownloadLyricStore.selectedEmbeddedLyric(resolvedMetadata, translated)
                ?: ManagedDownloadLyricStore.fallbackEmbeddedLyric(resolvedMetadata, translated)
            ) to false
    }

    fun readRomanizedLyric(): Pair<String?, Boolean> {
        val reference = ManagedDownloadLyricStore.resolveManagedRomanizedLyricReference(
            context = context,
            snapshot = snapshot,
            song = song,
            resolvedAudio = resolvedAudio,
            resolvedMetadata = resolvedMetadata,
            fileNameTemplate = settings.fileNameTemplate,
            exists = exists
        )
        if (reference != null) {
            readText(reference)?.let { return it to true }
        }
        return (
            ManagedDownloadLyricStore.selectedEmbeddedRomanizedLyric(resolvedMetadata)
                ?: ManagedDownloadLyricStore.fallbackEmbeddedRomanizedLyric(resolvedMetadata)
            ) to false
    }

    val original = readLyric(translated = false)
    val translated = readLyric(translated = true)
    val romanized = readRomanizedLyric()

    return DownloadedLyricsBundle(
        lyric = original.first,
        translatedLyric = translated.first,
        romanizedLyric = romanized.first,
        hasOriginalSidecar = original.second,
        hasTranslatedSidecar = translated.second,
        hasRomanizedSidecar = romanized.second
    )
}

internal fun ManagedDownloadStorage.findRomanizedLyricLocationImpl(
    context: Context,
    songId: Long,
    candidateBaseNames: List<String>
): String? {
    val snapshot = resolveSnapshotForIndexedLookup(context)
        ?: buildDownloadLibrarySnapshotBlocking(context)
    return ManagedDownloadLyricStore.findRomanizedLyricLocation(
        snapshot = snapshot,
        songId = songId,
        candidateBaseNames = candidateBaseNames
    )
}

internal fun ManagedDownloadStorage.resolveStoredEntryPlaybackUriImpl(
    entry: StoredEntry,
    allowPending: Boolean = false
): String? {
    if (entry.isPendingAudioWrite && !allowPending) {
        return null
    }
    val mediaReference = entry.mediaUri
        .trim()
        .takeIf(::isLocalStorageReference)
    val storageReference = entry.reference
        .trim()
        .takeIf(::isLocalStorageReference)
    // 调用方会在需要时把绝对路径转换为 file URI，这里保持原始引用，
    // 也让 Room/JSON 恢复逻辑不依赖 Android Uri 实现
    return mediaReference ?: storageReference
}

internal suspend fun ManagedDownloadStorage.restoreManagedMigrationEntriesFromJournalImpl(
    root: RootHandle,
    journal: ManagedMigrationReplacementJournal,
    persistedTargetNames: Map<String, String>
): RestoredMigrationManifest? = coroutineScope {
    if (!journal.sourceEntryCountKnown) return@coroutineScope null
    val manifest = linkedMapOf<String, ManagedMigrationSourceEntry>()
    journal.sourceEntries.forEach { rawEntry ->
        val reference = rawEntry.sourceReference.trim()
        if (reference.isBlank()) return@coroutineScope null
        val entry = rawEntry.copy(sourceReference = reference)
        val previous = manifest[reference]
        if (previous != null && previous != entry) return@coroutineScope null
        manifest[reference] = entry
    }
    journal.cleanupReceipts.forEach { receipt ->
        val reference = receipt.sourceReference.trim()
        if (reference.isBlank()) return@coroutineScope null
        val receiptEntry = ManagedMigrationSourceEntry(
            sourceReference = reference,
            sourceName = receipt.sourceName,
            sourceSubdirectory = receipt.sourceSubdirectory,
            sizeBytes = receipt.targetEntry.sizeBytes.coerceAtLeast(0L),
            lastModifiedMs = receipt.targetEntry.lastModifiedMs.coerceAtLeast(0L),
            logicalCreatedAtMs = receipt.sourceLogicalCreatedAtMs,
            createdAtSource = receipt.sourceCreatedAtSource,
            createdAtConfidence = receipt.sourceCreatedAtConfidence
        )
        val previous = manifest[reference]
        if (previous != null && (
                previous.sourceName != receiptEntry.sourceName ||
                    previous.sourceSubdirectory != receiptEntry.sourceSubdirectory
                )
        ) {
            return@coroutineScope null
        }
        manifest.putIfAbsent(reference, receiptEntry)
    }
    if (manifest.size != journal.sourceEntryCount) return@coroutineScope null
    if (manifest.keys.any { reference -> reference !in persistedTargetNames }) {
        return@coroutineScope null
    }
    if (manifest.values.any { entry -> !isMigrationSourceEntryBoundToRoot(root, entry) }) {
        return@coroutineScope null
    }
    val sourceEntries = manifest.values.sortedWith(
        compareBy<ManagedMigrationSourceEntry>(
            { it.sourceSubdirectory.orEmpty() },
            { it.sourceName },
            { it.sourceReference }
        )
    )
    // 持久清单只是结构恢复线索，不能证明所有源文件仍存在
    // 复制 Worker 会读取源文件并区分 Missing、PermissionLost 和 ProviderFailure
    // 重启时不再完整扫描 SAF
    val entries = sourceEntries.map { sourceEntry ->
        val reference = sourceEntry.sourceReference.trim()
        ManagedMigrationEntry(
            subdirectory = sourceEntry.sourceSubdirectory,
            entry = StoredEntry(
                name = sourceEntry.sourceName,
                reference = reference,
                mediaUri = reference,
                localFilePath = reference.takeIf { it.startsWith("/") },
                sizeBytes = sourceEntry.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = sourceEntry.lastModifiedMs.coerceAtLeast(0L),
                isDirectory = false
            ),
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                createdAtMs = sourceEntry.logicalCreatedAtMs,
                createdAtSource = sourceEntry.createdAtSource,
                createdAtConfidence = sourceEntry.createdAtConfidence
            )
        )
    }
    RestoredMigrationManifest(
        entries = entries
    )
}

internal suspend fun ManagedDownloadStorage.buildMigrationTargetIndexFromReceiptsImpl(
    context: Context,
    targetRoot: RootHandle,
    entries: List<ManagedMigrationEntry>,
    persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
    persistedTargetNames: Map<String, String>
): ManagedMigrationTargetIndex? = coroutineScope {
    if (entries.isEmpty() || persistedCopyReceipts.size < entries.size) {
        return@coroutineScope null
    }
    val receiptPairs = entries.mapNotNull { entry ->
        val receipt = persistedCopyReceipts[entry.entry.reference] ?: return@mapNotNull null
        val persistedName = persistedTargetNames[entry.entry.reference]
        if (
            receipt.sourceReference != entry.entry.reference ||
            receipt.sourceName != entry.entry.name ||
            receipt.sourceSubdirectory != entry.subdirectory ||
            !isSafeMigrationPlanName(receipt.targetEntry.name) ||
            persistedName != null && persistedName != receipt.targetEntry.name
        ) {
            return@mapNotNull null
        }
        entry to receipt
    }
    if (receiptPairs.size != entries.size) return@coroutineScope null

    fun buildIndex(
        resolvedEntries: List<Pair<String?, StoredEntry>>
    ): ManagedMigrationTargetIndex? {
        val duplicateKeys = resolvedEntries
            .map { (subdirectory, entry) -> subdirectory to entry.name }
            .let { keys -> keys.size != keys.toSet().size }
        if (duplicateKeys) return null
        return ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = resolvedEntries
                .filter { (subdirectory, _) -> subdirectory == null }
                .map { (_, entry) -> entry },
            coverEntries = resolvedEntries
                .filter { (subdirectory, _) -> subdirectory == COVER_SUBDIRECTORY }
                .map { (_, entry) -> entry },
            lyricEntries = resolvedEntries
                .filter { (subdirectory, _) -> subdirectory == LYRIC_SUBDIRECTORY }
                .map { (_, entry) -> entry }
        )
    }

    val statLimiter = Semaphore(
        migrationCopyParallelism(
            sourceRoot = targetRoot,
            targetRoot = targetRoot
        ).coerceAtLeast(1)
    )
    val actualEntries = receiptPairs.map { (entry, receipt) ->
        async(Dispatchers.IO) {
            statLimiter.withPermit {
                statMigrationReceiptTarget(
                    context = context,
                    targetRoot = targetRoot,
                    receipt = receipt
                )?.let { actual -> entry.subdirectory to actual }
            }
        }
    }.awaitAll()
    if (actualEntries.all { it != null }) {
        buildIndex(actualEntries.filterNotNull())?.let { return@coroutineScope it }
    }

    // 直接探测失败时无法确认受管子目录结构，保留完整回退扫描
    // 最终校验负责确认 Covers 和 Lyrics 位置并清理临时文件
    val snapshot = try {
        treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }
    if (snapshot?.isComplete != true) return@coroutineScope null
    val snapshotEntries = receiptPairs.mapNotNull { (entry, receipt) ->
        if (!isMigrationReferenceBoundToRoot(targetRoot, receipt.targetEntry.reference)) {
            return@mapNotNull null
        }
        val actual = snapshot.entryFor(entry.subdirectory, receipt.targetEntry)
            ?.takeIf { candidate ->
                !candidate.isDirectory && candidate.name == receipt.targetEntry.name
            }
            ?: return@mapNotNull null
        entry.subdirectory to actual
    }
    if (snapshotEntries.size != receiptPairs.size) return@coroutineScope null
    buildIndex(snapshotEntries)
}

internal suspend fun ManagedDownloadStorage.validateMigrationSourceCopyReceiptsImpl(
    context: Context,
    sourceRoot: RootHandle,
    entries: List<ManagedMigrationEntry>,
    persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
    preferDirectStats: Boolean
): ValidatedMigrationCopyReceipts = coroutineScope {
    if (entries.isEmpty() || persistedCopyReceipts.isEmpty()) {
        return@coroutineScope ValidatedMigrationCopyReceipts(
            receipts = emptyMap(),
            sourceEntriesByReference = emptyMap()
        )
    }

    // 新迁移可以使用完整源快照，进程终止后的恢复只需直接校验持久凭据
    // 不必再次遍历整棵 SAF 目录
    val snapshot = if (preferDirectStats) {
        null
    } else {
        try {
            treeDirectories.refreshManagedMigrationEntries(context, sourceRoot)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }
    if (snapshot?.isComplete == true) {
        val validated = entries.mapNotNull { sourceEntry ->
            val receipt = persistedCopyReceipts[sourceEntry.entry.reference]
                ?: return@mapNotNull null
            if (!isMigrationReferenceBoundToRoot(sourceRoot, sourceEntry.entry.reference)) {
                return@mapNotNull null
            }
            val current = snapshot.entryFor(sourceEntry.subdirectory, sourceEntry.entry)
                ?: return@mapNotNull null
            if (
                !isCurrentMigrationSourceFingerprint(
                    receipt = receipt,
                    sourceName = sourceEntry.entry.name,
                    entry = current
                )
            ) {
                return@mapNotNull null
            }
            sourceEntry.entry.reference to (receipt to current)
        }.toMap()
        return@coroutineScope ValidatedMigrationCopyReceipts(
            receipts = validated.mapValues { (_, value) -> value.first },
            sourceEntriesByReference = validated.mapValues { (_, value) -> value.second }
        )
    }

    val statLimiter = Semaphore(
        migrationCopyParallelism(
            sourceRoot = sourceRoot,
            targetRoot = sourceRoot
        ).coerceAtLeast(1)
    )
    val checks = entries.mapNotNull { sourceEntry ->
        val receipt = persistedCopyReceipts[sourceEntry.entry.reference]
            ?: return@mapNotNull null
        async(Dispatchers.IO) {
            statLimiter.withPermit {
                // 清单中的 stat 只证明打开日志时源文件存在，复用凭据前仍要重新探测
                val statResult = statMigrationReceiptSource(
                    context = context,
                    sourceRoot = sourceRoot,
                    sourceEntry = sourceEntry.entry
                )
                if (
                    isCurrentMigrationSourceFingerprint(
                        receipt = receipt,
                        sourceName = sourceEntry.entry.name,
                        statResult = statResult
                    )
                ) {
                    val stat = (statResult as? StorageLookupResult.Found)?.value
                    stat?.let {
                        sourceEntry.entry.reference to (receipt to sourceEntry.entry.copy(
                            sizeBytes = it.sizeBytes ?: sourceEntry.entry.sizeBytes,
                            lastModifiedMs = it.lastModifiedMs
                                ?: sourceEntry.entry.lastModifiedMs,
                            sizeKnown = it.sizeBytes != null || sourceEntry.entry.sizeKnown
                        ))
                    }
                } else {
                    null
                }
            }
        }
    }
    val validated = checks.awaitAll().filterNotNull().toMap()
    ValidatedMigrationCopyReceipts(
        receipts = validated.mapValues { (_, value) -> value.first },
        sourceEntriesByReference = validated.mapValues { (_, value) -> value.second }
    )
}

internal fun ManagedDownloadStorage.buildLyricCandidateNamesImpl(
    songId: Long?,
    candidateBaseNames: List<String>,
    translated: Boolean
): List<String> {
    return ManagedDownloadStorageNaming.buildLyricCandidateNames(
        songId = songId,
        candidateBaseNames = candidateBaseNames,
        translated = translated
    )
}

internal fun ManagedDownloadStorage.buildLyricCandidateNamesImpl(
    songId: Long?,
    candidateBaseNames: List<String>,
    kind: LyricKind
): List<String> {
    return ManagedDownloadStorageNaming.buildLyricCandidateNames(
        songId = songId,
        candidateBaseNames = candidateBaseNames,
        kind = when (kind) {
            LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
            LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
            LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
        }
    )
}

internal fun ManagedDownloadStorage.applySidecarRefreshToSnapshotImpl(
    snapshot: DownloadLibrarySnapshot,
    coverEntries: List<StoredEntry>,
    lyricEntries: List<StoredEntry>
): DownloadLibrarySnapshot {
    return ManagedDownloadSnapshotIndex.applySidecarRefresh(
        snapshot = snapshot,
        coverEntries = coverEntries,
        lyricEntries = lyricEntries
    )
}

internal suspend fun ManagedDownloadStorage.reconcilePendingArtifactsAfterStorageMutationImpl(
    context: Context
): StartupRecoveryResult = withContext(Dispatchers.IO) {
    val pending = cleanupPendingAudioWrites(context)
    val unfinalized = cleanupUnfinalizedDownloadArtifacts(context)
    val terminal = cleanupPersistedTerminalTemporaryWriteArtifacts(context)
    StartupRecoveryResult(
        cleanedCount = pending.cleanedCount +
            unfinalized.cleanedCount +
            terminal.cleanedCount,
        failedCount = pending.failedCount +
            unfinalized.failedCount +
            terminal.failedCount,
        externalSignalRequiredCount = pending.externalSignalRequiredCount +
            unfinalized.externalSignalRequiredCount +
            terminal.externalSignalRequiredCount,
        protectedCount = pending.protectedCount +
            unfinalized.protectedCount +
            terminal.protectedCount,
        protectedReferences = pending.protectedReferences +
            unfinalized.protectedReferences +
            terminal.protectedReferences
    )
}

internal fun ManagedDownloadStorage.cleanupUnfinalizedDownloadArtifactsImpl(context: Context): StartupRecoveryResult {
    return try {
        val root = resolveRootBlocking(context)
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
        if (!refresh.isComplete) {
            NPLogger.w(TAG, "下载目录枚举不完整，跳过未完成半成品清理")
            return StartupRecoveryResult(failedCount = 1)
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过未完成半成品清理")
            return StartupRecoveryResult(failedCount = 1)
        }
        val rootEntries = (refresh.rootEntries + temporary.entries)
            .filterNot(StoredEntry::isDirectory)
        val parsedMetadataEntries = rootEntries
            .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
            .mapNotNull { entry ->
                val metadata = parseDownloadedAudioMetadata(context, entry) ?: return@mapNotNull null
                ManagedDownloadParsedMetadataEntry(entry, metadata)
            }
        val managedSidecarReferences = refresh.coverEntries
            .plus(refresh.lyricEntries)
            .mapTo(linkedSetOf(), StoredEntry::reference)
        val referencesToDelete = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = rootEntries,
            parsedMetadataEntries = parsedMetadataEntries,
            managedSidecarReferences = managedSidecarReferences
        )
        if (referencesToDelete.isEmpty()) {
            StartupRecoveryResult()
        } else {
            var cleanedCount = 0
            var failedCount = 0
            referencesToDelete.forEach { reference ->
                val deleted = deleteInternal(
                    context = context,
                    reference = reference,
                    trustedReferences = referencesToDelete.toSet(),
                    invalidateSnapshot = false
                )
                if (deleted) {
                    cleanedCount++
                } else {
                    failedCount++
                }
            }
            if (cleanedCount > 0 || failedCount > 0) {
                NPLogger.d(TAG, "清理未完成下载半成品完成: cleaned=$cleanedCount, failed=$failedCount")
            }
            StartupRecoveryResult(
                cleanedCount = cleanedCount,
                failedCount = failedCount
            )
        }
    } catch (error: SecurityException) {
        throw error
    } catch (error: ManagedDownloadRootUnavailableException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "清理未完成下载半成品失败: ${error.message}")
        StartupRecoveryResult(failedCount = 1)
    }
}

internal fun ManagedDownloadStorage.mergeTreeChildNamesAfterRefreshImpl(
    refreshedNames: Collection<String>,
    cachedNames: Collection<String>?,
    cachedNamesComplete: Boolean?,
    refreshedComplete: Boolean
): TreeChildNameRefresh {
    return ManagedDownloadTreeChildRegistry.mergeTreeChildNamesAfterRefresh(
        refreshedNames,
        cachedNames,
        cachedNamesComplete,
        refreshedComplete
    )
}

internal fun ManagedDownloadStorage.verifiedCommittedByteCountImpl(
    expectedSizeBytes: Long,
    reportedSizeBytes: Long?,
    countedSizeBytes: Long?,
    toleranceBytes: Long = 0L
): Long? {
    return ManagedDownloadCommitVerifier.verifiedCommittedByteCount(
        expectedSizeBytes = expectedSizeBytes,
        reportedSizeBytes = reportedSizeBytes,
        countedSizeBytes = countedSizeBytes,
        toleranceBytes = toleranceBytes
    )
}

internal fun ManagedDownloadStorage.shouldRejectTransferSizeImpl(
    expectedSizeBytes: Long?,
    actualSizeBytes: Long,
    transferSizeVerified: Boolean
): Boolean {
    return !transferSizeVerified &&
        expectedSizeBytes != null &&
        !ManagedDownloadSizePolicy.isTransferSizeComplete(
            expectedSizeBytes = expectedSizeBytes,
            actualSizeBytes = actualSizeBytes
        )
}

internal suspend fun <T> ManagedDownloadStorage.readStoredEntryForMigrationImpl(
    context: Context,
    entry: StoredEntry,
    block: suspend (InputStream) -> T
): StorageLookupResult<Result<T>> {
    val target = backendReference(context, entry.reference)
        ?: return StorageLookupResult.OutOfScope
    return target.backend.readPreservingBlockFailure(target.reference, block)
}

internal suspend fun ManagedDownloadStorage.deleteEnumeratedMigrationReferencesImpl(
    context: Context,
    references: Collection<TrustedManagedRef>,
    root: RootHandle,
    trustedReferencesSnapshot: Set<TrustedManagedRef>? = null,
    onDeleteStarted: (TrustedManagedRef) -> Unit = {},
    onDeleteFinished: (TrustedManagedRef) -> Unit = {}
): Map<TrustedManagedRef, StorageMutationResult> =
    migrationCleanupTrustLock.withLock {
        if (references.isEmpty()) return@withLock emptyMap()
        val trustedReferences = trustedReferencesSnapshot ?: try {
            enumerateCompleteRootReferences(context, root)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val result = error.toMigrationDeletionResult()
            return@withLock references.associateWith { result }
        } ?: run {
            val result = StorageMutationResult.ProviderFailure(
                IllegalStateException("迁移删除前的完整枚举未完成")
            )
            return@withLock references.associateWith { result }
        }
        val trustedReferenceSet = trustedReferences.mapTo(linkedSetOf()) {
            it.externalReference
        }
        // 整个批次复用不可变的完整枚举信任快照, 只允许快照中的引用进入删除
        val trustedByExternalReference = trustedReferences.associateBy(
            TrustedManagedRef::externalReference
        )
        val eligibleReferences = references.asSequence()
            .mapNotNull { reference ->
                if (reference.externalReference in trustedByExternalReference) {
                    reference
                } else {
                    NPLogger.w(
                        TAG,
                        "迁移删除引用未来自当前完整枚举，保留源: " +
                            "reference=${reference.externalReference} " +
                            "root=${rootIdentityForLog(root)}"
                    )
                    null
                }
            }
            .distinctBy(TrustedManagedRef::externalReference)
            .toList()
        val eligibleResults = if (eligibleReferences.isEmpty()) {
            emptyMap()
        } else {
            val deletePolicy = buildManagedDeletePolicy(
                context = context,
                allowedRoot = root,
                trustedReferences = trustedReferenceSet
            )
            val deleteParallelism = migrationDeleteParallelism(root).coerceAtLeast(1)
            val startedAtMs = System.currentTimeMillis()
            val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
                context = context,
                references = eligibleReferences,
                deletePolicy = deletePolicy,
                parallelism = deleteParallelism,
                onDeleteStarted = onDeleteStarted,
                onDeleteAttemptFinished = { reference, _ ->
                    onDeleteFinished(reference)
                }
            )
            val unresolvedReferences = eligibleReferences.filterNot { reference ->
                reference.externalReference in deleteResult.deletedReferences
            }
            val classifyLimiter = Semaphore(deleteParallelism)
            val unresolvedResults = coroutineScope {
                unresolvedReferences.map { reference ->
                    async(Dispatchers.IO) {
                        classifyLimiter.withPermit {
                            try {
                                classifyMigrationDeleteFailure(context, reference)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                error.toMigrationDeletionResult()
                            }
                        }
                    }
                }.awaitAll()
                    .mapIndexed { index, result ->
                        unresolvedReferences[index].externalReference to result
                    }
                    .toMap()
            }
            val resultsByExternalReference = eligibleReferences.associate { reference ->
                reference.externalReference to if (
                    reference.externalReference in deleteResult.deletedReferences
                ) {
                    StorageMutationResult.Deleted
                } else {
                    unresolvedResults[reference.externalReference]
                        ?: StorageMutationResult.ProviderFailure(
                            IllegalStateException("迁移源文件删除结果缺失")
                        )
                }
            }
            NPLogger.d(
                TAG,
                "迁移源清理完成: requested=${eligibleReferences.size}, " +
                    "costMs=${System.currentTimeMillis() - startedAtMs}, " +
                    "parallelism=$deleteParallelism"
            )
            resultsByExternalReference
        }
        buildMap {
            references.forEach { reference ->
                put(
                    reference,
                    eligibleResults[reference.externalReference]
                        ?: StorageMutationResult.OutOfScope
                )
            }
        }
    }

internal fun ManagedDownloadStorage.isReferenceAllowedForManagedDeleteImpl(
    reference: String,
    trustedReferences: Set<String>,
    managedFileRoots: Collection<String>,
    managedTreeRoots: Collection<String>
): Boolean {
    return ManagedDownloadDeleteGuard.isReferenceAllowedForManagedDelete(
        reference = reference,
        trustedReferences = trustedReferences,
        managedFileRoots = managedFileRoots,
        managedTreeRoots = managedTreeRoots,
        onTrustedReferenceOutsideManagedRoot = { normalizedReference ->
            NPLogger.w(TAG, "受信引用不在托管根内，拒绝删除: $normalizedReference")
        }
    )
}

internal fun ManagedDownloadStorage.storedEntryFromTreeChildImpl(
    name: String,
    documentReference: String,
    sizeBytes: Long,
    lastModifiedMs: Long,
    isDirectory: Boolean
): StoredEntry {
    return ManagedDownloadStoredEntryMapper.fromTreeChild(
        name = name,
        documentReference = documentReference,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        isDirectory = isDirectory
    )
}
