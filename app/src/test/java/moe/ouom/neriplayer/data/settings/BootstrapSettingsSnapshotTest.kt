package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapSettingsSnapshotTest {

    @Test
    fun `sanitized clears blank bootstrap values`() {
        val snapshot = BootstrapSettingsSnapshot(
            bypassProxy = false,
            downloadDirectoryUri = " ",
            downloadDirectoryLabel = "",
            downloadFileNameTemplate = " "
        ).sanitized()

        assertEquals(false, snapshot.bypassProxy)
        assertNull(snapshot.downloadDirectoryUri)
        assertNull(snapshot.downloadDirectoryLabel)
        assertNull(snapshot.downloadFileNameTemplate)
    }
}
