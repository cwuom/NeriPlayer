package moe.ouom.neriplayer.core.startup.debug

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugBuildWarningRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `confirmation survives a new repository and datastore instance`() = runTest {
        val file = File(temporaryFolder.root, "warning.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + StandardTestDispatcher(testScheduler))
        val first = DebugBuildWarningRepository(
            PreferenceDataStoreFactory.create(scope = firstScope) { file }
        )
        try {
            assertFalse(first.isAcknowledged())
            first.acknowledge()
            assertTrue(first.isAcknowledged())
        } finally {
            firstScope.cancel()
            firstJob.join()
        }

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + StandardTestDispatcher(testScheduler))
        try {
            val second = DebugBuildWarningRepository(
                PreferenceDataStoreFactory.create(scope = secondScope) { file }
            )
            assertTrue(second.isAcknowledged())
        } finally {
            secondScope.cancel()
            secondJob.join()
        }
    }

    @Test
    fun `a fresh installation has its own unconfirmed warning`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        try {
            val confirmed = DebugBuildWarningRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    File(temporaryFolder.root, "confirmed.preferences_pb")
                }
            )
            confirmed.acknowledge()
            val fresh = DebugBuildWarningRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    File(temporaryFolder.root, "fresh.preferences_pb")
                }
            )
            assertTrue(confirmed.isAcknowledged())
            assertFalse(fresh.isAcknowledged())
        } finally {
            scope.cancel()
            job.join()
        }
    }

    @Test
    fun `corrupt confirmation data resets only the warning and allows confirmation`() = runTest {
        val file = File(temporaryFolder.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(-1, -1, -1))
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        try {
            val repository = DebugBuildWarningRepository(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = debugBuildWarningCorruptionHandler,
                    scope = scope
                ) { file }
            )
            assertFalse(repository.isAcknowledged())
            repository.acknowledge()
            assertTrue(repository.isAcknowledged())
        } finally {
            scope.cancel()
            job.join()
        }
    }
}
