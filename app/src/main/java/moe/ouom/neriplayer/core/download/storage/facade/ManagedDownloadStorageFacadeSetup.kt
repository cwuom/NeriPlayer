package moe.ouom.neriplayer.core.download.storage.facade

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.operation.content.clearTreeDirectoryCache
import moe.ouom.neriplayer.core.download.storage.operation.content.createDefaultRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.invalidateSnapshotCache
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.scheduleSnapshotWarmup
import moe.ouom.neriplayer.core.download.storage.operation.content.treeDocumentIdOrNull
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.hasPendingStartupMigrationRecovery
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveStartupMetadataRecovery
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveStartupPendingAudioRecovery
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.restorePersistedMigrationProgress
import moe.ouom.neriplayer.core.download.storage.operation.requireCompleteMigrationDirectoryScan
import moe.ouom.neriplayer.core.download.storage.operation.shouldIndexMetadataLessAudio
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.startup.AppStartupWorkGate
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal fun ManagedDownloadStorage.initializeImpl(context: Context) {
    val appContext = context.applicationContext
    // 先恢复最后一份持久快照，WorkManager 启动时界面就能显示真实迁移位置
    restorePersistedMigrationProgress(appContext, includeJournal = false)
    snapshotScope.launch {
        restorePersistedMigrationProgress(appContext, includeJournal = true)
        AppStartupWorkGate.awaitInteractiveContentOrTimeout()
        val migrationRecoveryPending = hasPendingStartupMigrationRecovery(appContext)
        val result = if (migrationRecoveryPending) {
            // 迁移 worker 必须独占源和目标目录。这里不创建默认目录、清理
            // 临时文件或预热快照，待迁移完成后的最终扫描再重新打开目录
            NPLogger.i(TAG, "检测到迁移恢复凭据，延后启动存储清理与快照预热")
            StartupRecoveryResult()
        } else {
            runCatching {
                if (settings.configuredDirectoryUri.isNullOrBlank()) {
                    createDefaultRoot(appContext)
                }
                val stagingRecovery = cleanupStagingFiles(appContext)
                val pendingAudioRecovery = resolveStartupPendingAudioRecovery(appContext)
                val metadataRecovery = resolveStartupMetadataRecovery(appContext)
                val terminalTemporaryWriteRecovery =
                    cleanupPersistedTerminalTemporaryWriteArtifacts(appContext)
                StartupRecoveryResult(
                    cleanedCount = stagingRecovery.cleanedCount +
                        pendingAudioRecovery.cleanedCount +
                        metadataRecovery.cleanedCount +
                        terminalTemporaryWriteRecovery.cleanedCount,
                    failedCount = stagingRecovery.failedCount +
                        pendingAudioRecovery.failedCount +
                        metadataRecovery.failedCount +
                        terminalTemporaryWriteRecovery.failedCount,
                    externalSignalRequiredCount =
                        stagingRecovery.externalSignalRequiredCount +
                            pendingAudioRecovery.externalSignalRequiredCount +
                            metadataRecovery.externalSignalRequiredCount +
                            terminalTemporaryWriteRecovery.externalSignalRequiredCount
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "后台初始化下载存储失败: ${error.message}")
            }.getOrDefault(StartupRecoveryResult())
        }
        startupRecoveryResult = result
        if (result.hasRecoveredEntries) {
            _startupRecoveryResults.tryEmit(result)
        }
        if (!migrationRecoveryPending) {
            // 进程重启只清空内存索引, 保留持久化缓存供首屏预览和重建回退
            scheduleSnapshotWarmup(appContext)
        }
    }
}

internal fun ManagedDownloadStorage.resolveFailedStableKeysImpl(
    referencesByStableKey: Map<String, Set<String>>,
    failedReferences: Set<String>
): Set<String> {
    if (referencesByStableKey.isEmpty() || failedReferences.isEmpty()) {
        return emptySet()
    }
    return referencesByStableKey
        .asSequence()
        .filter { (_, references) -> references.any(failedReferences::contains) }
        .mapTo(linkedSetOf()) { (stableKey, _) -> stableKey }
}

internal fun ManagedDownloadStorage.resolveUnreadablePendingArtifactReferencesImpl(
    pendingEntries: Collection<StoredEntry>,
    metadataEntries: Collection<StoredEntry>,
    unreadableMetadataReferences: Set<String>
): Set<String> {
    if (
        pendingEntries.isEmpty() ||
        metadataEntries.isEmpty() ||
        unreadableMetadataReferences.isEmpty()
    ) {
        return emptySet()
    }
    val unreadableAudioNames = metadataEntries
        .asSequence()
        .filter { it.reference in unreadableMetadataReferences }
        .mapNotNull { entry ->
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)
        }
        .toSet()
    if (unreadableAudioNames.isEmpty()) return emptySet()
    return pendingEntries
        .asSequence()
        .filter { entry ->
            val logicalName = if (entry.isPendingAudioWrite) {
                entry.logicalName
            } else {
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
            entry.reference in unreadableMetadataReferences ||
                logicalName != null && logicalName in unreadableAudioNames
        }
        .mapTo(linkedSetOf(), StoredEntry::reference)
}

