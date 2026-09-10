package moe.ouom.neriplayer.core.download.artifact

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

/** 批量删除结果，用于把竞态和真正的“已不存在”区分开 */
internal data class ManagedDownloadArtifactBatchDeleteResult(
    val requestedCount: Int,
    val removedCount: Int,
    val missingCount: Int,
    val racedCount: Int
) {
    val isComplete: Boolean
        get() = racedCount == 0
}

internal class ManagedDownloadArtifactCoordinator {
    /** 只为不存在的条目批量预创建租约，已有条目仍走完整 claim 校验 */
    suspend fun precreateMissingArtifacts(
        context: Context,
        songs: Collection<SongItem>,
        leaseOwnerIds: Map<String, String> = emptyMap()
    ): Map<String, ManagedDownloadArtifactClaim.Acquired> {
        val songsByStableKey = linkedMapOf<String, SongItem>()
        songs.forEach { song ->
            song.stableKey().trim().takeIf(String::isNotBlank)?.let { stableKey ->
                songsByStableKey.putIfAbsent(stableKey, song)
            }
        }
        if (songsByStableKey.isEmpty()) return emptyMap()

        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val existingKeys = linkedSetOf<String>()
            songsByStableKey.keys.chunked(BATCH_ARTIFACT_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.findAllByStableKeys(chunk)
                    .filterNot { artifact ->
                        ManagedDownloadArtifactState.fromPersisted(artifact.state) in setOf(
                            ManagedDownloadArtifactState.CANCELLED,
                            ManagedDownloadArtifactState.FAILED_RETRYABLE,
                            ManagedDownloadArtifactState.MISSING_CONFIRMED
                        ) && artifact.leaseId == null
                    }
                    .forEach { artifact -> existingKeys += artifact.stableKey }
            }
            val missing = songsByStableKey
                .filterKeys { stableKey -> stableKey !in existingKeys }
                .map { (stableKey, _) ->
                    newLeaseArtifact(
                        rootKey = rootKey,
                        stableKey = stableKey,
                        artifactId = artifactId(rootKey, stableKey),
                        previous = null,
                        nowMs = nowMs,
                        leaseOwnerId = leaseOwnerIds[stableKey]
                    )
                }
            if (missing.isEmpty()) return@withTransaction emptyMap()
            val insertResults = missing.chunked(BATCH_ARTIFACT_INSERT_CHUNK_SIZE)
                .flatMap { chunk -> dao.insertIfAbsentAll(chunk) }
            missing.mapIndexedNotNull { index, artifact ->
                if (insertResults.getOrNull(index)?.let { result -> result >= 0L } == true) {
                    artifact.stableKey to ManagedDownloadArtifactClaim.Acquired(artifact)
                } else {
                    null
                }
            }.toMap()
        }
    }

    suspend fun claim(
        context: Context,
        song: SongItem,
        reconcileStorage: Boolean = false,
        leaseOwnerId: String? = null,
        allowFreshTransferReclaim: Boolean = false
    ): ManagedDownloadArtifactClaim {
        val appContext = context.applicationContext
        val normalizedLeaseOwnerId = leaseOwnerId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank)
            ?: return createUntrackedClaim(normalizedLeaseOwnerId)
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        val dao = database.managedDownloadArtifactDao()
        val current = dao.find(rootKey, stableKey)
        // 根目录切换不会同步改写旧 artifact 行。先复用旧根的活动或已提交行，
        // 防止新根在迁移窗口内再创建第二个 lease
        val foreign = dao.findAllByStableKey(stableKey)
            .asSequence()
            .filter { artifact -> artifact.rootKey != rootKey }
            .sortedWith(
                compareByDescending<ManagedDownloadArtifactEntity> {
                    crossRootArtifactPriority(it)
                }.thenByDescending { it.updatedAtMs }
            )
            .firstOrNull()
            ?.takeIf { artifact ->
                val state = ManagedDownloadArtifactState.fromPersisted(artifact.state)
                state in CROSS_ROOT_AUTHORITATIVE_STATES ||
                    artifact.leaseId != null && state in CROSS_ROOT_LEASE_STATES
            }
        if (
            foreign != null &&
                (current == null || !isCrossRootAuthoritative(current))
        ) {
            return resolveExistingClaim(
                context = appContext,
                database = database,
                current = foreign,
                nowMs = nowMs,
                rootKey = foreign.rootKey,
                stableKey = stableKey,
                leaseOwnerId = normalizedLeaseOwnerId,
                allowFreshTransferReclaim = allowFreshTransferReclaim
            )
        }
        val discovered = if (current == null && reconcileStorage) {
            val discovered = discoverExistingAudio(appContext, song)
            discovered?.let {
                newDiscoveredArtifact(
                    rootKey = rootKey,
                    stableKey = stableKey,
                    discovered = it,
                    nowMs = nowMs
                )
            }
        } else {
            null
        }

        val raced = dao.find(rootKey, stableKey)
        if (raced != null) {
            return resolveExistingClaim(
                context = appContext,
                database = database,
                current = raced,
                nowMs = nowMs,
                rootKey = rootKey,
                stableKey = stableKey,
                leaseOwnerId = normalizedLeaseOwnerId,
                allowFreshTransferReclaim = allowFreshTransferReclaim
            )
        }
        if (discovered != null) {
            val inserted = dao.insertIfAbsent(discovered)
            if (inserted >= 0L) {
                return discovered.toClaim(nowMs)
            }
            return dao.find(rootKey, stableKey)
                ?.let { winner ->
                    resolveExistingClaim(
                        context = appContext,
                        database = database,
                        current = winner,
                        nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = normalizedLeaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim
                    )
                }
                ?: unavailableClaim(discovered)
        }

        val acquired = newLeaseArtifact(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = artifactId(rootKey, stableKey),
            previous = null,
            nowMs = nowMs,
            leaseOwnerId = normalizedLeaseOwnerId
        )
        val inserted = dao.insertIfAbsent(acquired)
        if (inserted >= 0L) {
            return ManagedDownloadArtifactClaim.Acquired(acquired)
        }
        return dao.find(rootKey, stableKey)
            ?.let { winner ->
                resolveExistingClaim(
                    context = appContext,
                    database = database,
                    current = winner,
                    nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = normalizedLeaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim
                )
            }
            ?: unavailableClaim(acquired)
    }

    suspend fun reconcileCatalog(
        context: Context,
        songs: Collection<DownloadedSong>
    ) {
        val normalizedSongs = songs.mapNotNull { song ->
            val stableKey = song.stableKey?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            stableKey to song
        }
        if (normalizedSongs.isEmpty()) return
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        val observedKeys = normalizedSongs.mapTo(linkedSetOf()) { (stableKey, _) -> stableKey }
        val staleCandidates = database.managedDownloadArtifactDao()
            .findAllByRootKey(rootKey)
            .filter { artifact ->
                artifact.stableKey !in observedKeys &&
                    !isActive(artifact) &&
                    !artifact.audioReference.isNullOrBlank()
            }
        staleCandidates.forEach { artifact ->
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context = appContext,
                reference = artifact.audioReference
            )
            if (ManagedDownloadReferenceLookup.canMarkMissing(evidence)) {
                database.managedDownloadArtifactDao().markMissingIfUnchanged(
                    rootKey = rootKey,
                    stableKey = artifact.stableKey,
                    expectedState = artifact.state,
                    expectedUpdatedAtMs = artifact.updatedAtMs,
                    missingState = ManagedDownloadArtifactState.MISSING_CONFIRMED.name,
                    updatedAtMs = nowMs,
                    errorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                )
            }
        }
        database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val existingByStableKey = dao.findAllByRootKey(rootKey).associateBy(
                ManagedDownloadArtifactEntity::stableKey
            )
            val updates = normalizedSongs.mapNotNull { (stableKey, song) ->
                val current = existingByStableKey[stableKey]
                if (current != null && isActive(current)) {
                    null
                } else {
                    catalogArtifact(
                        rootKey = rootKey,
                        stableKey = stableKey,
                        current = current,
                        song = song,
                        nowMs = nowMs
                    )
                }
            }
            if (updates.isNotEmpty()) {
                dao.upsertAll(updates)
            }
        }
    }

    suspend fun reconcileEmptyConfirmed(
        context: Context,
        rootKey: String
    ) {
        val database = database(context.applicationContext)
        database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            dao.findAllByRootKey(rootKey)
                .filterNot(::isActive)
                .forEach { artifact ->
                    dao.deleteIfUnchanged(
                        rootKey = rootKey,
                        stableKey = artifact.stableKey,
                        expectedState = artifact.state,
                        expectedLeaseId = artifact.leaseId,
                        expectedUpdatedAtMs = artifact.updatedAtMs
                    )
                }
        }
    }

    suspend fun reconcilePendingStorage(
        context: Context,
        songs: Collection<SongItem>
    ) {
        if (songs.isEmpty()) return
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val snapshot = loadDiscoverySnapshot(appContext) ?: return
        val nowMs = System.currentTimeMillis()
        val candidates = songs.mapNotNull { song ->
            val stableKey = song.stableKey().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val discovered = discoverExistingAudio(snapshot, song)
                ?: return@mapNotNull null
            stableKey to newDiscoveredArtifact(
                rootKey = rootKey,
                stableKey = stableKey,
                discovered = discovered,
                nowMs = nowMs
            )
        }.distinctBy { (stableKey, _) -> stableKey }
        if (candidates.isEmpty()) return
        val database = database(appContext)
        database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val existingKeys = dao.findAllByStableKeys(
                candidates.map { (stableKey, _) -> stableKey }
            )
                .asSequence()
                .map(ManagedDownloadArtifactEntity::stableKey)
                .toSet()
            val missing = candidates
                .filterNot { (stableKey, _) -> stableKey in existingKeys }
                .map { (_, discovered) -> discovered }
            if (missing.isNotEmpty()) {
                dao.insertIfAbsentAll(missing)
            }
        }
    }

    suspend fun filterNotFinalized(
        context: Context,
        songs: Collection<SongItem>
    ): List<SongItem> {
        if (songs.isEmpty()) return emptyList()
        val appContext = context.applicationContext
        val dao = database(appContext).managedDownloadArtifactDao()
        val stableKeys = songs.mapNotNull { song ->
            song.stableKey().trim().takeIf(String::isNotBlank)
        }.distinct()
        val artifactsByStableKey = stableKeys
            .chunked(BATCH_ARTIFACT_QUERY_CHUNK_SIZE)
            .flatMap { chunk -> dao.findAllByStableKeys(chunk) }
            .groupBy(ManagedDownloadArtifactEntity::stableKey)
            .mapValues { (_, artifacts) ->
                artifacts.maxWithOrNull(
                    compareBy<ManagedDownloadArtifactEntity> {
                        crossRootArtifactPriority(it)
                    }.thenBy { it.updatedAtMs }
                )
            }
        val snapshot = loadLiveFinalizationSnapshot(appContext)
        return songs.filter { song ->
            val stableKey = song.stableKey().trim()
            val artifact = artifactsByStableKey[stableKey]
            artifact == null ||
                finalizedArtifactCompletionDisposition(
                    snapshot = snapshot,
                    artifact = artifact,
                    stableKey = stableKey
                ) != ManagedDownloadArtifactFinalizationDisposition.SETTLED
        }
    }

    /**
     * 用轻量 artifact ledger 预检已经提交的音频，避免目录 catalog 尚未恢复时重复建队
     *
     * 这里只返回有正式音频引用且 provider 明确可读的 post-core 条目；未知或异常引用
     * 交给后续 claim/recovery 路径处理，不能在启动预检阶段乐观跳过
     */
    suspend fun findReadableCompletedStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): Set<String> {
        val normalizedKeys = stableKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedKeys.isEmpty()) return emptySet()
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val artifacts = database(appContext).managedDownloadArtifactDao()
            .let { dao ->
                normalizedKeys
                    .chunked(BATCH_ARTIFACT_QUERY_CHUNK_SIZE)
                    .flatMap { chunk -> dao.findAllByRootKeyAndStableKeys(rootKey, chunk) }
            }
            .groupBy(ManagedDownloadArtifactEntity::stableKey)
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<ManagedDownloadArtifactEntity> { it.updatedAtMs }
                        .thenBy { it.audioReference.orEmpty() }
                )
            }
        val completedStates = setOf(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.FINALIZED,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.REPAIR_REQUIRED
        )
        return normalizedKeys.mapNotNull { stableKey ->
            val artifact = artifacts[stableKey] ?: return@mapNotNull null
            val state = ManagedDownloadArtifactState.fromPersisted(artifact.state)
            if (state !in completedStates) return@mapNotNull null
            val references = listOfNotNull(
                artifact.audioReference?.trim()?.takeIf(String::isNotBlank),
                artifact.audioName?.trim()?.takeIf { reference ->
                    reference.startsWith("/") || reference.contains("://")
                }
            ).distinct()
            if (references.any { reference ->
                    ManagedDownloadReferenceLookup.inspect(appContext, reference) is
                        ManagedDownloadReferenceLookup.Result.Present
                }
            ) {
                stableKey
            } else {
                null
            }
        }.toSet()
    }

    suspend fun currentLeaseId(
        context: Context,
        song: SongItem,
        rootKeyOverride: String? = null
    ): String? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        val rootKey = rootKeyOverride
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        return database(context.applicationContext).managedDownloadArtifactDao()
            .find(rootKey, stableKey)
            ?.leaseId
    }

    suspend fun currentLeaseIdAnyRoot(
        context: Context,
        song: SongItem
    ): String? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        return database(context.applicationContext).managedDownloadArtifactDao()
            .findAllByStableKey(stableKey)
            .asSequence()
            .filter { artifact -> artifact.leaseId != null }
            .maxWithOrNull(compareBy<ManagedDownloadArtifactEntity> { it.updatedAtMs })
            ?.leaseId
    }

    suspend fun currentState(
        context: Context,
        song: SongItem
    ): ManagedDownloadArtifactState? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        val dao = database(context.applicationContext).managedDownloadArtifactDao()
        return (dao.find(rootKey, stableKey)
            ?: dao.findAllByStableKey(stableKey).firstOrNull())
            ?.state
            ?.let(ManagedDownloadArtifactState::fromPersisted)
    }

    suspend fun currentStateAnyRoot(
        context: Context,
        song: SongItem,
        expectedLeaseId: String? = null
    ): ManagedDownloadArtifactState? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        val artifacts = database(context.applicationContext).managedDownloadArtifactDao()
            .findAllByStableKey(stableKey)
        val normalizedLeaseId = expectedLeaseId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val selected = normalizedLeaseId?.let { leaseId ->
            artifacts.firstOrNull { artifact -> artifact.leaseId == leaseId }
        } ?: artifacts.maxWithOrNull(
            compareBy<ManagedDownloadArtifactEntity> {
                crossRootArtifactPriority(it)
            }.thenBy { it.updatedAtMs }
        )
        return selected
            ?.state
            ?.let(ManagedDownloadArtifactState::fromPersisted)
    }

    /** 在目录迁移或取消竞态中，按 stableKey 跨根释放同一个 owner 的 lease */
    suspend fun settleLeaseAnyRoot(
        context: Context,
        song: SongItem,
        expectedLeaseId: String,
        requestedState: ManagedDownloadArtifactState =
            ManagedDownloadArtifactState.CANCELLED,
        errorCode: String? = null
    ): Boolean {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return false
        val normalizedLeaseId = expectedLeaseId.trim().takeIf(String::isNotBlank)
            ?: return false
        val database = database(context.applicationContext)
        val nowMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val matches = dao.findAllByStableKey(stableKey)
                .filter { artifact -> artifact.leaseId == normalizedLeaseId }
            if (matches.isEmpty()) return@withTransaction false
            matches.forEach { current ->
                val currentState = ManagedDownloadArtifactState.fromPersisted(current.state)
                val nextState = resolveArtifactStateUpdate(
                    current = currentState,
                    requested = requestedState
                )
                val releaseAfterMonotonicUpdate =
                    shouldReleaseLeaseAfterMonotonicArtifactUpdate(
                        current = currentState,
                        requested = requestedState,
                        clearLease = true
                    )
                if (nextState != currentState &&
                    nextState != requestedState &&
                    !releaseAfterMonotonicUpdate
                ) {
                    return@forEach
                }
                val persistedState = if (releaseAfterMonotonicUpdate) {
                    currentState
                } else {
                    nextState
                }
                dao.upsert(
                    current.copy(
                        state = persistedState.name,
                        leaseId = null,
                        updatedAtMs = nowMs,
                        needsReconcile = persistedState !=
                            ManagedDownloadArtifactState.FINALIZED,
                        lastErrorCode = errorCode
                            ?: if (releaseAfterMonotonicUpdate) {
                                "CANCELLED_AFTER_CORE_COMMIT"
                            } else {
                                null
                            }
                    )
                )
            }
            true
        }
    }

    suspend fun markCoreCommitted(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        expectedLeaseId: String? = null,
        rootKeyOverride: String? = null
    ): Boolean {
        val appContext = context.applicationContext
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return false
        val rootKey = rootKeyOverride
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        var committed = false
        database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val current = dao.find(rootKey, stableKey)
                ?: expectedLeaseId?.let { leaseId ->
                    dao.findAllByStableKey(stableKey)
                        .firstOrNull { artifact -> artifact.leaseId == leaseId }
                }
            if (current == null && expectedLeaseId != null) return@withTransaction
            if (current != null && !matchesLease(current, expectedLeaseId)) return@withTransaction
            val base = current ?: newLeaseArtifact(
                rootKey = rootKey,
                stableKey = stableKey,
                artifactId = artifactId(rootKey, stableKey),
                previous = null,
                nowMs = nowMs,
                leaseOwnerId = expectedLeaseId
            ).copy(leaseId = expectedLeaseId)
            dao.upsert(
                base.copy(
                    state = ManagedDownloadArtifactState.CORE_COMMITTED.name,
                    leaseId = base.leaseId,
                    audioReference = storedAudio.reference,
                    audioName = storedAudio.name,
                    fileSize = storedAudio.sizeBytes,
                    downloadedAtMs = base.downloadedAtMs ?: nowMs,
                    updatedAtMs = nowMs,
                    needsReconcile = false,
                    lastErrorCode = null
                )
            )
            committed = true
        }
        return committed
    }

    suspend fun markAssetsEnriching(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.ASSETS_ENRICHING,
            clearLease = false
        )
    }

    suspend fun markDegradedComplete(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?,
        errorCode: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            clearLease = true,
            errorCode = errorCode
        )
    }

    suspend fun markCommitting(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.COMMITTING,
            clearLease = false
        )
    }

    suspend fun markFinalized(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        expectedLeaseId: String? = null
    ): Boolean {
        val appContext = context.applicationContext
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return false
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val current = dao.find(rootKey, stableKey)
                ?: expectedLeaseId?.let { leaseId ->
                    dao.findAllByStableKey(stableKey)
                        .firstOrNull { artifact -> artifact.leaseId == leaseId }
                }
            if (current == null && expectedLeaseId != null) {
                return@withTransaction false
            }
            if (current != null && !matchesLease(current, expectedLeaseId)) {
                return@withTransaction false
            }
            val base = current ?: newLeaseArtifact(
                rootKey = rootKey,
                stableKey = stableKey,
                artifactId = artifactId(rootKey, stableKey),
                previous = null,
                nowMs = nowMs,
                leaseOwnerId = expectedLeaseId
            ).copy(leaseId = expectedLeaseId)
            dao.upsert(
                base.copy(
                    state = ManagedDownloadArtifactState.FINALIZED.name,
                    leaseId = null,
                    audioReference = storedAudio.reference,
                    audioName = storedAudio.name,
                    fileSize = storedAudio.sizeBytes,
                    finalizedAtMs = nowMs,
                    downloadedAtMs = base.downloadedAtMs ?: nowMs,
                    updatedAtMs = nowMs,
                    needsReconcile = false,
                    lastErrorCode = null
                )
            )
            true
        }
    }

    suspend fun markRetryable(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?,
        errorCode: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.FAILED_RETRYABLE,
            clearLease = true,
            errorCode = errorCode
        )
    }

    /** 空间或目录暂不可用时保留 lease，恢复时可继续使用同一个工作文件 */
    suspend fun markWaitingForStorage(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?,
        errorCode: String
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.WAITING_STORAGE,
            clearLease = false,
            errorCode = errorCode
        )
    }

    suspend fun markRepairRequired(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?,
        errorCode: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.REPAIR_REQUIRED,
            clearLease = true,
            errorCode = errorCode
        )
    }

    suspend fun markMissingConfirmed(
        context: Context,
        song: SongItem,
        errorCode: String?
    ) {
        updateStateWithoutLease(
            context = context,
            song = song,
            state = ManagedDownloadArtifactState.MISSING_CONFIRMED,
            errorCode = errorCode
        )
    }

    suspend fun markCancelled(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?
    ) {
        updateState(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            state = ManagedDownloadArtifactState.CANCELLED,
            clearLease = true
        )
    }

    suspend fun delete(
        context: Context,
        song: SongItem
    ) {
        deleteByStableKey(context, song.stableKey())
    }

    suspend fun deleteByStableKey(
        context: Context,
        stableKey: String?
    ): Boolean {
        val normalizedStableKey = stableKey?.trim()?.takeIf(String::isNotBlank) ?: return false
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        val database = database(context.applicationContext)
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val current = dao.find(rootKey, normalizedStableKey) ?: return@withTransaction true
            dao.deleteIfUnchanged(
                rootKey = rootKey,
                stableKey = normalizedStableKey,
                expectedState = current.state,
                expectedLeaseId = current.leaseId,
                expectedUpdatedAtMs = current.updatedAtMs
            ) > 0
        }
    }

    /** 在一个 Room 事务内批量清理指定歌曲，避免每首歌曲重复打开事务 */
    suspend fun deleteByStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): ManagedDownloadArtifactBatchDeleteResult {
        val normalizedKeys = stableKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return ManagedDownloadArtifactBatchDeleteResult(0, 0, 0, 0)
        }
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val currentByStableKey = normalizedKeys
                .toList()
                .chunked(BATCH_ARTIFACT_QUERY_CHUNK_SIZE)
                .flatMap { chunk ->
                    dao.findAllByRootKeyAndStableKeys(rootKey, chunk)
                }
                .associateBy(ManagedDownloadArtifactEntity::stableKey)
            var removedCount = 0
            var missingCount = 0
            var racedCount = 0
            normalizedKeys.forEach { key ->
                val current = currentByStableKey[key]
                when {
                    current == null -> missingCount++
                    current.leaseId != null -> racedCount++
                    dao.deleteIfUnchanged(
                        rootKey = rootKey,
                        stableKey = key,
                        expectedState = current.state,
                        expectedLeaseId = null,
                        expectedUpdatedAtMs = current.updatedAtMs
                    ) > 0 -> removedCount++
                    else -> racedCount++
                }
            }
            ManagedDownloadArtifactBatchDeleteResult(
                requestedCount = normalizedKeys.size,
                removedCount = removedCount,
                missingCount = missingCount,
                racedCount = racedCount
            )
        }
    }

    /** 全库物理删除已经确认后，原子清掉当前根目录的无租约凭据 */
    suspend fun deleteAllLeaseFree(
        context: Context
    ): ManagedDownloadArtifactBatchDeleteResult {
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val requestedCount = dao.countByRootKey(rootKey)
            val activeBefore = dao.countLeasedByRootKey(rootKey)
            val removedCount = dao.deleteLeaseFreeByRootKey(rootKey)
            val activeAfter = dao.countLeasedByRootKey(rootKey)
            ManagedDownloadArtifactBatchDeleteResult(
                requestedCount = requestedCount,
                removedCount = removedCount,
                missingCount = (requestedCount - removedCount - activeBefore)
                    .coerceAtLeast(0),
                racedCount = maxOf(activeBefore, activeAfter)
            )
        }
    }

    /** 取消和 Provider 清理已确认后，收敛旧租约并删除当前根目录凭据 */
    suspend fun deleteAllAfterCancellationSettled(
        context: Context
    ): ManagedDownloadArtifactBatchDeleteResult {
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        return database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val requestedCount = dao.countByRootKey(rootKey)
            val removedCount = dao.deleteAllByRootKey(rootKey)
            ManagedDownloadArtifactBatchDeleteResult(
                requestedCount = requestedCount,
                removedCount = removedCount,
                missingCount = (requestedCount - removedCount).coerceAtLeast(0),
                racedCount = (requestedCount - removedCount).coerceAtLeast(0)
            )
        }
    }

    private suspend fun resolveExistingClaim(
        context: Context,
        database: NeriUserDataDatabase,
        current: ManagedDownloadArtifactEntity,
        nowMs: Long,
        rootKey: String,
        stableKey: String,
        leaseOwnerId: String?,
        allowFreshTransferReclaim: Boolean,
        retryCount: Int = 0
    ): ManagedDownloadArtifactClaim {
        val dao = database.managedDownloadArtifactDao()
        return when (
            ManagedDownloadArtifactPolicy.decide(
                existing = current,
                nowMs = nowMs,
                leaseOwnerId = leaseOwnerId
            )
        ) {
            ManagedDownloadArtifactDecision.AlreadyDownloaded -> {
                val artifactState = ManagedDownloadArtifactState.fromPersisted(current.state)
                val reference = current.audioReference
                val referenceState = withContext(Dispatchers.IO) {
                    classifyManagedDownloadArtifactReference(
                        ManagedDownloadReferenceLookup.inspect(context, reference)
                    )
                }
                val finalizationDisposition = if (
                    artifactState == ManagedDownloadArtifactState.FINALIZED
                ) {
                    inspectFinalizedArtifactCompletion(
                        context = context,
                        current = current,
                        stableKey = stableKey
                    )
                } else {
                    null
                }
                if (
                    shouldForceFreshTransferForUser(
                        artifactState = artifactState,
                        userInitiated = allowFreshTransferReclaim,
                        currentLeaseId = current.leaseId,
                        leaseOwnerId = leaseOwnerId
                    )
                ) {
                    return acquireExistingClaim(
                        context = context,
                        database = database,
                        current = current,
                        nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = leaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim,
                        retryCount = retryCount,
                        preservesExistingReference = !reference.isNullOrBlank()
                    )
                }
                if (
                    shouldReclaimUnavailableArtifactForFreshTransfer(
                        artifactState = artifactState,
                        referenceState = referenceState,
                        userInitiated = allowFreshTransferReclaim,
                        currentLeaseId = current.leaseId,
                        leaseOwnerId = leaseOwnerId
                    )
                ) {
                    return acquireExistingClaim(
                        context = context,
                        database = database,
                        current = current,
                        nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = leaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim,
                        retryCount = retryCount,
                        preservesExistingReference = !reference.isNullOrBlank()
                    )
                }
                if (
                    shouldReclaimUnavailableFinalizationForFreshTransfer(
                        artifactState = artifactState,
                        disposition = finalizationDisposition
                            ?: ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE,
                        userInitiated = allowFreshTransferReclaim,
                        currentLeaseId = current.leaseId,
                        leaseOwnerId = leaseOwnerId
                    )
                ) {
                    return acquireExistingClaim(
                        context = context,
                        database = database,
                        current = current,
                        nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = leaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim,
                        retryCount = retryCount,
                        preservesExistingReference = !reference.isNullOrBlank()
                    )
                }
                if (artifactState == ManagedDownloadArtifactState.FINALIZED) {
                    if (
                        !reference.isNullOrBlank() &&
                            referenceState == ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED
                    ) {
                        return ManagedDownloadArtifactClaim.RepairRequired(
                            current.copy(
                                updatedAtMs = System.currentTimeMillis(),
                                needsReconcile = true,
                                lastErrorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                            )
                        )
                    }
                    when (
                        finalizationDisposition
                            ?: ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE
                    ) {
                        ManagedDownloadArtifactFinalizationDisposition.SETTLED -> {
                            return ManagedDownloadArtifactClaim.AlreadyDownloaded(current)
                        }

                        ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED -> {
                            return acquireExistingClaim(
                                context = context,
                                database = database,
                                current = current,
                                nowMs = nowMs,
                                rootKey = rootKey,
                                stableKey = stableKey,
                                leaseOwnerId = leaseOwnerId,
                                allowFreshTransferReclaim = allowFreshTransferReclaim,
                                retryCount = retryCount
                            )
                        }

                        ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE -> {
                            if (referenceState == ManagedDownloadArtifactReferenceState.PRESENT) {
                                return ManagedDownloadArtifactClaim.RepairRequired(
                                    current.copy(
                                        updatedAtMs = System.currentTimeMillis(),
                                        needsReconcile = true,
                                        lastErrorCode = "FINALIZATION_EVIDENCE_UNAVAILABLE"
                                    )
                                )
                            }
                        }
                    }
                }
                if (referenceState == ManagedDownloadArtifactReferenceState.PRESENT) {
                    return ManagedDownloadArtifactClaim.AlreadyDownloaded(current)
                }
                if (referenceState == ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED) {
                    return ManagedDownloadArtifactClaim.RepairRequired(
                        current.copy(
                            updatedAtMs = System.currentTimeMillis(),
                            needsReconcile = true,
                            lastErrorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                        )
                    )
                }
                val replacement = findAccessibleReplacement(
                    context = context,
                    current = current,
                    stableKey = stableKey
                )
                if (
                    replacement != null &&
                        artifactState != ManagedDownloadArtifactState.FINALIZED
                ) {
                    val refreshed = current.copy(
                        audioReference = replacement.reference,
                        audioName = replacement.name,
                        fileSize = replacement.sizeBytes,
                        updatedAtMs = System.currentTimeMillis(),
                        needsReconcile = false,
                        lastErrorCode = null
                    )
                    dao.upsert(refreshed)
                    return ManagedDownloadArtifactClaim.AlreadyDownloaded(refreshed)
                }
                if (!isMissingConfirmed(context, current, stableKey)) {
                    return ManagedDownloadArtifactClaim.RepairRequired(
                        current.copy(
                            updatedAtMs = System.currentTimeMillis(),
                            needsReconcile = true,
                            lastErrorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                        )
                    )
                }
                val repairUpdatedAtMs = System.currentTimeMillis()
                val updated = dao.markMissingIfUnchanged(
                    rootKey = rootKey,
                    stableKey = stableKey,
                    expectedState = current.state,
                    expectedUpdatedAtMs = current.updatedAtMs,
                    missingState = ManagedDownloadArtifactState.MISSING_CONFIRMED.name,
                    updatedAtMs = repairUpdatedAtMs,
                    errorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                )
                if (updated == 1) {
                    val missing = current.copy(
                        state = ManagedDownloadArtifactState.MISSING_CONFIRMED.name,
                        leaseId = null,
                        updatedAtMs = repairUpdatedAtMs,
                        needsReconcile = true,
                        lastErrorCode = "AUDIO_REFERENCE_UNAVAILABLE"
                    )
                    resolveExistingClaim(
                        context = context,
                        database = database,
                        current = missing,
                        nowMs = repairUpdatedAtMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = leaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim
                    )
                } else if (retryCount < 2) {
                    dao.find(rootKey, stableKey)?.let { winner ->
                        resolveExistingClaim(
                            context = context,
                            database = database,
                            current = winner,
                            nowMs = repairUpdatedAtMs,
                            rootKey = rootKey,
                            stableKey = stableKey,
                            leaseOwnerId = leaseOwnerId,
                            allowFreshTransferReclaim = allowFreshTransferReclaim,
                            retryCount = retryCount + 1
                        )
                    } ?: ManagedDownloadArtifactClaim.RepairRequired(current)
                } else {
                    ManagedDownloadArtifactClaim.RepairRequired(current)
                }
            }

            ManagedDownloadArtifactDecision.InFlight ->
                ManagedDownloadArtifactClaim.InFlight(current)

            ManagedDownloadArtifactDecision.RepairRequired -> {
                val artifactState = ManagedDownloadArtifactState.fromPersisted(current.state)
                val referenceState = withContext(Dispatchers.IO) {
                    classifyManagedDownloadArtifactReference(
                        ManagedDownloadReferenceLookup.inspect(context, current.audioReference)
                    )
                }
                if (
                    shouldForceFreshTransferForUser(
                        artifactState = artifactState,
                        userInitiated = allowFreshTransferReclaim,
                        currentLeaseId = current.leaseId,
                        leaseOwnerId = leaseOwnerId
                    ) || shouldReclaimUnavailableArtifactForFreshTransfer(
                        artifactState = artifactState,
                        referenceState = referenceState,
                        userInitiated = allowFreshTransferReclaim,
                        currentLeaseId = current.leaseId,
                        leaseOwnerId = leaseOwnerId
                    )
                ) {
                    acquireExistingClaim(
                        context = context,
                        database = database,
                        current = current,
                        nowMs = nowMs,
                        rootKey = rootKey,
                        stableKey = stableKey,
                        leaseOwnerId = leaseOwnerId,
                        allowFreshTransferReclaim = allowFreshTransferReclaim,
                        retryCount = retryCount,
                        preservesExistingReference = !current.audioReference.isNullOrBlank()
                    )
                } else {
                    ManagedDownloadArtifactClaim.RepairRequired(current)
                }
            }

            ManagedDownloadArtifactDecision.Acquire -> acquireExistingClaim(
                context = context,
                database = database,
                current = current,
                nowMs = nowMs,
                rootKey = rootKey,
                stableKey = stableKey,
                leaseOwnerId = leaseOwnerId,
                allowFreshTransferReclaim = allowFreshTransferReclaim,
                retryCount = retryCount
            )
        }
    }

    private suspend fun acquireExistingClaim(
        context: Context,
        database: NeriUserDataDatabase,
        current: ManagedDownloadArtifactEntity,
        nowMs: Long,
        rootKey: String,
        stableKey: String,
        leaseOwnerId: String?,
        allowFreshTransferReclaim: Boolean,
        retryCount: Int,
        preservesExistingReference: Boolean = false
    ): ManagedDownloadArtifactClaim {
        val dao = database.managedDownloadArtifactDao()
        val acquired = newLeaseArtifact(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = current.artifactId,
            previous = current,
            nowMs = nowMs,
            leaseOwnerId = leaseOwnerId
        )
        val updated = dao.tryAcquire(
            rootKey = rootKey,
            stableKey = stableKey,
            expectedState = current.state,
            expectedUpdatedAtMs = current.updatedAtMs,
            state = acquired.state,
            leaseId = acquired.leaseId.orEmpty(),
            updatedAtMs = nowMs
        )
        if (updated == 1) {
            return ManagedDownloadArtifactClaim.Acquired(
                artifact = acquired,
                preservesExistingReference = preservesExistingReference
            )
        }
        if (retryCount >= 2) {
            return dao.find(rootKey, stableKey)
                ?.let { winner -> ManagedDownloadArtifactClaim.InFlight(winner) }
                ?: unavailableClaim(acquired)
        }
        return dao.find(rootKey, stableKey)
            ?.let { winner ->
                resolveExistingClaim(
                    context = context,
                    database = database,
                    current = winner,
                    nowMs = nowMs,
                    rootKey = rootKey,
                    stableKey = stableKey,
                    leaseOwnerId = leaseOwnerId,
                    allowFreshTransferReclaim = allowFreshTransferReclaim,
                    retryCount = retryCount + 1
                )
            }
            ?: unavailableClaim(acquired)
    }

    private suspend fun discoverExistingAudio(
        context: Context,
        song: SongItem
    ): DiscoveredAudio? {
        val snapshot = loadDiscoverySnapshot(context) ?: return null
        return discoverExistingAudio(snapshot, song)
    }

    private suspend fun findAccessibleReplacement(
        context: Context,
        current: ManagedDownloadArtifactEntity,
        stableKey: String
    ): ManagedDownloadStorage.StoredEntry? {
        val snapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true,
                includeMetadataLessAudioForLegacyUpgrade = true
            )
        }.getOrNull() ?: return null
        if (!snapshot.rootEntriesComplete) return null
        val candidates = (
            snapshot.audioEntriesByStableKey[stableKey].orEmpty() +
                artifactReconciliationAudioEntries(snapshot)
        ).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        return candidates.firstOrNull { entry ->
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, entry)
                ?.stableKey
                ?.trim() == stableKey
        } ?: current.audioName?.let { audioName ->
            candidates.firstOrNull { entry ->
                entry.name == audioName || entry.logicalName == audioName
            }
        }
    }

    private suspend fun isMissingConfirmed(
        context: Context,
        current: ManagedDownloadArtifactEntity,
        stableKey: String
    ): Boolean {
        val snapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true,
                includeMetadataLessAudioForLegacyUpgrade = true
            )
        }.getOrNull() ?: return false
        if (!snapshot.rootEntriesComplete) return false
        val candidates = (
            snapshot.audioEntriesByStableKey[stableKey].orEmpty() +
                artifactReconciliationAudioEntries(snapshot)
        ).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        val matchingByIdentity = candidates.filter { entry ->
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, entry)
                ?.stableKey
                ?.trim() == stableKey
        }
        if (matchingByIdentity.isNotEmpty()) return false
        val matchingByReference = candidates.any { entry ->
            entry.reference == current.audioReference ||
                entry.name == current.audioName ||
                entry.logicalName == current.audioName
        }
        if (matchingByReference) return false
        val evidence = ManagedDownloadReferenceLookup.inspect(
            context = context,
            reference = current.audioReference
        )
        return ManagedDownloadReferenceLookup.canMarkMissing(evidence)
    }

    private suspend fun loadDiscoverySnapshot(
        context: Context
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        return runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true,
                includeMetadataLessAudioForLegacyUpgrade = true
            )
        }.getOrNull()
            ?.takeIf { snapshot -> snapshot.rootEntriesComplete }
    }

    private suspend fun loadLiveFinalizationSnapshot(
        context: Context
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        return runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true,
                includeMetadataLessAudioForLegacyUpgrade = true
            )
        }.getOrNull()?.takeIf { snapshot -> snapshot.rootEntriesComplete }
    }

    private suspend fun inspectFinalizedArtifactCompletion(
        context: Context,
        current: ManagedDownloadArtifactEntity,
        stableKey: String
    ): ManagedDownloadArtifactFinalizationDisposition {
        return finalizedArtifactCompletionDisposition(
            snapshot = loadLiveFinalizationSnapshot(context),
            artifact = current,
            stableKey = stableKey
        )
    }

    private fun finalizedArtifactCompletionDisposition(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        artifact: ManagedDownloadArtifactEntity,
        stableKey: String
    ): ManagedDownloadArtifactFinalizationDisposition {
        val candidates = snapshot
            ?.takeIf { currentSnapshot -> currentSnapshot.rootEntriesComplete }
            ?.let { currentSnapshot ->
                liveArtifactAudioCandidates(
                    snapshot = currentSnapshot,
                    artifact = artifact,
                    stableKey = stableKey
                )
            }
            .orEmpty()
        val metadata = candidates.map { audio ->
            snapshot?.metadataByAudioName?.get(audio.name)
                ?: snapshot?.metadataByAudioName?.get(audio.logicalName)
        }
        val matchingMetadata = metadata.filter { entry ->
            entry?.stableKey?.trim() == stableKey
        }
        val metadataIdentity = when {
            matchingMetadata.isNotEmpty() ->
                ManagedDownloadArtifactMetadataIdentity.MATCHING

            metadata.any { entry -> entry == null } ->
                ManagedDownloadArtifactMetadataIdentity.MISSING

            metadata.isNotEmpty() ->
                ManagedDownloadArtifactMetadataIdentity.MISMATCHED

            else ->
                ManagedDownloadArtifactMetadataIdentity.MISSING
        }
        val hasStrictCompletion = candidates.any { audio ->
            val entry = snapshot?.metadataByAudioName?.get(audio.name)
                ?: snapshot?.metadataByAudioName?.get(audio.logicalName)
            entry?.takeIf { it.stableKey?.trim() == stableKey }?.let {
                isFinalizedDownloadedAudioEntry(
                    rootEntriesComplete = snapshot?.rootEntriesComplete == true,
                    isPendingAudioWrite = audio.isPendingAudioWrite,
                    metadata = it
                )
            } == true
        }
        return resolveFinalizedArtifactCompletionDisposition(
            artifactState = ManagedDownloadArtifactState.fromPersisted(artifact.state),
            snapshotIsComplete = snapshot?.rootEntriesComplete == true,
            matchingAudioFound = candidates.isNotEmpty(),
            metadataIdentity = metadataIdentity,
            metadataHasStrictCompletion = hasStrictCompletion
        )
    }

    private fun liveArtifactAudioCandidates(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        artifact: ManagedDownloadArtifactEntity,
        stableKey: String
    ): List<ManagedDownloadStorage.StoredEntry> {
        val references = listOfNotNull(
            artifact.audioReference?.trim()?.takeIf(String::isNotBlank),
            artifact.audioName?.trim()?.takeIf(String::isNotBlank)
        ).toSet()
        val candidates = (
            snapshot.audioEntriesByStableKey[stableKey].orEmpty() +
                artifactReconciliationAudioEntries(snapshot)
        ).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        return candidates.filter { entry ->
            val metadataStableKey = ManagedDownloadStorage
                .metadataForAudioEntry(snapshot, entry)
                ?.stableKey
                ?.trim()
            entry in snapshot.audioEntriesByStableKey[stableKey].orEmpty() ||
                metadataStableKey == stableKey ||
                entry.reference in references ||
                entry.mediaUri in references ||
                entry.localFilePath in references ||
                entry.name in references ||
                entry.logicalName in references
        }
    }

    private fun discoverExistingAudio(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem
    ): DiscoveredAudio? {
        val audio = ManagedDownloadStorage.findDownloadedAudioIncludingMetadataLess(snapshot, song)
            ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
            ?: return null
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
        val finalized = isFinalizedDownloadedAudioEntry(
            rootEntriesComplete = snapshot.rootEntriesComplete,
            isPendingAudioWrite = audio.isPendingAudioWrite,
            metadata = metadata
        )
        return DiscoveredAudio(
            reference = audio.reference,
            name = audio.name,
            sizeBytes = audio.sizeBytes,
            artifactState = resolveDiscoveredManagedArtifactState(
                finalized = finalized,
                metadataArtifactState = metadata?.artifactState
            ),
            downloadedAtMs = metadata?.downloadTimeMs
                ?: metadata?.createdAtMs
                ?: audio.lastModifiedMs.takeIf { it > 0L },
            libraryAddedAtMs = metadata?.libraryAddedAtMs,
            sourceCreatedAtMs = metadata?.sourceCreatedAtMs,
            sourceModifiedAtMs = metadata?.sourceModifiedAtMs
        )
    }

    private fun newDiscoveredArtifact(
        rootKey: String,
        stableKey: String,
        discovered: DiscoveredAudio,
        nowMs: Long
    ): ManagedDownloadArtifactEntity {
        val state = discovered.artifactState
        return ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = artifactId(rootKey, stableKey),
            state = state.name,
            leaseId = null,
            audioReference = discovered.reference,
            audioName = discovered.name,
            fileSize = discovered.sizeBytes,
            contentHash = null,
            libraryAddedAtMs = discovered.libraryAddedAtMs
                ?: discovered.downloadedAtMs,
            sourceCreatedAtMs = discovered.sourceCreatedAtMs,
            sourceModifiedAtMs = discovered.sourceModifiedAtMs,
            downloadedAtMs = discovered.downloadedAtMs
                ?: nowMs.takeIf { state == ManagedDownloadArtifactState.FINALIZED },
            migratedAtMs = null,
            finalizedAtMs = discovered.downloadedAtMs
                ?.takeIf { state == ManagedDownloadArtifactState.FINALIZED },
            updatedAtMs = nowMs,
            needsReconcile = state != ManagedDownloadArtifactState.FINALIZED,
            lastErrorCode = if (discovered.downloadedAtMs == null) {
                "LEGACY_FALLBACK"
            } else {
                null
            }
        )
    }

    private fun catalogArtifact(
        rootKey: String,
        stableKey: String,
        current: ManagedDownloadArtifactEntity?,
        song: DownloadedSong,
        nowMs: Long
    ): ManagedDownloadArtifactEntity {
        val audioReference = song.filePath
            .trim()
            .takeIf(String::isNotBlank)
            ?: song.mediaUri?.trim()?.takeIf(String::isNotBlank)
        val finalized = audioReference != null
        val currentState = current?.let { artifact ->
            ManagedDownloadArtifactState.fromPersisted(artifact.state)
        }
        val preservedState = currentState?.takeIf { state ->
            state in setOf(
                ManagedDownloadArtifactState.CORE_COMMITTED,
                ManagedDownloadArtifactState.ASSETS_ENRICHING,
                ManagedDownloadArtifactState.DEGRADED_COMPLETE
            )
        }
        val nextState = resolveCatalogArtifactState(
            currentState = currentState,
            hasAudioReference = finalized
        )
        val audioName = audioReference
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf(String::isNotBlank)
        return (current ?: ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = artifactId(rootKey, stableKey),
            state = nextState.name,
            leaseId = null,
            audioReference = null,
            audioName = null,
            fileSize = null,
            contentHash = null,
            libraryAddedAtMs = null,
            sourceCreatedAtMs = null,
            sourceModifiedAtMs = null,
            downloadedAtMs = null,
            migratedAtMs = null,
            finalizedAtMs = null,
            updatedAtMs = nowMs,
            needsReconcile = nextState != ManagedDownloadArtifactState.FINALIZED,
            lastErrorCode = "AUDIO_REFERENCE_MISSING".takeIf {
                nextState == ManagedDownloadArtifactState.MISSING_CONFIRMED
            }
        )).copy(
            state = nextState.name,
            leaseId = if (preservedState != null) current.leaseId else null,
            audioReference = audioReference,
            audioName = audioName,
            fileSize = song.fileSize,
            downloadedAtMs = song.downloadTime.takeIf { finalized },
            finalizedAtMs = if (nextState == ManagedDownloadArtifactState.FINALIZED) {
                current?.finalizedAtMs ?: nowMs
            } else {
                current?.finalizedAtMs
            },
            updatedAtMs = nowMs,
            needsReconcile = nextState != ManagedDownloadArtifactState.FINALIZED,
            lastErrorCode = "AUDIO_REFERENCE_MISSING".takeIf {
                nextState == ManagedDownloadArtifactState.MISSING_CONFIRMED
            }
        )
    }

    private suspend fun updateState(
        context: Context,
        song: SongItem,
        expectedLeaseId: String?,
        state: ManagedDownloadArtifactState,
        clearLease: Boolean,
        errorCode: String? = null
    ) {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        database.withTransaction {
            val dao = database.managedDownloadArtifactDao()
            val current = dao.find(rootKey, stableKey)
                ?: expectedLeaseId?.let { leaseId ->
                    dao.findAllByStableKey(stableKey)
                        .firstOrNull { artifact -> artifact.leaseId == leaseId }
                }
                ?: return@withTransaction
            if (!matchesLease(current, expectedLeaseId)) {
                return@withTransaction
            }
            val nextState = resolveArtifactStateUpdate(
                current = ManagedDownloadArtifactState.fromPersisted(current.state),
                requested = state
            )
            val currentState = ManagedDownloadArtifactState.fromPersisted(current.state)
            val releaseLeaseAfterMonotonicUpdate =
                shouldReleaseLeaseAfterMonotonicArtifactUpdate(
                    current = currentState,
                    requested = state,
                    clearLease = clearLease
                )
            if (nextState == currentState && nextState != state &&
                !releaseLeaseAfterMonotonicUpdate
            ) {
                return@withTransaction
            }
            dao.upsert(
                current.copy(
                    state = nextState.name,
                    leaseId = if (clearLease || releaseLeaseAfterMonotonicUpdate) {
                        null
                    } else {
                        current.leaseId
                    },
                    updatedAtMs = nowMs,
                    needsReconcile = nextState != ManagedDownloadArtifactState.FINALIZED,
                    lastErrorCode = errorCode ?: if (releaseLeaseAfterMonotonicUpdate) {
                        "CANCELLED_AFTER_CORE_COMMIT"
                    } else {
                        null
                    }
                )
            )
        }
    }

    private suspend fun updateStateWithoutLease(
        context: Context,
        song: SongItem,
        state: ManagedDownloadArtifactState,
        errorCode: String?
    ) {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        val dao = database(context.applicationContext).managedDownloadArtifactDao()
        val current = dao.find(rootKey, stableKey) ?: return
        if (!canApplyLeaseFreeArtifactTransition(
                currentState = ManagedDownloadArtifactState.fromPersisted(current.state),
                currentLeaseId = current.leaseId,
                requestedState = state
            )
        ) {
            return
        }
        dao.updateLeaseFreeIfUnchanged(
            rootKey = rootKey,
            stableKey = stableKey,
            expectedState = current.state,
            expectedUpdatedAtMs = current.updatedAtMs,
            state = state.name,
            updatedAtMs = System.currentTimeMillis(),
            needsReconcile = state in setOf(
                ManagedDownloadArtifactState.DEGRADED_COMPLETE,
                ManagedDownloadArtifactState.MISSING_CONFIRMED,
                ManagedDownloadArtifactState.REPAIR_REQUIRED
            ),
            errorCode = errorCode
        )
    }

    private fun matchesLease(
        current: ManagedDownloadArtifactEntity,
        expectedLeaseId: String?
    ): Boolean {
        return matchesManagedDownloadArtifactLease(
            currentLeaseId = current.leaseId,
            expectedLeaseId = expectedLeaseId
        )
    }

    private fun isActive(entity: ManagedDownloadArtifactEntity): Boolean {
        return ManagedDownloadArtifactState.fromPersisted(entity.state) in setOf(
            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.WAITING_STORAGE,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING
        )
    }

    private fun isCrossRootAuthoritative(entity: ManagedDownloadArtifactEntity): Boolean {
        val state = ManagedDownloadArtifactState.fromPersisted(entity.state)
        return state in CROSS_ROOT_AUTHORITATIVE_STATES ||
            entity.leaseId != null && state in CROSS_ROOT_LEASE_STATES
    }

    private fun crossRootArtifactPriority(entity: ManagedDownloadArtifactEntity): Int {
        return when (ManagedDownloadArtifactState.fromPersisted(entity.state)) {
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.WAITING_STORAGE -> 4

            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.FINALIZED -> 3

            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            ManagedDownloadArtifactState.MISSING_CONFIRMED -> 2

            ManagedDownloadArtifactState.FAILED_RETRYABLE,
            ManagedDownloadArtifactState.CANCELLED -> 1
        }
    }

    private fun newLeaseArtifact(
        rootKey: String,
        stableKey: String,
        artifactId: String,
        previous: ManagedDownloadArtifactEntity?,
        nowMs: Long,
        leaseOwnerId: String? = null
    ): ManagedDownloadArtifactEntity {
        return (previous ?: ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = artifactId,
            state = ManagedDownloadArtifactState.DOWNLOADING.name,
            leaseId = null,
            audioReference = null,
            audioName = null,
            fileSize = null,
            contentHash = null,
            libraryAddedAtMs = null,
            sourceCreatedAtMs = null,
            sourceModifiedAtMs = null,
            downloadedAtMs = null,
            migratedAtMs = null,
            finalizedAtMs = null,
            updatedAtMs = nowMs,
            needsReconcile = true,
            lastErrorCode = null
        )).copy(
            artifactId = artifactId,
            state = ManagedDownloadArtifactState.DOWNLOADING.name,
            leaseId = leaseOwnerId ?: UUID.randomUUID().toString(),
            audioReference = previous?.audioReference,
            audioName = previous?.audioName,
            updatedAtMs = nowMs,
            needsReconcile = true,
            lastErrorCode = null
        )
    }

    private fun artifactId(rootKey: String, stableKey: String): String {
        return "managed:$rootKey:$stableKey"
    }

    private companion object {
        private const val BATCH_ARTIFACT_QUERY_CHUNK_SIZE = 900
        private const val BATCH_ARTIFACT_INSERT_CHUNK_SIZE = 128
        private val CROSS_ROOT_AUTHORITATIVE_STATES = setOf(
            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.WAITING_STORAGE,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.FINALIZED
        )
        private val CROSS_ROOT_LEASE_STATES = setOf(
            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.WAITING_STORAGE,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING
        )
    }

    private fun database(context: Context): NeriUserDataDatabase {
        return NeriUserDataDatabase.getInstance(context.applicationContext)
    }

    private fun ManagedDownloadArtifactEntity.toClaim(
        nowMs: Long = System.currentTimeMillis()
    ): ManagedDownloadArtifactClaim {
        return when (ManagedDownloadArtifactPolicy.decide(this, nowMs)) {
            ManagedDownloadArtifactDecision.RepairRequired ->
                ManagedDownloadArtifactClaim.RepairRequired(this)

            ManagedDownloadArtifactDecision.InFlight ->
                ManagedDownloadArtifactClaim.InFlight(this)

            ManagedDownloadArtifactDecision.Acquire ->
                ManagedDownloadArtifactClaim.Acquired(this)

            ManagedDownloadArtifactDecision.AlreadyDownloaded ->
                ManagedDownloadArtifactClaim.AlreadyDownloaded(this)
        }
    }

    private fun createUntrackedClaim(
        leaseOwnerId: String?
    ): ManagedDownloadArtifactClaim {
        return ManagedDownloadArtifactClaim.Acquired(
            ManagedDownloadArtifactEntity(
                rootKey = "untracked",
                stableKey = "untracked",
                artifactId = "untracked",
                state = ManagedDownloadArtifactState.DOWNLOADING.name,
                leaseId = leaseOwnerId ?: UUID.randomUUID().toString(),
                audioReference = null,
                audioName = null,
                fileSize = null,
                contentHash = null,
                libraryAddedAtMs = null,
                sourceCreatedAtMs = null,
                sourceModifiedAtMs = null,
                downloadedAtMs = null,
                migratedAtMs = null,
                finalizedAtMs = null,
                updatedAtMs = System.currentTimeMillis(),
                needsReconcile = true,
                lastErrorCode = null
            )
        )
    }

    private fun unavailableClaim(
        candidate: ManagedDownloadArtifactEntity
    ): ManagedDownloadArtifactClaim {
        return ManagedDownloadArtifactClaim.InFlight(
            candidate.copy(
                state = ManagedDownloadArtifactState.QUEUED.name,
                leaseId = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private data class DiscoveredAudio(
        val reference: String,
        val name: String,
        val sizeBytes: Long,
        val artifactState: ManagedDownloadArtifactState,
        val downloadedAtMs: Long?,
        val libraryAddedAtMs: Long?,
        val sourceCreatedAtMs: Long?,
        val sourceModifiedAtMs: Long?
    )
}

internal fun artifactReconciliationAudioEntries(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
): List<ManagedDownloadStorage.StoredEntry> {
    return (
        snapshot.audioEntries +
            snapshot.audioEntriesWithoutMetadata +
            snapshot.pendingAudioEntries
        )
        .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
}

internal fun resolveDiscoveredManagedArtifactState(
    finalized: Boolean,
    metadataArtifactState: String?
): ManagedDownloadArtifactState {
    if (finalized) {
        return ManagedDownloadArtifactState.FINALIZED
    }
    val persistedState = metadataArtifactState
        ?.trim()
        ?.let { raw ->
            ManagedDownloadArtifactState.entries.firstOrNull { state ->
                state.name.equals(raw, ignoreCase = true)
            }
        }
    return when (persistedState) {
        ManagedDownloadArtifactState.CORE_COMMITTED ->
            ManagedDownloadArtifactState.CORE_COMMITTED
        ManagedDownloadArtifactState.ASSETS_ENRICHING ->
            ManagedDownloadArtifactState.ASSETS_ENRICHING
        ManagedDownloadArtifactState.DEGRADED_COMPLETE ->
            ManagedDownloadArtifactState.DEGRADED_COMPLETE
        else -> ManagedDownloadArtifactState.REPAIR_REQUIRED
    }
}

internal fun resolveCatalogArtifactState(
    currentState: ManagedDownloadArtifactState?,
    hasAudioReference: Boolean
): ManagedDownloadArtifactState {
    if (currentState == null) {
        return if (hasAudioReference) {
            ManagedDownloadArtifactState.FINALIZED
        } else {
            ManagedDownloadArtifactState.MISSING_CONFIRMED
        }
    }
    return if (
        currentState == ManagedDownloadArtifactState.FINALIZED &&
            !hasAudioReference
    ) {
        ManagedDownloadArtifactState.MISSING_CONFIRMED
    } else {
        currentState
    }
}
