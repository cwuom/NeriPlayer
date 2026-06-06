package moe.ouom.neriplayer.data.auth.netease

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TestNeteaseAuthPrefs = "netease_auth_secure_prefs"
private val LegacyCookieJsonKey = stringPreferencesKey("netease_cookie_json")

@RunWith(AndroidJUnit4::class)
class NeteaseCookieRepositoryAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        clearStorage()
    }

    @After
    fun tearDown() {
        clearStorage()
    }

    @Test
    fun saveCookies_persistsAcrossRepositoryRecreation_andClearRemovesThem() {
        val savedAt = System.currentTimeMillis()
        val firstRepository = NeteaseCookieRepository(context)

        assertTrue(
            firstRepository.saveCookies(
                cookies = mapOf(
                    "MUSIC_U" to "music-cookie",
                    "__csrf" to "csrf-token"
                ),
                savedAt = savedAt
            )
        )

        val recreatedRepository = NeteaseCookieRepository(context)
        assertEquals("music-cookie", recreatedRepository.getCookiesOnce()["MUSIC_U"])
        assertEquals("csrf-token", recreatedRepository.getCookiesOnce()["__csrf"])
        assertEquals("pc", recreatedRepository.getCookiesOnce()["os"])
        assertEquals("8.10.35", recreatedRepository.getCookiesOnce()["appver"])
        assertEquals(
            SavedCookieAuthState.Valid,
            recreatedRepository.getAuthHealth(now = savedAt + 1_000L).state
        )

        recreatedRepository.clear()

        val clearedRepository = NeteaseCookieRepository(context)
        assertTrue(clearedRepository.getCookiesOnce().isEmpty())
        assertEquals(SavedCookieAuthState.Missing, clearedRepository.getAuthHealthOnce().state)
    }

    @Test
    fun legacyCookies_areMigratedIntoEncryptedStorage_andLegacyStoreIsCleared() {
        runBlocking {
            cookieDataStore().edit { preferences ->
                preferences[LegacyCookieJsonKey] = JSONObject(
                    mapOf(
                        "MUSIC_U" to "legacy-cookie",
                        "__csrf" to "legacy-csrf"
                    )
                ).toString()
            }
        }

        val repository = NeteaseCookieRepository(context)

        assertEquals("legacy-cookie", repository.getCookiesOnce()["MUSIC_U"])
        assertEquals("legacy-csrf", repository.getCookiesOnce()["__csrf"])
        assertEquals("pc", repository.getCookiesOnce()["os"])
        assertEquals("8.10.35", repository.getCookiesOnce()["appver"])
        assertEquals(SavedCookieAuthState.Valid, repository.getAuthHealthOnce().state)

        val legacyJsonAfterMigration = runBlocking {
            cookieDataStore().data.first()[LegacyCookieJsonKey]
        }
        assertNull(legacyJsonAfterMigration)

        val recreatedRepository = NeteaseCookieRepository(context)
        assertEquals("legacy-cookie", recreatedRepository.getCookiesOnce()["MUSIC_U"])
    }

    private fun clearStorage() {
        context.deleteSharedPreferences(TestNeteaseAuthPrefs)
        runBlocking {
            cookieDataStore().edit { preferences ->
                preferences.clear()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cookieDataStore(): DataStore<Preferences> {
        val containerClass = Class.forName(
            "moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepositoryKt"
        )
        val getter = containerClass.getDeclaredMethod("getCookieDataStore", Context::class.java)
        getter.isAccessible = true
        return getter.invoke(null, context) as DataStore<Preferences>
    }
}
