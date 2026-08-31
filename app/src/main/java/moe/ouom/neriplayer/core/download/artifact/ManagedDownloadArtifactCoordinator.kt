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

internal class ManagedDownloadArtifactCoordinator {
    suspend fun claim(
        context: Context,
        song: SongItem,
        reconcileStorage: Boolean = false,
        leaseOwnerId: String? = null
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
                leaseOwnerId = normalizedLeaseOwnerId
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
                        leaseOwnerId = normalizedLeaseOwnerId
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
                    leaseOwnerId = normalizedLeaseOwnerId
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
            val existingKeys = dao.findAllByRootKey(rootKey)
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
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val dao = database(appContext).managedDownloadArtifactDao()
        val artifactsByStableKey = dao.findAllByRootKey(rootKey).associateBy(
            ManagedDownloadArtifactEntity::stableKey
        )
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

    suspend fun currentState(
        context: Context,
        song: SongItem
    ): ManagedDownloadArtifactState? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        return database(context.applicationContext).managedDownloadArtifactDao()
            .find(rootKey, stableKey)
            ?.state
            ?.let(ManagedDownloadArtifactState::fromPersisted)
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
    ) {
        val appContext = context.applicationContext
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(appContext)
        val database = database(appContext)
        val nowMs = System.currentTimeMillis()
        database.withTransaction {
            val current = database.managedDownloadArtifactDao().find(rootKey, stableKey)
            if (current == null && expectedLeaseId != null) {
                return@withTransaction
            }
            if (current != null && !matchesLease(current, expectedLeaseId)) {
                return@withTransaction
            }
            val base = current ?: newLeaseArtifact(
                rootKey = rootKey,
                stableKey = stableKey,
                artifactId = artifactId(rootKey, stableKey),
                previous = null,
                nowMs = nowMs,
                leaseOwnerId = expectedLeaseId
            ).copy(leaseId = expectedLeaseId)
            database.managedDownloadArtifactDao().upsert(
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

    private suspend fun resolveExistingClaim(
        context: Context,
        database: NeriUserDataDatabase,
        current: ManagedDownloadArtifactEntity,
        nowMs: Long,
        rootKey: String,
        stableKey: String,
        leaseOwnerId: String?,
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
                        inspectFinalizedArtifactCompletion(
                            context = context,
                            current = current,
                            stableKey = stableKey
                        )
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
                        leaseOwnerId = leaseOwnerId
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
                            retryCount = retryCount + 1
                        )
                    } ?: ManagedDownloadArtifactClaim.RepairRequired(current)
                } else {
                    ManagedDownloadArtifactClaim.RepairRequired(current)
                }
            }

            ManagedDownloadArtifactDecision.InFlight ->
                ManagedDownloadArtifactClaim.InFlight(current)

            ManagedDownloadArtifactDecision.RepairRequired ->
                ManagedDownloadArtifactClaim.RepairRequired(current)

            ManagedDownloadArtifactDecision.Acquire -> acquireExistingClaim(
                context = context,
                database = database,
                current = current,
                nowMs = nowMs,
                rootKey = rootKey,
                stableKey = stableKey,
                leaseOwnerId = leaseOwnerId,
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
        retryCount: Int
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
            return ManagedDownloadArtifactClaim.Acquired(acquired)
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
                forceRefresh = true
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
                forceRefresh = true
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
        val cachedSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = true
        )?.takeIf { snapshot -> snapshot.rootEntriesComplete }
        return cachedSnapshot ?: runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }.getOrNull()
    }

    private suspend fun loadLiveFinalizationSnapshot(
        context: Context
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val cachedSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )?.takeIf { snapshot -> snapshot.rootEntriesComplete }
        return cachedSnapshot ?: runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
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
        val audio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
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
            val current = database.managedDownloadArtifactDao().find(rootKey, stableKey)
                ?: return@withTransaction
            if (!matchesLease(current, expectedLeaseId)) {
                return@withTransaction
            }
            val nextState = resolveArtifactStateUpdate(
                current = ManagedDownloadArtifactState.fromPersisted(current.state),
                requested = state
            )
            if (nextState == ManagedDownloadArtifactState.fromPersisted(current.state) &&
                nextState != state
            ) {
                return@withTransaction
            }
            database.managedDownloadArtifactDao().upsert(
                current.copy(
                    state = nextState.name,
                    leaseId = if (clearLease) null else current.leaseId,
                    updatedAtMs = nowMs,
                    needsReconcile = nextState != ManagedDownloadArtifactState.FINALIZED,
                    lastErrorCode = errorCode
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
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING
        )
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
    return (snapshot.audioEntries + snapshot.pendingAudioEntries)
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
