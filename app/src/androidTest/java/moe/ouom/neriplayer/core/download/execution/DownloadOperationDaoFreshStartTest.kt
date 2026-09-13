package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOperationDaoFreshStartTest {
    @Test
    fun freshStartReleasesOnlyStoppedPostCoreRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.downloadOperationDao()
            dao.upsert(
                operation(
                    operationId = "post-core",
                    stableKey = "song",
                    state = "CORE_COMMITTED",
                    stopRequestedByUser = true,
                    lastErrorCode = "USER_CANCELLED"
                )
            )
            dao.upsert(
                operation(
                    operationId = "queued-cancelled",
                    stableKey = "song",
                    state = "CANCEL_REQUESTED",
                    stopRequestedByUser = false,
                    lastErrorCode = "USER_CANCELLED"
                )
            )
            dao.upsert(
                operation(
                    operationId = "other-song",
                    stableKey = "other",
                    state = "CORE_COMMITTED",
                    stopRequestedByUser = true,
                    lastErrorCode = "USER_CANCELLED"
                )
            )

            assertEquals(
                1,
                dao.clearUserStopForFreshStartAnyLibrary(
                    stableKeys = listOf("song"),
                    updatedAtMs = 100L
                )
            )

            val released = dao.find("post-core")
            assertNotNull(released)
            assertFalse(released?.stopRequestedByUser == true)
            assertNull(released?.lastErrorCode)
            assertEquals("CORE_COMMITTED", released?.state)
            assertEquals(100L, released?.updatedAtMs)

            val queued = dao.find("queued-cancelled")
            assertNotNull(queued)
            assertEquals("CANCEL_REQUESTED", queued?.state)
            assertEquals("USER_CANCELLED", queued?.lastErrorCode)

            val other = dao.find("other-song")
            assertNotNull(other)
            assertTrue(other?.stopRequestedByUser == true)
            assertEquals("USER_CANCELLED", other?.lastErrorCode)
        } finally {
            database.close()
        }
    }

    @Test
    fun retryableTransitionPersistsBackoffAndClaimClearsDeadline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.downloadOperationDao()
            dao.upsert(
                operation(
                    operationId = "retryable",
                    stableKey = "song",
                    state = "RUNNING",
                    stopRequestedByUser = false,
                    lastErrorCode = null,
                    retryCount = 2,
                    updatedAtMs = 10L
                )
            )

            assertEquals(
                1,
                dao.transitionToRetryable(
                    operationId = "retryable",
                    expectedStates = listOf("RUNNING"),
                    expectedRetryCount = 2,
                    expectedUpdatedAtMs = 10L,
                    retryCount = 3,
                    nextRetryAtMs = 4_010L,
                    updatedAtMs = 4_000L,
                    errorCode = "IO_FAILURE"
                )
            )
            val retryable = requireNotNull(dao.find("retryable"))
            assertEquals("RETRYABLE", retryable.state)
            assertEquals(3, retryable.retryCount)
            assertEquals(4_010L, retryable.nextRetryAtMs)
            assertEquals("IO_FAILURE", retryable.lastErrorCode)

            assertEquals(
                1,
                dao.transitionState(
                    operationId = "retryable",
                    expectedStates = listOf("RETRYABLE"),
                    state = "RUNNING",
                    updatedAtMs = 5_000L,
                    errorCode = null
                )
            )
            val claimed = requireNotNull(dao.find("retryable"))
            assertEquals("RUNNING", claimed.state)
            assertEquals(3, claimed.retryCount)
            assertNull(claimed.nextRetryAtMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun processRestartRequeuesOnlyRunningRowsWithoutCurrentHostOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val dao = database.downloadOperationDao()
            dao.upsert(
                operation(
                    operationId = "old-host",
                    stableKey = "old-song",
                    state = "RUNNING",
                    stopRequestedByUser = false,
                    lastErrorCode = null,
                    hostProcessToken = "old-process"
                )
            )
            dao.upsert(
                operation(
                    operationId = "missing-host",
                    stableKey = "missing-song",
                    state = "RUNNING",
                    stopRequestedByUser = false,
                    lastErrorCode = null
                )
            )
            dao.upsert(
                operation(
                    operationId = "current-host",
                    stableKey = "current-song",
                    state = "RUNNING",
                    stopRequestedByUser = false,
                    lastErrorCode = null,
                    hostProcessToken = "current-process"
                )
            )
            dao.upsert(
                operation(
                    operationId = "post-core",
                    stableKey = "post-core-song",
                    state = "CORE_COMMITTED",
                    stopRequestedByUser = false,
                    lastErrorCode = null,
                    hostProcessToken = "old-process"
                )
            )
            dao.upsert(
                operation(
                    operationId = "user-stopped",
                    stableKey = "stopped-song",
                    state = "RUNNING",
                    stopRequestedByUser = true,
                    lastErrorCode = "USER_CANCELLED",
                    hostProcessToken = "old-process"
                )
            )

            assertEquals(
                setOf("old-host", "missing-host"),
                dao.findOrphanedRunningOperationIdentities("current-process")
                    .mapTo(linkedSetOf()) { identity -> identity.operationId }
            )
            assertEquals(
                2,
                dao.requeueOrphanedRunningOperations(
                    processToken = "current-process",
                    updatedAtMs = 100L
                )
            )

            listOf("old-host", "missing-host").forEach { operationId ->
                val recovered = requireNotNull(dao.find(operationId))
                assertEquals("RETRYABLE", recovered.state)
                assertEquals("PROCESS_RESTART_RECOVERY", recovered.lastErrorCode)
                assertNull(recovered.nextRetryAtMs)
                assertNull(recovered.hostProcessToken)
            }
            assertEquals("RUNNING", dao.find("current-host")?.state)
            assertEquals("current-process", dao.find("current-host")?.hostProcessToken)
            assertEquals("CORE_COMMITTED", dao.find("post-core")?.state)
            assertEquals("RUNNING", dao.find("user-stopped")?.state)
        } finally {
            database.close()
        }
    }

    private fun operation(
        operationId: String,
        stableKey: String,
        state: String,
        stopRequestedByUser: Boolean,
        lastErrorCode: String?,
        retryCount: Int = 0,
        nextRetryAtMs: Long? = null,
        updatedAtMs: Long = 1L,
        hostProcessToken: String? = null
    ): DownloadOperationEntity {
        return DownloadOperationEntity(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = "root",
            state = state,
            queueOrder = 0,
            sourceHintJson = "{}",
            stagingDirName = "staging-$operationId",
            bytesWritten = 0L,
            totalBytes = null,
            resumeJson = null,
            retryCount = retryCount,
            nextRetryAtMs = nextRetryAtMs,
            lastErrorCode = lastErrorCode,
            stopRequestedByUser = stopRequestedByUser,
            createdAtMs = 1L,
            updatedAtMs = updatedAtMs,
            hostProcessToken = hostProcessToken,
            hostAdmittedAtMs = hostProcessToken?.let { 1L }
        )
    }
}
