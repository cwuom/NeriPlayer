package moe.ouom.neriplayer.platform.youtube.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
    fun `discarded response cookies do not invalidate a pending refresh or write storage`() {
        val (context, repository) = repository()
        val pendingRefresh = repository.captureAuthSnapshot()
        val responseSnapshot = repository.captureAuthSnapshot()
        val preferences = AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val storedBefore = preferences.all
        var storageEdits = 0
        val countedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                storageEdits += 1
                return preferences.edit()
            }
        }
        repository.javaClass.getDeclaredField("encryptedPrefs").apply {
            isAccessible = true
            set(repository, countedPreferences)
        }

        val result = repository.updateAuthIfCurrent(responseSnapshot) { current ->
            requireNotNull(mergeYouTubeAuthCookieUpdates(
                current,
                listOf("NID=synthetic-unpersisted-cookie; Secure"),
                savedAt = 456L
            ))
        }
        var browserSyncs = 0

        assertNotNull(result)
        assertEquals(pendingRefresh.auth, result!!.auth)
        assertTrue(repository.runIfCurrent(pendingRefresh) { browserSyncs += 1 })
        assertEquals(1, browserSyncs)
        assertEquals(0, storageEdits)
        assertEquals(storedBefore, preferences.all)
        assertEquals(pendingRefresh.auth, YouTubeAuthRepository(context).getAuthOnce())
    }

    @Test
    fun `a timestamp only conditional update preserves the saved bundle and active snapshot`() {
        val (context, repository) = repository()
        val captured = repository.captureAuthSnapshot()
        val healthBefore = repository.getAuthHealthOnce()

        val result = repository.updateAuthIfCurrent(captured) { current ->
            current.copy(savedAt = 456L)
        }

        assertNotNull(result)
        assertEquals(captured.auth, result!!.auth)
        assertTrue(repository.isCurrent(captured))
        assertEquals(123L, repository.getAuthOnce().savedAt)
        assertEquals(healthBefore, repository.getAuthHealthOnce())
        assertEquals(captured.auth, YouTubeAuthRepository(context).getAuthOnce())
    }

    @Test
    fun `changed account metadata still invalidates an earlier refresh snapshot`() {
        val changes: List<(YouTubeAuthBundle) -> YouTubeAuthBundle> = listOf(
            { it.copy(authorization = "SAPISIDHASH synthetic-authorization") },
            { it.copy(xGoogAuthUser = "3") },
            { it.copy(origin = "https://www.youtube.com") },
            { it.copy(userAgent = "changed-agent") }
        )
        changes.forEach { change ->
            val (_, repository) = repository()
            val captured = repository.captureAuthSnapshot()

            val result = repository.updateAuthIfCurrent(captured, change)

            assertNotNull(result)
            assertFalse(repository.isCurrent(captured))
            assertTrue(repository.isCurrent(result!!))
            assertEquals(change(captured.auth), result.auth)
        }
    }

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
