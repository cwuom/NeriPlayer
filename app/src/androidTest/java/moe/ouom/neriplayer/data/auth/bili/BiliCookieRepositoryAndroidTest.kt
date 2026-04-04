package moe.ouom.neriplayer.data.auth.bili

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

private const val TestBiliAuthPrefs = "bili_auth_secure_prefs"

@RunWith(AndroidJUnit4::class)
class BiliCookieRepositoryAndroidTest {

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
        val firstRepository = BiliCookieRepository(context)

        firstRepository.saveCookies(
            cookies = mapOf(
                "SESSDATA" to "sess-cookie",
                "DedeUserID" to "12345",
                "bili_jct" to "csrf-token"
            ),
            savedAt = savedAt
        )

        val recreatedRepository = BiliCookieRepository(context)
        assertEquals("sess-cookie", recreatedRepository.getCookiesOnce()["SESSDATA"])
        assertEquals("12345", recreatedRepository.getCookiesOnce()["DedeUserID"])
        assertEquals("csrf-token", recreatedRepository.getCookiesOnce()["bili_jct"])
        assertEquals(
            SavedCookieAuthState.Valid,
            recreatedRepository.getAuthHealth(now = savedAt + 1_000L).state
        )

        recreatedRepository.clear()

        val clearedRepository = BiliCookieRepository(context)
        assertTrue(clearedRepository.getCookiesOnce().isEmpty())
        assertEquals(SavedCookieAuthState.Missing, clearedRepository.getAuthHealthOnce().state)
    }

    @Test
    fun legacyCookies_areMigratedIntoEncryptedStorage_andLegacyStoreIsCleared() {
        runBlocking {
            cookieDataStore().edit { preferences ->
                preferences[BiliCookieKeys.COOKIE_JSON] = JSONObject(
                    mapOf(
                        "SESSDATA" to "legacy-sess",
                        "DedeUserID" to "67890",
                        "bili_jct" to "legacy-csrf"
                    )
                ).toString()
            }
        }

        val repository = BiliCookieRepository(context)

        assertEquals("legacy-sess", repository.getCookiesOnce()["SESSDATA"])
        assertEquals("67890", repository.getCookiesOnce()["DedeUserID"])
        assertEquals("legacy-csrf", repository.getCookiesOnce()["bili_jct"])
        assertEquals(SavedCookieAuthState.Valid, repository.getAuthHealthOnce().state)

        val legacyJsonAfterMigration = runBlocking {
            cookieDataStore().data.first()[BiliCookieKeys.COOKIE_JSON]
        }
        assertNull(legacyJsonAfterMigration)

        val recreatedRepository = BiliCookieRepository(context)
        assertEquals("legacy-sess", recreatedRepository.getCookiesOnce()["SESSDATA"])
    }

    private fun clearStorage() {
        context.deleteSharedPreferences(TestBiliAuthPrefs)
        runBlocking {
            cookieDataStore().edit { preferences ->
                preferences.clear()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cookieDataStore(): DataStore<Preferences> {
        val containerClass = Class.forName(
            "moe.ouom.neriplayer.data.auth.bili.BiliCookieRepositoryKt"
        )
        val getter = containerClass.getDeclaredMethod("getBiliCookieStore", Context::class.java)
        getter.isAccessible = true
        return getter.invoke(null, context) as DataStore<Preferences>
    }
}
