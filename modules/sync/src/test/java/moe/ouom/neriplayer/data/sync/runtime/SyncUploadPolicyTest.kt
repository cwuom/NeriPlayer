package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUploadPolicyTest {
    private val data = SyncData(deviceId = "device", deviceName = "test-device")

    @Test
    fun `legacy backup migration uploads even when data is unchanged`() {
        assertTrue(
            SyncUploadPolicy.shouldUpload(
                remoteData = data,
                requiresMigrationUpload = true,
                mergedData = data.copy()
            )
        )
    }

    @Test
    fun `current backup with unchanged data skips upload`() {
        assertFalse(
            SyncUploadPolicy.shouldUpload(
                remoteData = data,
                requiresMigrationUpload = false,
                mergedData = data.copy()
            )
        )
    }
}