internal fun ManagedDownloadStorage.metadataForAudioEntryImpl(
    snapshot: DownloadLibrarySnapshot?,
    audio: StoredEntry
): DownloadedAudioMetadata? {
    val metadataByAudioName = snapshot?.metadataByAudioName ?: return null
    if (audio.isPendingAudioWrite) {
        snapshot.pendingMetadataByAudioName[audio.logicalName]?.let { return it }
        snapshot.pendingMetadataByCanonicalAudioName[
            ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName)
        ]?.let { return it }
    }
    val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(audio.name)
    val canonicalLogicalName = ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName)
    return metadataByAudioName[audio.name]
        ?: metadataByAudioName[audio.logicalName]
        ?: snapshot.metadataByCanonicalAudioName[canonicalAudioName]
        ?: snapshot.metadataByCanonicalAudioName[canonicalLogicalName]
        ?: snapshot.metadataByDeclaredAudioName[canonicalAudioName]
        ?: snapshot.metadataByDeclaredAudioName[canonicalLogicalName]
        ?: snapshot.metadataByStoredReference[audio.reference]
        ?: snapshot.metadataByStoredReference[audio.mediaUri]
        ?: audio.localFilePath?.let(snapshot.metadataByStoredReference::get)
}

internal fun ManagedDownloadStorage.primeSettingsImpl(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String? = null) {
    settings.prime(
        directoryUri = directoryUri,
        directoryLabel = directoryLabel,
        fileNameTemplate = fileNameTemplate
    )
    val generation = LocalStorageRootGeneration.update(directoryUri)
    LocalAssetInvalidationBus.publishRootChanged(generation)
    clearTreeDirectoryCache()
    invalidateSnapshotCache()
}

internal suspend fun ManagedDownloadStorage.hasMigratableDownloadsImpl(
    context: Context,
    directoryUri: String?
): Boolean = withContext(Dispatchers.IO) {
    val startedAtNanos = System.nanoTime()
    try {
        val root = resolveRoot(context, directoryUri)
            ?: throw ManagedDownloadRootUnavailableException(directoryUri.orEmpty())
        val allowMetadataLessAudio = shouldIndexMetadataLessAudio(directoryUri)
        var sidecarEnumerationRequired = false
        val refresh = treeDirectories.refreshManagedMigrationEntries(
            context = context,
            root = root,
            requiresSidecarEntries = { rootEntries ->
                ManagedDownloadMigrationEntryCollector.requiresSidecarEvidence(
                    rootEntries = rootEntries,
                    allowMetadataLessAudio = allowMetadataLessAudio
                ).also { required ->
                    sidecarEnumerationRequired = required
                }
            }
        )
        requireCompleteMigrationDirectoryScan(
            root = root,
            isComplete = refresh.isComplete
        )
        val hasManagedEntries = ManagedDownloadMigrationEntryCollector.hasAnyManagedEntry(
            rootEntries = refresh.rootEntries,
            coverEntries = refresh.coverEntries,
            lyricEntries = refresh.lyricEntries,
            allowMetadataLessAudio = allowMetadataLessAudio
        )
        NPLogger.d(
            TAG,
            "migration_preflight stage=presence_scan status=complete " +
                "rootType=${if (root is RootHandle.TreeRoot) "tree" else "file"} " +
                "rootEntries=${refresh.rootEntries.size} " +
                "coverEntries=${refresh.coverEntries.size} " +
                "lyricEntries=${refresh.lyricEntries.size} " +
                "sidecarEnumerationRequired=$sidecarEnumerationRequired " +
                "managedEntriesPresent=$hasManagedEntries " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
        )
        hasManagedEntries
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "migration_preflight stage=presence_scan status=failed " +
                "errorType=${error::class.java.simpleName} " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
        )
        throw error
    }
}

internal suspend fun ManagedDownloadStorage.hasActualDirectoryEntriesImpl(
    context: Context,
    directoryUri: String?
): Boolean = withContext(Dispatchers.IO) {
    val startedAtNanos = System.nanoTime()
    try {
        val root = resolveRoot(context, directoryUri)
            ?: throw ManagedDownloadRootUnavailableException(directoryUri.orEmpty())
        val refresh = treeDirectories.refreshRootEntries(context, root)
        requireCompleteMigrationDirectoryScan(
            root = root,
            isComplete = refresh.isComplete
        )
        val rootDocumentId = (root as? RootHandle.TreeRoot)
            ?.tree
            ?.uri
            ?.let(::treeDocumentIdOrNull)
        var ignoredSelfRows = 0
        val hasActualEntries = refresh.entries.any { entry ->
            val isVirtualSelfRow = rootDocumentId != null &&
                runCatching { treeDocumentIdOrNull(entry.mediaUri.toUri()) }
                    .getOrNull() == rootDocumentId
            if (isVirtualSelfRow) {
                ignoredSelfRows++
            }
            !isVirtualSelfRow
        }
        NPLogger.d(
            TAG,
            "migration_preflight stage=target_non_empty status=complete " +
                "rootType=${if (root is RootHandle.TreeRoot) "tree" else "file"} " +
                "rootEntries=${refresh.entries.size} " +
                "ignoredSelfRows=$ignoredSelfRows " +
                "nonEmpty=$hasActualEntries " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
        )
        hasActualEntries
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "migration_preflight stage=target_non_empty status=failed " +
                "errorType=${error::class.java.simpleName} " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
        )
        throw error
    }
}
