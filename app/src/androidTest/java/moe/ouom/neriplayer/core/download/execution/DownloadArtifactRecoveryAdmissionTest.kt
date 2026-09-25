package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.manager.recovery.claimArtifactForRecovery
import moe.ouom.neriplayer.core.download.policy.finalizedPublicationRecoveryLeaseOwnerId
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadArtifactRecoveryAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = SongItem(
        id = 991L, name = "recovery-admission", artist = "artist",
        album = "Netease", albumId = 1L, durationMs = 180_000L, coverUrl = null
    )

    @Test
    fun finalizedPublicationRecoveryLeaseRebindSurvivesReopenAndRejectsStaleOwner() = runTest {
        val name = "download-recovery-lease-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val request = DownloadExecutionRequest(
                operationId = "finalized-publication-recovery",
                song = song,
                artifactLeaseId = "original-download-lease",
                attemptId = 17L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "CORE_COMMITTED",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.rebindArtifactLeaseForRecovery(
                    context = context,
                    operationId = request.operationId,
                    stableKey = song.stableKey(),
                    expectedArtifactLeaseId = request.artifactLeaseId,
                    recoveryArtifactLeaseId = "finalized-recovery-lease",
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.rebindArtifactLeaseForRecovery(
                    context = context,
                    operationId = request.operationId,
                    stableKey = song.stableKey(),
                    expectedArtifactLeaseId = request.artifactLeaseId,
                    recoveryArtifactLeaseId = "stale-recovery-lease",
                    database = database
                )
            )

            database.close()
            database = open()
            val recovered = requireNotNull(
                DownloadExecutionRoomStore.read(context, request.operationId, database)
            )
            assertEquals("finalized-recovery-lease", recovered.artifactLeaseId)
            assertEquals(request.attemptId, recovered.attemptId)
            assertEquals(
                "CORE_COMMITTED",
                database.downloadOperationDao().findHeader(request.operationId)?.state
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun finalizedPublicationRecoveryLeaseRebindAcceptsRetryableCommitRecovery() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "retryable-finalized-publication",
                song = song,
                artifactLeaseId = "pre-recovery-lease",
                attemptId = 19L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "RETRYABLE",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.rebindArtifactLeaseForRecovery(
                    context = context,
                    operationId = request.operationId,
                    stableKey = song.stableKey(),
                    expectedArtifactLeaseId = request.artifactLeaseId,
                    recoveryArtifactLeaseId = "retryable-recovery-lease",
                    database = database
                )
            )
            val recovered = requireNotNull(
                DownloadExecutionRoomStore.read(context, request.operationId, database)
            )
            assertEquals("retryable-recovery-lease", recovered.artifactLeaseId)
            assertEquals(request.attemptId, recovered.attemptId)
            assertEquals(
                "RETRYABLE",
                database.downloadOperationDao().findHeader(request.operationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun recoveryClaimAtomicallyRebindsArtifactAndDurableOperation() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "atomic-recovery-claim",
                song = song,
                artifactLeaseId = "original-atomic-lease",
                attemptId = 23L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "CORE_COMMITTED",
                database = database
            )
            val header = requireNotNull(
                database.downloadOperationDao().findHeader(request.operationId)
            )
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = header.libraryId,
                    leaseId = request.artifactLeaseId
                )
            )

            val claimed = GlobalDownloadManager.claimArtifactForRecovery(
                context = context,
                song = song,
                operationId = request.operationId,
                database = database
            )

            val recoveryLeaseId = finalizedPublicationRecoveryLeaseOwnerId(
                stableKey = song.stableKey(),
                operationId = request.operationId
            )
            assertNotNull(claimed)
            assertEquals(recoveryLeaseId, claimed?.leaseOwnerId)
            assertEquals(
                recoveryLeaseId,
                DownloadExecutionRoomStore.read(context, request.operationId, database)
                    ?.artifactLeaseId
            )
            assertEquals(
                recoveryLeaseId,
                database.managedDownloadArtifactDao()
                    .find(header.libraryId, song.stableKey())
                    ?.leaseId
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun recoveryClaimRollsBackArtifactWhenOperationCannotRebind() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "atomic-recovery-rollback",
                song = song,
                artifactLeaseId = "rollback-original-lease",
                attemptId = 29L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "RUNNING",
                database = database
            )
            val header = requireNotNull(
                database.downloadOperationDao().findHeader(request.operationId)
            )
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = header.libraryId,
                    leaseId = request.artifactLeaseId
                )
            )

            assertNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = request.operationId,
                    database = database
                )
            )
            assertEquals(
                request.artifactLeaseId,
                DownloadExecutionRoomStore.read(context, request.operationId, database)
                    ?.artifactLeaseId
            )
            val artifact = requireNotNull(
                database.managedDownloadArtifactDao().find(header.libraryId, song.stableKey())
            )
            assertEquals(request.artifactLeaseId, artifact.leaseId)
            assertEquals(ManagedDownloadArtifactState.CORE_COMMITTED.name, artifact.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun orphanRecoveryClaimRejectsForeignArtifactOwner() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val audio = File(context.cacheDir, "foreign-recovery-owner-${UUID.randomUUID()}.mp3")
        try {
            audio.writeBytes(byteArrayOf(1, 2, 3, 4))
            val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = rootKey,
                    leaseId = "foreign-operation-owner",
                    audioReference = audio.absolutePath
                )
            )

            assertNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = null,
                    database = database
                )
            )
            val artifact = requireNotNull(
                database.managedDownloadArtifactDao().find(rootKey, song.stableKey())
            )
            assertEquals("foreign-operation-owner", artifact.leaseId)
            assertEquals(ManagedDownloadArtifactState.CORE_COMMITTED.name, artifact.state)
        } finally {
            audio.delete()
            database.close()
        }
    }

    @Test
    fun orphanRecoveryClaimRejectsStaleForeignTransferOwner() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = rootKey,
                    leaseId = "stale-foreign-transfer-owner",
                    audioReference = null,
                    state = ManagedDownloadArtifactState.DOWNLOADING
                )
            )

            assertNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = null,
                    database = database
                )
            )
            val artifact = requireNotNull(
                database.managedDownloadArtifactDao().find(rootKey, song.stableKey())
            )
            assertEquals("stale-foreign-transfer-owner", artifact.leaseId)
            assertEquals(ManagedDownloadArtifactState.DOWNLOADING.name, artifact.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun requestRecoveryClaimRejectsForeignRepairOwnerWithoutReference() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "foreign-repair-owner",
                song = song,
                artifactLeaseId = "request-repair-owner",
                attemptId = 30L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "CORE_COMMITTED",
                database = database
            )
            val header = requireNotNull(
                database.downloadOperationDao().findHeader(request.operationId)
            )
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = header.libraryId,
                    leaseId = "different-repair-owner",
                    audioReference = null,
                    state = ManagedDownloadArtifactState.REPAIR_REQUIRED
                )
            )

            assertNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = request.operationId,
                    database = database
                )
            )
            assertEquals(
                request.artifactLeaseId,
                DownloadExecutionRoomStore.read(context, request.operationId, database)
                    ?.artifactLeaseId
            )
            val artifact = requireNotNull(
                database.managedDownloadArtifactDao()
                    .find(header.libraryId, song.stableKey())
            )
            assertEquals("different-repair-owner", artifact.leaseId)
            assertEquals(ManagedDownloadArtifactState.REPAIR_REQUIRED.name, artifact.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun ownerlessFinalizedArtifactAcquiresRecoveryLeaseBeforePublication() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "ownerless-finalized-artifact",
                song = song,
                artifactLeaseId = "ownerless-operation-lease",
                attemptId = 31L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "CORE_COMMITTED",
                database = database
            )
            val header = requireNotNull(
                database.downloadOperationDao().findHeader(request.operationId)
            )
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = header.libraryId,
                    leaseId = null,
                    state = ManagedDownloadArtifactState.FINALIZED
                )
            )

            val claimed = GlobalDownloadManager.claimArtifactForRecovery(
                context = context,
                song = song,
                operationId = request.operationId,
                database = database
            )
            val recoveryLeaseId = finalizedPublicationRecoveryLeaseOwnerId(
                stableKey = song.stableKey(),
                operationId = request.operationId
            )

            assertNotNull(claimed)
            assertEquals(
                recoveryLeaseId,
                DownloadExecutionRoomStore.read(context, request.operationId, database)
                    ?.artifactLeaseId
            )
            assertEquals(
                recoveryLeaseId,
                database.managedDownloadArtifactDao()
                    .find(header.libraryId, song.stableKey())
                    ?.leaseId
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun recoveryClaimSurvivesDatabaseReopenWithDeterministicOwner() = runTest {
        val name = "download-atomic-recovery-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val request = DownloadExecutionRequest(
                operationId = "reopen-atomic-recovery",
                song = song,
                artifactLeaseId = "reopen-original-owner",
                attemptId = 37L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "RETRYABLE",
                database = database
            )
            val header = requireNotNull(
                database.downloadOperationDao().findHeader(request.operationId)
            )
            database.managedDownloadArtifactDao().upsert(
                recoveryArtifact(
                    rootKey = header.libraryId,
                    leaseId = request.artifactLeaseId
                )
            )

            assertNotNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = request.operationId,
                    database = database
                )
            )
            val recoveryLeaseId = finalizedPublicationRecoveryLeaseOwnerId(
                stableKey = song.stableKey(),
                operationId = request.operationId
            )

            database.close()
            database = open()
            assertNotNull(
                GlobalDownloadManager.claimArtifactForRecovery(
                    context = context,
                    song = song,
                    operationId = request.operationId,
                    database = database
                )
            )
            assertEquals(
                recoveryLeaseId,
                DownloadExecutionRoomStore.read(context, request.operationId, database)
                    ?.artifactLeaseId
            )
            assertEquals(
                recoveryLeaseId,
                database.managedDownloadArtifactDao()
                    .find(header.libraryId, song.stableKey())
                    ?.leaseId
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun clearedCoreCancellationSurvivesDatabaseReopenAndAllowsNewRequest() = runTest {
        val name = "download-recovery-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val old = DownloadExecutionRequest(operationId = "old-core", song = song)
            DownloadExecutionRoomStore.upsert(context, old, "CORE_COMMITTED", database = database)
            assertTrue(DownloadExecutionRoomStore.requestCancel(context, old.operationId, database))
            assertEquals(0, DownloadExecutionRoomStore.purgeFullyClearedOperations(
                context, listOf(old.operationId), database
            ))
            assertNotNull(database.downloadOperationDao().findHeader(old.operationId))
            database.close()
            database = open()
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, old.operationId, database = database
            ))
            val replacement = DownloadExecutionRequest(operationId = "new-core", song = song)
            DownloadExecutionRoomStore.upsert(context, replacement, "CORE_COMMITTED", database = database)
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, replacement.operationId, database = database
            ))
            val progress = DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(context, database)
            assertTrue(progress.any { it.request.operationId == replacement.operationId })
            assertTrue(progress.first { it.request.operationId == old.operationId }.stopRequestedByUser)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cancelledBatchBlocksLegacyOrphanAfterOperationWasDeleted() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(
                context, listOf(song), database = database, nowMs = 1L
            )
            val request = DownloadExecutionRequest(
                operationId = "legacy-orphan", song = song, attemptId = 1L,
                batchId = batch.batchId, batchGeneration = batch.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, listOf(request), database = database
            ))
            val capture = DownloadExecutionRoomStore.beginBatchClear(context, 1L, database)
            database.downloadOperationDao().delete(request.operationId)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
            assertTrue(DownloadExecutionRoomStore.finalizeBatchClear(
                context, capture.identities, database
            ))
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, "unrelated-orphan", database = database
            ))
        } finally {
            database.close()
        }
    }

    private fun recoveryArtifact(
        rootKey: String,
        leaseId: String?,
        audioReference: String? = "/unresolved/recovery-audio.mp3",
        state: ManagedDownloadArtifactState = ManagedDownloadArtifactState.CORE_COMMITTED
    ): ManagedDownloadArtifactEntity {
        return ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = song.stableKey(),
            artifactId = "managed:$rootKey:${song.stableKey()}",
            state = state.name,
            leaseId = leaseId,
            audioReference = audioReference,
            audioName = "recovery-audio.mp3",
            fileSize = 4L,
            updatedAtMs = 1L,
            needsReconcile = true
        )
    }

    @Test
    fun directoryRecoveryRespectsPersistentRetryDeadlineAndExhaustion() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(
                context, listOf(song), database = database, nowMs = 1L
            )
            val request = DownloadExecutionRequest(
                operationId = "retry-core", song = song,
                batchId = batch.batchId, batchGeneration = batch.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, listOf(request), database = database
            ))
            val retry = requireNotNull(DownloadExecutionRoomStore.recordPostCoreRetryFailure(
                context = context, operationId = request.operationId, stableKey = song.stableKey(),
                expectedAttemptId = null, errorCode = "TEST_FAILURE", database = database, nowMs = 10L
            ))
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = retry.nextRetryAtMs - 1L
            ))
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = retry.nextRetryAtMs
            ))
            assertTrue(DownloadExecutionRoomStore.markPostCoreRetryExhausted(
                context = context, operationId = request.operationId, stableKey = song.stableKey(),
                expectedAttemptId = null, minimumRetryCount = 1,
                errorCode = "POST_CORE_RETRY_EXHAUSTED", database = database
            ))
            assertEquals("INVALID", DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(
                context, database
            ).single().state)
            assertTrue(requireNotNull(database.downloadBatchDao().findBatchById(batch.batchId))
                .stateBits and DownloadBatchState.COMPLETED != 0)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = Long.MAX_VALUE
            ))
            DownloadExecutionRoomStore.purgeFullyClearedOperations(context, listOf(request.operationId), database)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
        } finally {
            database.close()
        }
    }

    @Test
    fun ordinaryCancelledTransferIsStillPurged() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(operationId = "cancelled-transfer", song = song)
            DownloadExecutionRoomStore.upsert(context, request, "CANCELLED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.purgeFullyClearedOperations(
                context, listOf(request.operationId), database
            ))
            assertTrue(database.downloadOperationDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }
}
