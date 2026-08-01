package moe.ouom.neriplayer.ui.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUiStateDefaultsTest {
    @Test
    fun `github and webdav ui states keep auto sync enabled by default`() {
        assertTrue(GitHubSyncUiState().autoSyncEnabled)
        assertTrue(WebDavSyncUiState().autoSyncEnabled)
    }
}
