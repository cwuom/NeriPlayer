package moe.ouom.neriplayer.platform.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(AndroidJUnit4::class)
class AccountCompatibilityStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getNoBackupFilesDir(): File = temporaryFolder.root
    }

    @Test
    fun neteaseAccountSurvivesRecreationAndLogoutRemainsEmpty() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs")
        val repository = NeteaseCookieRepository(context)
        assertTrue(repository.requiresCompatibilityStorageWarning)
        assertTrue(repository.isAuthStoragePersistent)
        assertTrue(repository.saveCookies(mapOf("MUSIC_U" to "synthetic-netease-session")))
        assertEquals("synthetic-netease-session", NeteaseCookieRepository(context).getCookiesOnce()["MUSIC_U"])
        repository.clear()
        assertTrue(NeteaseCookieRepository(context).getCookiesOnce().isEmpty())
    }

    @Test
    fun biliAccountSurvivesRecreationAndLogoutRemainsEmpty() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs")
        val repository = BiliCookieRepository(context)
        assertTrue(repository.requiresCompatibilityStorageWarning)
        assertTrue(repository.isAuthStoragePersistent)
        repository.saveCookies(mapOf("SESSDATA" to "synthetic-bili-session"))
        assertEquals("synthetic-bili-session", BiliCookieRepository(context).getCookiesOnce()["SESSDATA"])
        repository.clear()
        assertTrue(BiliCookieRepository(context).getCookiesOnce().isEmpty())
    }

    @Test
    fun youtubeCompatibilityStorageTakesPrecedenceOverOldRecoveryCopies() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)
        assertTrue(repository.requiresCompatibilityStorageWarning)
        assertTrue(repository.isAuthStoragePersistent)
        repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "synthetic-youtube-session")))
        assertEquals("synthetic-youtube-session", YouTubeAuthRepository(context).getAuthOnce().cookies["SAPISID"])
        repository.clear()
        assertTrue(YouTubeAuthRepository(context).getAuthOnce().cookies.isEmpty())
        assertFalse(YouTubeAuthRepository(context).getAuthOnce().cookies.containsValue("synthetic-youtube-session"))
    }

    @Test
    fun neteaseFailedCommitKeepsPublishedAccountUnchanged() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs")
        val repository = NeteaseCookieRepository(context)
        assertTrue(repository.saveCookies(mapOf("MUSIC_U" to "saved-session")))
        val before = repository.getCookiesOnce()
        replacePreferences(repository, failedPreferences())

        assertFalse(repository.saveCookies(mapOf("MUSIC_U" to "unsaved-session")))
        assertEquals(before, repository.getCookiesOnce())
        assertThrows(IOException::class.java) { repository.clear() }
        assertEquals(before, repository.getCookiesOnce())
    }

    @Test
    fun biliFailedCommitKeepsPublishedAccountUnchanged() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs")
        val repository = BiliCookieRepository(context)
        repository.saveCookies(mapOf("SESSDATA" to "saved-session"))
        val before = repository.getCookiesOnce()
        replacePreferences(repository, failedPreferences())

        assertThrows(IOException::class.java) {
            repository.saveCookies(mapOf("SESSDATA" to "unsaved-session"))
        }
        assertEquals(before, repository.getCookiesOnce())
        assertThrows(IOException::class.java) { repository.clear() }
        assertEquals(before, repository.getCookiesOnce())
    }

    @Test
    fun youtubeFailedCompatibilityCommitKeepsPublishedAccountUnchanged() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)
        repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "saved-session")))
        val before = repository.getAuthOnce()
        replacePreferences(repository, failedPreferences())

        assertThrows(IOException::class.java) {
            repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "unsaved-session")))
        }
        assertEquals(before, repository.getAuthOnce())
        assertThrows(IOException::class.java) { repository.clear() }
        assertEquals(before, repository.getAuthOnce())
    }

    @Test
    fun youtubeSecureWriteFailureDoesNotSilentlySwitchToCompatibilityStorage() {
        val context = context()
        val compatibility = AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)
        repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "saved-session")))
        val before = repository.getAuthOnce()
        val compatibilityBefore = compatibility.all
        replacePreferences(repository, failedPreferences())
        repository.javaClass.getDeclaredField("usingCompatibilityStorage").apply {
            isAccessible = true
            setBoolean(repository, false)
        }
        repository.javaClass.getDeclaredField("usingRecoveryStorage").apply {
            isAccessible = true
            setBoolean(repository, true)
        }

        assertTrue(repository.isAuthStoragePersistent)
        assertFalse(repository.requiresCompatibilityStorageWarning)
        assertThrows(IOException::class.java) {
            repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "unsaved-session")))
        }
        assertEquals(before, repository.getAuthOnce())
        assertEquals(compatibilityBefore, compatibility.all)
    }

    @Test
    fun unavailableCompatibilityStorageBlocksAllAccountWrites() {
        val unavailableDirectory = temporaryFolder.newFile("not-a-directory")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getNoBackupFilesDir(): File = unavailableDirectory
        }
        AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs")
        AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs")
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val netease = NeteaseCookieRepository(context)
        val bili = BiliCookieRepository(context)
        val youtube = YouTubeAuthRepository(context)

        assertFalse(netease.isAuthStoragePersistent)
        assertFalse(bili.isAuthStoragePersistent)
        assertFalse(youtube.isAuthStoragePersistent)
        assertFalse(netease.saveCookies(mapOf("MUSIC_U" to "unsaved-session")))
        assertThrows(IOException::class.java) { bili.saveCookies(mapOf("SESSDATA" to "unsaved-session")) }
        assertThrows(IOException::class.java) {
            youtube.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "unsaved-session")))
        }
        assertTrue(netease.getCookiesOnce().isEmpty())
        assertTrue(bili.getCookiesOnce().isEmpty())
        assertTrue(youtube.getAuthOnce().cookies.isEmpty())
    }

    private fun failedPreferences(): SharedPreferences {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        return preferences
    }

    private fun replacePreferences(repository: Any, preferences: SharedPreferences) {
        repository.javaClass.getDeclaredField("encryptedPrefs").apply {
            isAccessible = true
            set(repository, preferences)
        }
    }
}
