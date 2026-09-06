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

    private fun operation(
        operationId: String,
        stableKey: String,
        state: String,
        stopRequestedByUser: Boolean,
        lastErrorCode: String?
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
            retryCount = 0,
            nextRetryAtMs = null,
            lastErrorCode = lastErrorCode,
            stopRequestedByUser = stopRequestedByUser,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            hostProcessToken = null,
            hostAdmittedAtMs = null
        )
    }
}
