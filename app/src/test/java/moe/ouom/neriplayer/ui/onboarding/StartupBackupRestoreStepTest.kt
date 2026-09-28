package moe.ouom.neriplayer.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupBackupRestoreStepTest {

    @Test
    fun githubRepositoryLabelRequiresBothParts() {
        assertEquals("owner/repo", githubRepoFullName("owner", "repo"))
        assertNull(githubRepoFullName("", "repo"))
        assertNull(githubRepoFullName("owner", "  "))
    }

    @Test
    fun webDavEndpointKeepsExistingServerAndPathFormatting() {
        assertEquals("https://dav.example/music", webDavEndpoint("https://dav.example", "music"))
        assertEquals("https://dav.example", webDavEndpoint("https://dav.example", ""))
        assertNull(webDavEndpoint("  ", "music"))
    }
}
