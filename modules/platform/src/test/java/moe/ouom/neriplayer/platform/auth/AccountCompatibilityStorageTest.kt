package moe.ouom.neriplayer.platform.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieKeys
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.CookieKeys
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(AndroidJUnit4::class)
class AccountCompatibilityStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun context(directory: File = temporaryFolder.newFolder()): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getFilesDir(): File = legacyFilesDirectory
            override fun getApplicationContext(): Context = this
        }

    @After
    fun clearLegacyCookies() {
        runBlocking {
            val context = context(temporaryFolder.root)
            biliLegacyStore(context).edit { it.clear() }
            neteaseLegacyStore(context).edit { it.clear() }
        }
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
    fun freshBiliCompatibilityStorageMigratesLegacyCookiesAndSurvivesReopening() = runBlocking {
        val context = context()
        biliLegacyStore(context).edit {
            it[BiliCookieKeys.COOKIE_JSON] = """{"SESSDATA":"legacy-bili-session"}"""
        }
        AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs")

        val repository = BiliCookieRepository(context)

        assertEquals("legacy-bili-session", repository.getCookiesOnce()["SESSDATA"])
        assertTrue(repository.requiresCompatibilityStorageWarning)
        assertEquals(0L, repository.getAuthHealthOnce().savedAt)
        assertNull(biliLegacyStore(context).data.first()[BiliCookieKeys.COOKIE_JSON])
        assertEquals(
            "legacy-bili-session",
            BiliCookieRepository(reopenedContext(context)).getCookiesOnce()["SESSDATA"]
        )
    }

    @Test
    fun freshNeteaseCompatibilityStorageMigratesLegacyCookiesAndSurvivesReopening() = runBlocking {
        val context = context()
        neteaseLegacyStore(context).edit {
            it[CookieKeys.NETEASE_COOKIE_JSON] = """{"MUSIC_U":"legacy-netease-session"}"""
        }
        AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs")

        val repository = NeteaseCookieRepository(context)

        assertEquals("legacy-netease-session", repository.getCookiesOnce()["MUSIC_U"])
        assertEquals("pc", repository.getCookiesOnce()["os"])
        assertEquals("8.10.35", repository.getCookiesOnce()["appver"])
        assertTrue(repository.requiresCompatibilityStorageWarning)
        assertEquals(0L, repository.getAuthHealthOnce().savedAt)
        assertNull(neteaseLegacyStore(context).data.first()[CookieKeys.NETEASE_COOKIE_JSON])
        assertEquals(
            "legacy-netease-session",
            NeteaseCookieRepository(reopenedContext(context)).getCookiesOnce()["MUSIC_U"]
        )
    }

    @Test
    fun biliExplicitLogoutPreventsResidualLegacyCookiesFromReturningAfterReopening() = runBlocking {
        val context = context()
        AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs")
        val repository = BiliCookieRepository(context)
        repository.clear()
        biliLegacyStore(context).edit {
            it[BiliCookieKeys.COOKIE_JSON] = """{"SESSDATA":"obsolete-bili-session"}"""
        }

        assertTrue(BiliCookieRepository(reopenedContext(context)).getCookiesOnce().isEmpty())
    }

    @Test
    fun neteaseExplicitLogoutPreventsResidualLegacyCookiesFromReturningAfterReopening() = runBlocking {
        val context = context()
        AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs")
        val repository = NeteaseCookieRepository(context)
        repository.clear()
        neteaseLegacyStore(context).edit {
            it[CookieKeys.NETEASE_COOKIE_JSON] = """{"MUSIC_U":"obsolete-netease-session"}"""
        }

        assertTrue(NeteaseCookieRepository(reopenedContext(context)).getCookiesOnce().isEmpty())
    }

    @Test
    fun existingEmptyBiliCompatibilityTombstonePreventsLegacyCookiesFromReturning() = runBlocking {
        val context = context()
        assertTrue(AccountPreferencesOpener.fallback(context, "bili_auth_secure_prefs").edit().clear().commit())
        biliLegacyStore(context).edit {
            it[BiliCookieKeys.COOKIE_JSON] = """{"SESSDATA":"obsolete-bili-session"}"""
        }

        assertTrue(BiliCookieRepository(reopenedContext(context)).getCookiesOnce().isEmpty())
    }

    @Test
    fun existingEmptyNeteaseCompatibilityTombstonePreventsLegacyCookiesFromReturning() = runBlocking {
        val context = context()
        assertTrue(AccountPreferencesOpener.fallback(context, "netease_auth_secure_prefs").edit().clear().commit())
        neteaseLegacyStore(context).edit {
            it[CookieKeys.NETEASE_COOKIE_JSON] = """{"MUSIC_U":"obsolete-netease-session"}"""
        }

        assertTrue(NeteaseCookieRepository(reopenedContext(context)).getCookiesOnce().isEmpty())
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
    fun youtubeUnconfirmedCompatibilitySavePreservesPublishedAndStoredAuth() {
        val context = context()
        val compatibility = AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)
        repository.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "saved-session"), savedAt = 123L))
        val before = repository.getAuthOnce()
        val healthBefore = repository.getAuthHealthOnce()
        val storedBefore = compatibility.all
        val unconfirmed = listOf(
            YouTubeAuthBundle(cookies = mapOf("SAPISID" to "unconfirmed-cookie")),
            YouTubeAuthBundle(cookieHeader = "SID=unconfirmed-header"),
            YouTubeAuthBundle(authorization = "SAPISIDHASH synthetic-authorization")
        )

        unconfirmed.forEach { bundle ->
            assertThrows(IOException::class.java) {
                repository.saveAuth(bundle, allowCompatibilityStorage = false)
            }
            assertEquals(before, repository.getAuthOnce())
            assertEquals(healthBefore, repository.getAuthHealthOnce())
            assertEquals(storedBefore, compatibility.all)
        }
        assertEquals("saved-session", YouTubeAuthRepository(reopenedContext(context)).getAuthOnce().cookies["SAPISID"])
    }

    @Test
    fun youtubeConfirmedCompatibilitySavePersistsAfterReopening() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)

        repository.saveAuth(
            YouTubeAuthBundle(cookies = mapOf("SAPISID" to "confirmed-session"), savedAt = 123L),
            allowCompatibilityStorage = true
        )

        assertEquals("confirmed-session", repository.getAuthOnce().cookies["SAPISID"])
        val reopened = YouTubeAuthRepository(reopenedContext(context)).getAuthOnce()
        assertEquals("confirmed-session", reopened.cookies["SAPISID"])
        assertEquals(123L, reopened.savedAt)
    }

    @Test
    fun youtubeMetadataWithoutCredentialsCanBeSavedWithoutCompatibilityConsent() {
        val context = context()
        AccountPreferencesOpener.fallback(context, "youtube_auth_secure_prefs")
        val repository = YouTubeAuthRepository(context)

        repository.saveAuth(
            YouTubeAuthBundle(
                xGoogAuthUser = "1",
                origin = "https://music.youtube.com",
                userAgent = "synthetic-agent",
                savedAt = 123L
            ),
            allowCompatibilityStorage = false
        )

        val reopened = YouTubeAuthRepository(reopenedContext(context)).getAuthOnce()
        assertTrue(reopened.cookies.isEmpty())
        assertEquals("", reopened.cookieHeader)
        assertEquals("", reopened.authorization)
        assertEquals("1", reopened.xGoogAuthUser)
        assertEquals("https://music.youtube.com", reopened.origin)
        assertEquals("synthetic-agent", reopened.userAgent)
        assertEquals(123L, reopened.savedAt)
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

    private fun reopenedContext(context: Context): Context {
        val directory = temporaryFolder.newFolder()
        context.noBackupFilesDir.copyRecursively(directory, overwrite = true)
        return context(directory)
    }

    private fun biliLegacyStore(context: Context): DataStore<Preferences> = legacyStore(
        context,
        "moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepositoryKt",
        "getBiliCookieStore"
    )

    private fun neteaseLegacyStore(context: Context): DataStore<Preferences> = legacyStore(
        context,
        "moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepositoryKt",
        "getCookieDataStore"
    )

    @Suppress("UNCHECKED_CAST")
    private fun legacyStore(context: Context, className: String, getterName: String): DataStore<Preferences> {
        val getter = Class.forName(className).getDeclaredMethod(getterName, Context::class.java)
        getter.isAccessible = true
        return getter.invoke(null, context) as DataStore<Preferences>
    }

    private fun replacePreferences(repository: Any, preferences: SharedPreferences) {
        repository.javaClass.getDeclaredField("encryptedPrefs").apply {
            isAccessible = true
            set(repository, preferences)
        }
    }

    private companion object {
        // DataStore 委托复用首次打开的目录，Robolectric 重建 Application 后仍需保持同一路径
        val legacyFilesDirectory: File by lazy {
            ApplicationProvider.getApplicationContext<Context>().filesDir
        }
    }
}
