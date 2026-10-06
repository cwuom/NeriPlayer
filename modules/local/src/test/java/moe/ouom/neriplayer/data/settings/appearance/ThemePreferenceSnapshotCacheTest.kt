package moe.ouom.neriplayer.data.settings.appearance

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ThemePreferenceSnapshotCacheTest {
    private val preferences = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(preferences)

    @Test
    fun `complete theme cache is returned as stored`() {
        preferences[THEME_CACHE].edit()
            .putBoolean("dynamic_color", false)
            .putBoolean("force_dark", true)
            .putBoolean("follow_system_dark", false)
            .commit()

        assertEquals(
            ThemePreferenceSnapshot(dynamicColor = false, forceDark = true, followSystemDark = false),
            readThemePreferenceSnapshotSync(context)
        )
    }

    @Test
    fun `partial cache on the main thread returns defaults and warms the cache`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = {
                it[SettingsKeys.DYNAMIC_COLOR] = false
                it[SettingsKeys.FORCE_DARK] = true
            }
        ) {
            val cache = preferences[THEME_CACHE]
            cache.edit().putBoolean("dynamic_color", true).commit()
            val warmed = CountDownLatch(1)
            cache.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "follow_system_dark") warmed.countDown()
            }

            // JVM 桩里的 Looper.myLooper() 与 getMainLooper() 都为 null, 等价于主线程
            assertEquals(ThemePreferenceSnapshot(), readThemePreferenceSnapshotSync(context))

            assertTrue(warmed.await(30, TimeUnit.SECONDS))
            assertEquals(
                mapOf("dynamic_color" to false, "force_dark" to true, "follow_system_dark" to true),
                cache.snapshot()
            )
        }
    }

    @Test
    fun `background read loads explicit settings and persists them`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = {
                it[SettingsKeys.DYNAMIC_COLOR] = false
                it[SettingsKeys.FORCE_DARK] = true
                it[SettingsKeys.FOLLOW_SYSTEM_DARK] = false
            }
        ) {
            val expected = ThemePreferenceSnapshot(dynamicColor = false, forceDark = true, followSystemDark = false)

            assertEquals(expected, readOffMainThread())
            assertEquals(
                mapOf("dynamic_color" to false, "force_dark" to true, "follow_system_dark" to false),
                preferences[THEME_CACHE].snapshot()
            )
        }
    }

    @Test
    fun `background read falls back to theme defaults for unset settings`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            preferences[THEME_CACHE].edit().putBoolean("dynamic_color", false).putBoolean("force_dark", true).commit()

            assertEquals(ThemePreferenceSnapshot(), readOffMainThread())
            assertEquals(
                mapOf("dynamic_color" to true, "force_dark" to false, "follow_system_dark" to true),
                preferences[THEME_CACHE].snapshot()
            )
        }
    }

    private fun readOffMainThread(): ThemePreferenceSnapshot {
        return mockStatic(Looper::class.java).use { looper ->
            looper.`when`<Looper> { Looper.myLooper() }.thenReturn(mock(Looper::class.java))
            readThemePreferenceSnapshotSync(context)
        }
    }

    private companion object {
        const val THEME_CACHE = "theme_snapshot_cache"
    }
}
