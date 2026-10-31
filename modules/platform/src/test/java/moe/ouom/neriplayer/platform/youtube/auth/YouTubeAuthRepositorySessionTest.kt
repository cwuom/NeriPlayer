package moe.ouom.neriplayer.platform.youtube.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeAuthRepositorySessionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `full refresh captured before logout cannot restore the account in memory or on disk`() {
        val (context, repository) = repository()
        val captured = repository.captureAuthSnapshot()
        repository.clear()

        val result = repository.updateAuthIfCurrent(captured) {
            captured.auth.copy(cookies = captured.auth.cookies + ("HSID" to "late-identity"))
        }

        assertNull(result)
        assertTrue(repository.getAuthOnce().cookies.isEmpty())
        assertTrue(YouTubeAuthRepository(context).getAuthOnce().cookies.isEmpty())
    }

    @Test
    fun `late identity salvage cannot replace a newly logged in account`() {
        val (context, repository) = repository()
        val captured = repository.captureAuthSnapshot()
        repository.saveAuth(auth("new-session"))

        val result = repository.updateAuthIfCurrent(captured) { current ->
            mergeYouTubeAuthBundle(current, mapOf("HSID" to "late-identity"))
        }

        assertNull(result)
        assertEquals("new-session", repository.getAuthOnce().cookies["__Secure-1PSID"])
        assertFalse(repository.getAuthOnce().cookies.containsKey("HSID"))
        assertEquals(repository.getAuthOnce(), YouTubeAuthRepository(context).getAuthOnce())
    }

    @Test
    fun `rotation captured before logout and same account login cannot pass by equal cookies`() {
        val (_, repository) = repository()
        val captured = repository.captureAuthSnapshot()
        repository.clear()
        repository.saveAuth(captured.auth)
        assertEquals(captured.auth, repository.getAuthOnce())

        val result = repository.updateAuthIfCurrent(captured) { current ->
            mergeYouTubeAuthBundle(current, mapOf("__Secure-1PSIDTS" to "late-rotation"))
        }

        assertNull(result)
        assertFalse(repository.getAuthOnce().cookies.containsKey("__Secure-1PSIDTS"))
    }

    @Test
    fun `explicit save of identical credentials still invalidates an older request`() {
        val (_, repository) = repository()
        val captured = repository.captureAuthSnapshot()
        repository.saveAuth(captured.auth)

        assertNull(repository.updateAuthIfCurrent(captured) { auth("late-session") })
        assertEquals("original-session", repository.getAuthOnce().cookies["__Secure-1PSID"])
    }

    @Test
    fun `late response cookies do not overwrite a newer session rotation`() {
        val (_, repository) = repository()
        val olderRequest = repository.captureAuthSnapshot()
        val newerRequest = repository.captureAuthSnapshot()
        assertNotNull(repository.updateAuthIfCurrent(newerRequest) { current ->
            mergeYouTubeAuthCookieUpdates(current, listOf("__Secure-1PSIDTS=new-rotation; Secure"))
        })

        assertNull(repository.updateAuthIfCurrent(olderRequest) { current ->
            mergeYouTubeAuthCookieUpdates(current, listOf("__Secure-1PSIDTS=old-rotation; Secure"))
        })
        assertEquals("new-rotation", repository.getAuthOnce().cookies["__Secure-1PSIDTS"])
    }

    @Test
    fun `salvage returns a current snapshot so the same refresh can finish without losing it`() {
        val (_, repository) = repository()
        val initial = repository.captureAuthSnapshot()
        val salvaged = requireNotNull(repository.updateAuthIfCurrent(initial) { current ->
            mergeYouTubeAuthBundle(current, mapOf("HSID" to "salvaged-identity"))
        })

        val refreshed = repository.updateAuthIfCurrent(salvaged) { current ->
            mergeYouTubeAuthBundle(current, mapOf("__Secure-1PSIDTS" to "fresh-rotation"))
        }

        assertNotNull(refreshed)
        assertEquals("salvaged-identity", repository.getAuthOnce().cookies["HSID"])
        assertEquals("fresh-rotation", repository.getAuthOnce().cookies["__Secure-1PSIDTS"])
        assertEquals("2", repository.getAuthOnce().xGoogAuthUser)
        assertEquals("synthetic-agent", repository.getAuthOnce().userAgent)
        assertNull(repository.updateAuthIfCurrent(initial) { auth("obsolete-session") })
    }

    @Test
    fun `stale browser cookie writes are skipped even after a successful credential commit`() {
        val (_, repository) = repository()
        val committed = requireNotNull(repository.updateAuthIfCurrent(repository.captureAuthSnapshot()) { current ->
            mergeYouTubeAuthBundle(current, mapOf("__Secure-1PSIDTS" to "fresh-rotation"))
        })
        repository.clear()
        var browserWrites = 0

        assertFalse(repository.runIfCurrent(committed) { browserWrites += 1 })
        assertEquals(0, browserWrites)
        assertTrue(repository.runIfCurrent(repository.captureAuthSnapshot()) { browserWrites += 1 })
        assertEquals(1, browserWrites)
    }

    @Test
    fun `another repository cannot accept a snapshot with identical credentials`() {
        val (_, first) = repository()
        val (_, second) = repository()

        assertNull(second.updateAuthIfCurrent(first.captureAuthSnapshot()) { auth("foreign-session") })
        assertEquals("original-session", second.getAuthOnce().cookies["__Secure-1PSID"])
    }

    private fun repository(): Pair<Context, YouTubeAuthRepository> {
        val directory = temporaryFolder.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)
        repository.saveAuth(auth("original-session"))
        return context to repository
    }

    private fun auth(session: String) = YouTubeAuthBundle(
        cookies = mapOf("__Secure-1PSID" to session, "SAPISID" to "synthetic-binding"),
        xGoogAuthUser = "2",
        userAgent = "synthetic-agent",
        savedAt = 123L
    )
}
