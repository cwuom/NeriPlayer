package moe.ouom.neriplayer.data.sync.work

import android.app.NotificationManager
import android.content.Context
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAccessDeniedException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavDirectoryNotFoundException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SyncProviderFailureMessageTest {
    @Test
    fun `automatic WebDAV sync stops and reports a parent replaced by a regular file`() = runTest {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method
            val probe = request.method == "PROPFIND"
            val body = if (probe) """
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/sync/</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
            """.trimIndent() else ""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (probe) 207 else 404).message("stub")
                .body(body.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val result = WebDavApiClient("user", "pass", client, "authentication failed")
            .getFileContentStrict("https://example.test/dav/sync/neriplayer-sync.json")
        assertTrue(result.isFailure)
        assertEquals(listOf("GET", "PROPFIND"), requests)

        val context = mock(Context::class.java)
        val host = createWebDavWorkerHost(context)
        assertEquals(SyncWorkerOutcome.FAILURE, host.handleFailure(result.exceptionOrNull(), false, false))
        verify(context).getSystemService(NotificationManager::class.java)
        verify(context, never()).getString(CoreCommonR.string.webdav_auth_failed)
    }

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

    @Test
    fun `WebDAV automatic directory and access failures stop retrying without authentication messages`() = runTest {
        val context = mock(Context::class.java)
        val webDav = createWebDavWorkerHost(context)

        assertEquals(SyncWorkerOutcome.RETRY, webDav.handleFailure(IOException("offline"), false, false))
        verify(context, never()).getSystemService(NotificationManager::class.java)

        for (error in listOf(
            WebDavDirectoryNotFoundException("missing directory"),
            WebDavAccessDeniedException("access denied")
        )) {
            assertEquals(SyncWorkerOutcome.FAILURE, webDav.handleFailure(error, false, false))
        }

        verify(context, times(2)).getSystemService(NotificationManager::class.java)
        verify(context, never()).getString(CoreCommonR.string.webdav_auth_failed)
        verify(context, never()).getString(CoreCommonR.string.webdav_sync_failed_message)
    }
}
