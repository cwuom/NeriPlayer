package moe.ouom.neriplayer.core.download.artifact

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
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
        return songs.filter { song ->
            val stableKey = song.stableKey().trim()
            val artifact = artifactsByStableKey[stableKey]
            artifact == null ||
                ManagedDownloadArtifactState.fromPersisted(artifact.state) !in setOf(
                    ManagedDownloadArtifactState.CORE_COMMITTED,
                    ManagedDownloadArtifactState.ASSETS_ENRICHING,
                    ManagedDownloadArtifactState.FINALIZED,
                    ManagedDownloadArtifactState.DEGRADED_COMPLETE
                )
        }
    }

    suspend fun currentLeaseId(
        context: Context,
        song: SongItem
    ): String? {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return null
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context.applicationContext)
        return database(context.applicationContext).managedDownloadArtifactDao()
            .find(rootKey, stableKey)
            ?.leaseId
    }

    suspend fun markCoreCommitted(
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
        }
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
                val reference = current.audioReference
                val referenceState = withContext(Dispatchers.IO) {
                    classifyManagedDownloadArtifactReference(
                        ManagedDownloadReferenceLookup.inspect(context, reference)
                    )
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
                if (replacement != null) {
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

            ManagedDownloadArtifactDecision.Acquire -> {
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
                    ManagedDownloadArtifactClaim.Acquired(acquired)
                } else if (retryCount >= 2) {
                    dao.find(rootKey, stableKey)
                        ?.let { winner -> ManagedDownloadArtifactClaim.InFlight(winner) }
                        ?: unavailableClaim(acquired)
                } else {
                    dao.find(rootKey, stableKey)
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
            }
        }
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
        return snapshot.audioEntriesByStableKey[stableKey]
            .orEmpty()
            .firstOrNull()
            ?: current.audioName?.let { audioName ->
                snapshot.audioEntries.firstOrNull { entry -> entry.name == audioName }
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
        val matchingByIdentity = snapshot.audioEntriesByStableKey[stableKey].orEmpty()
        if (matchingByIdentity.isNotEmpty()) return false
        val matchingByReference = snapshot.audioEntries.any { entry ->
            entry.reference == current.audioReference || entry.name == current.audioName
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

    private fun discoverExistingAudio(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem
    ): DiscoveredAudio? {
        val audio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song) ?: return null
        val metadata = snapshot.metadataByAudioName[audio.name]
        return DiscoveredAudio(
            reference = audio.reference,
            name = audio.name,
            sizeBytes = audio.sizeBytes,
            finalized = metadata?.downloadFinalized == true,
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
        return ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = artifactId(rootKey, stableKey),
            state = if (discovered.finalized) {
                ManagedDownloadArtifactState.FINALIZED.name
            } else {
                ManagedDownloadArtifactState.REPAIR_REQUIRED.name
            },
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
                ?: nowMs.takeIf { discovered.finalized },
            migratedAtMs = null,
            finalizedAtMs = discovered.downloadedAtMs
                ?.takeIf { discovered.finalized },
            updatedAtMs = nowMs,
            needsReconcile = !discovered.finalized,
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
        val nextState = preservedState ?: if (finalized) {
            ManagedDownloadArtifactState.FINALIZED
        } else {
            ManagedDownloadArtifactState.MISSING_CONFIRMED
        }
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
        val finalized: Boolean,
        val downloadedAtMs: Long?,
        val libraryAddedAtMs: Long?,
        val sourceCreatedAtMs: Long?,
        val sourceModifiedAtMs: Long?
    )
}
