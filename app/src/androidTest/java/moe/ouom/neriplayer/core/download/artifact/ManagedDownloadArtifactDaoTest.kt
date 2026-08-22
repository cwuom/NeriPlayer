package moe.ouom.neriplayer.core.download.artifact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadArtifactDaoTest {
    @Test
    fun concurrentInsertKeepsExactlyOneLease() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val dao = database.managedDownloadArtifactDao()
            val results = listOf("lease-a", "lease-b").map { leaseId ->
                async {
                    withContext(executor.asCoroutineDispatcher()) {
                        barrier.await(5, TimeUnit.SECONDS)
                        dao.insertIfAbsent(artifact(leaseId))
                    }
                }
            }.awaitAll()

            assertEquals(1, results.count { it >= 0L })
            assertEquals(1, results.count { it < 0L })
            val stored = dao.find("root", "stable")
            assertTrue(stored?.leaseId in setOf("lease-a", "lease-b"))
        } finally {
            executor.shutdownNow()
            database.close()
        }
    }

    @Test
    fun bulkLookupAndRepairCasAreBoundedToTheExpectedRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.managedDownloadArtifactDao()
            val original = artifact("lease")
            dao.insertIfAbsent(original)
            dao.insertIfAbsent(
                original.copy(
                    stableKey = "other",
                    artifactId = "managed:root:other"
                )
            )

            assertEquals(2, dao.findAllByRootKey("root").size)
            assertEquals(
                1,
                dao.markRepairRequiredIfUnchanged(
                    rootKey = "root",
                    stableKey = "stable",
                    expectedState = original.state,
                    expectedUpdatedAtMs = original.updatedAtMs,
                    repairState = ManagedDownloadArtifactState.REPAIR_REQUIRED.name,
                    updatedAtMs = 2L,
                    errorCode = "TEST_UNAVAILABLE"
                )
            )
            assertEquals(
                0,
                dao.markRepairRequiredIfUnchanged(
                    rootKey = "root",
                    stableKey = "stable",
                    expectedState = original.state,
                    expectedUpdatedAtMs = original.updatedAtMs,
                    repairState = ManagedDownloadArtifactState.REPAIR_REQUIRED.name,
                    updatedAtMs = 3L,
                    errorCode = "STALE_WRITE"
                )
            )
            val repaired = dao.find("root", "stable")
            assertNotNull(repaired)
            assertEquals(ManagedDownloadArtifactState.REPAIR_REQUIRED.name, repaired?.state)
            assertEquals("TEST_UNAVAILABLE", repaired?.lastErrorCode)
        } finally {
            database.close()
        }
    }

    private fun artifact(leaseId: String): ManagedDownloadArtifactEntity {
        return ManagedDownloadArtifactEntity(
            rootKey = "root",
            stableKey = "stable",
            artifactId = "managed:root:stable",
            state = ManagedDownloadArtifactState.DOWNLOADING.name,
            leaseId = leaseId,
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
            updatedAtMs = 1L,
            needsReconcile = true,
            lastErrorCode = null
        )
    }
}
