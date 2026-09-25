package moe.ouom.neriplayer.core.download.artifact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadArtifactLeaseIsolationTest {
    @Test
    fun staleLeaseCannotDeleteArtifactReassignedAfterTheOriginalRowWasRemoved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.managedDownloadArtifactDao()
            val stale = artifact(
                rootKey = "root-a",
                stableKey = "stable",
                leaseId = "lease-old",
                updatedAtMs = 10L
            )
            assertTrue(dao.insertIfAbsent(stale) >= 0L)
            assertEquals(
                1,
                dao.deleteIfUnchanged(
                    rootKey = stale.rootKey,
                    stableKey = stale.stableKey,
                    expectedState = stale.state,
                    expectedLeaseId = stale.leaseId,
                    expectedUpdatedAtMs = stale.updatedAtMs
                )
            )

            val reassigned = stale.copy(
                leaseId = "lease-new",
                updatedAtMs = 11L
            )
            assertTrue(dao.insertIfAbsent(reassigned) >= 0L)

            assertEquals(
                0,
                dao.deleteIfUnchanged(
                    rootKey = stale.rootKey,
                    stableKey = stale.stableKey,
                    expectedState = stale.state,
                    expectedLeaseId = stale.leaseId,
                    expectedUpdatedAtMs = stale.updatedAtMs
                )
            )
            val stored = dao.find(reassigned.rootKey, reassigned.stableKey)
            assertNotNull(stored)
            assertEquals("lease-new", stored?.leaseId)
            assertEquals(11L, stored?.updatedAtMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun operationInPreviousDirectoryDoesNotHideTheSameSongInNewDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.downloadOperationDao()
            dao.upsert(operation("operation-root-a", "root-a", "stable", 10L))
            dao.upsert(operation("operation-root-b", "root-b", "stable", 20L))

            assertEquals(
                "operation-root-b",
                dao.findLatestOperationIdByStableKey(
                    libraryId = "root-b",
                    stableKey = "stable",
                    states = listOf("QUEUED")
                )
            )
            assertEquals(
                listOf("operation-root-b"),
                dao.findAllByStableKey(
                    libraryId = "root-b",
                    stableKey = "stable",
                    states = listOf("QUEUED")
                ).map(DownloadOperationEntity::operationId)
            )
        } finally {
            database.close()
        }
    }

    private fun artifact(
        rootKey: String,
        stableKey: String,
        leaseId: String,
        updatedAtMs: Long
    ): ManagedDownloadArtifactEntity {
        return ManagedDownloadArtifactEntity(
            rootKey = rootKey,
            stableKey = stableKey,
            artifactId = "managed:$rootKey:$stableKey",
            state = ManagedDownloadArtifactState.DOWNLOADING.name,
            leaseId = leaseId,
            updatedAtMs = updatedAtMs,
            needsReconcile = true
        )
    }

    private fun operation(
        operationId: String,
        libraryId: String,
        stableKey: String,
        updatedAtMs: Long
    ): DownloadOperationEntity {
        return DownloadOperationEntity(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = libraryId,
            state = "QUEUED",
            queueOrder = 0,
            sourceHintJson = "{}",
            stagingDirName = operationId,
            bytesWritten = 0L,
            totalBytes = null,
            resumeJson = null,
            retryCount = 0,
            nextRetryAtMs = null,
            lastErrorCode = null,
            createdAtMs = updatedAtMs,
            updatedAtMs = updatedAtMs
        )
    }
}
