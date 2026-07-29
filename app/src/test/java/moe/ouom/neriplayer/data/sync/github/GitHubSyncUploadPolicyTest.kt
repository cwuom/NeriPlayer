package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSyncUploadPolicyTest {
    private val data = SyncData(deviceId = "device", deviceName = "test-device")

    @Test
    fun `legacy backup migration uploads even when data is unchanged`() {
        assertTrue(
            GitHubSyncUploadPolicy.shouldUpload(
                remoteData = data,
                requiresMigrationUpload = true,
                mergedData = data.copy()
            )
        )
    }

    @Test
    fun `current backup with unchanged data skips upload`() {
        assertFalse(
            GitHubSyncUploadPolicy.shouldUpload(
                remoteData = data,
                requiresMigrationUpload = false,
                mergedData = data.copy()
            )
        )
    }
}
