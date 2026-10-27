package moe.ouom.neriplayer.data.sync.work

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAccessDeniedException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavDirectoryNotFoundException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN")
class SyncProviderFailureMessageTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val originalLanguage = LanguageManager.getCurrentLanguage(context)
    private val originalLocale = Locale.getDefault()
    private val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Before
    fun selectAppLanguage() {
        LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
        notifications.cancelAll()
    }

    @After
    fun restoreLanguage() {
        notifications.cancelAll()
        LanguageManager.setLanguage(context, originalLanguage)
        Locale.setDefault(originalLocale)
    }

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

        val host = createWebDavWorkerHost(context)
        assertEquals(SyncWorkerOutcome.FAILURE, host.handleFailure(result.exceptionOrNull(), false, false))
        assertEquals(result.exceptionOrNull()?.message, notificationText())
        assertTrue(notificationText() != localizedString(CoreCommonR.string.webdav_auth_failed))
    }

    @Test
    fun `provider authentication and missing messages use localized resources`() = runTest {
        val github = createGitHubWorkerHost(context)
        assertEquals(SyncWorkerOutcome.FAILURE, github.handleFailure(TokenExpiredException("expired"), true, false))
        assertEquals(localizedString(CoreCommonR.string.github_sync_token_expired), notificationText())
        for (error in listOf(IOException("details"), IOException(), null)) {
            assertEquals(SyncWorkerOutcome.RETRY, github.handleFailure(error, true, false))
            assertEquals(error?.message ?: localizedString(CoreCommonR.string.github_sync_failed_message), notificationText())
        }

        notifications.cancelAll()
        val webDav = createWebDavWorkerHost(context)
        assertEquals(SyncWorkerOutcome.FAILURE, webDav.handleFailure(WebDavAuthException("auth"), true, false))
        assertEquals(localizedString(CoreCommonR.string.webdav_auth_failed), notificationText())
        for (error in listOf(IOException("details"), IOException(), null)) {
            assertEquals(SyncWorkerOutcome.RETRY, webDav.handleFailure(error, true, false))
            assertEquals(error?.message ?: localizedString(CoreCommonR.string.webdav_sync_failed_message), notificationText())
        }
    }

    @Test
    fun `WebDAV automatic directory and access failures stop retrying without authentication messages`() = runTest {
        val webDav = createWebDavWorkerHost(context)

        assertEquals(SyncWorkerOutcome.RETRY, webDav.handleFailure(IOException("offline"), false, false))
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())

        for (error in listOf(
            WebDavDirectoryNotFoundException("missing directory"),
            WebDavAccessDeniedException("access denied")
        )) {
            assertEquals(SyncWorkerOutcome.FAILURE, webDav.handleFailure(error, false, false))
            assertEquals(error.message, notificationText())
        }
    }

    @Test
    fun `English app errors use English notification text when the system context is Chinese`() = runTest {
        assertEquals("zh", context.resources.configuration.locales[0].language)

        createGitHubWorkerHost(context).handleFailure(TokenExpiredException("diagnostic"), true, false)
        assertEquals("Token expired, please reconfigure", notificationText())

        notifications.cancelAll()
        createWebDavWorkerHost(context).handleFailure(WebDavAuthException("diagnostic"), true, false)
        assertEquals("WebDAV username or password is invalid", notificationText())
    }

    private fun localizedString(id: Int): String = LanguageManager.applyLanguage(context).getString(id)

    private fun notificationText(): String = shadowOf(notifications).allNotifications.single()
        .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
}
