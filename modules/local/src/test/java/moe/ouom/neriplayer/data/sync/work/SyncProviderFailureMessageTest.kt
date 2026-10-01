package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SyncProviderFailureMessageTest {
    @Test
    fun `provider authentication and missing messages use localized resources`() = runTest {
        val githubContext = mock(Context::class.java)
        `when`(githubContext.getString(CoreCommonR.string.github_sync_token_expired)).thenReturn("expired")
        `when`(githubContext.getString(CoreCommonR.string.github_sync_failed_message)).thenReturn("failed")
        val github = createGitHubWorkerHost(githubContext)
        github.handleFailure(TokenExpiredException("expired"), true, false)
        github.handleFailure(IOException("details"), true, false)
        github.handleFailure(IOException(), true, false)
        github.handleFailure(null, true, false)
        verify(githubContext).getString(CoreCommonR.string.github_sync_token_expired)

        val webDavContext = mock(Context::class.java)
        `when`(webDavContext.getString(CoreCommonR.string.webdav_auth_failed)).thenReturn("auth")
        `when`(webDavContext.getString(CoreCommonR.string.webdav_sync_failed_message)).thenReturn("failed")
        val webDav = createWebDavWorkerHost(webDavContext)
        webDav.handleFailure(WebDavAuthException("auth"), true, false)
        webDav.handleFailure(IOException("details"), true, false)
        webDav.handleFailure(IOException(), true, false)
        webDav.handleFailure(null, true, false)
        verify(webDavContext).getString(CoreCommonR.string.webdav_auth_failed)
    }
}
