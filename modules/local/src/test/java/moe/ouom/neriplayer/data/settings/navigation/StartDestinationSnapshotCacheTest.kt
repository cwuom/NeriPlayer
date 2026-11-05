package moe.ouom.neriplayer.data.settings.navigation

import android.os.Looper
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic

/**
 * 覆盖启动页快照的缓存语义: 命中即读、部分缓存视为缺失、未知态不被当成默认值,
 * 以及 DataStore 偏好到快照的投影与写透.
 *
 * 需要访问 DataStore 的用例隔离并恢复设置，避免影响其它设置测试
 */
class StartDestinationSnapshotCacheTest {
    private val preferences = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(preferences)

    @Test
    fun `ready cache is served as stored`() {
        preferences[SNAPSHOT_CACHE].edit()
            .putString(START_DESTINATION_KEY, "library")
            .commit()

        assertEquals(
            StartDestinationSnapshot(startDestination = "library"),
            readStartDestinationSnapshotOrNull(context)
        )
    }

    @Test
    fun `partial cache is treated as missing`() {
        preferences[SNAPSHOT_CACHE].edit().putString("unrelated", "x").commit()

        assertNull(readCachedStartDestinationSnapshot(context))
    }

    @Test
    fun `stored route is read back verbatim`() {
        preferences[SNAPSHOT_CACHE].edit()
            .putString(START_DESTINATION_KEY, "settings")
            .commit()

        assertEquals(
            StartDestinationSnapshot(startDestination = "settings"),
            readCachedStartDestinationSnapshot(context)
        )
    }

    @Test
    fun `preferences map the stored route and fall back to the default`() {
        assertEquals(
            StartDestinationSnapshot(startDestination = "settings"),
            mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to "settings")
                .toStartDestinationSnapshot()
        )
        assertEquals(
            StartDestinationSnapshot(),
            mutablePreferencesOf().toStartDestinationSnapshot()
        )
    }

    @Test
    fun `write through updates the cached snapshot`() {
        persistStartDestinationSnapshot(
            context,
            StartDestinationSnapshot(startDestination = "library")
        )

        assertEquals(
            StartDestinationSnapshot(startDestination = "library"),
            readCachedStartDestinationSnapshot(context)
        )
        assertEquals(
            mapOf(START_DESTINATION_KEY to "library"),
            preferences[SNAPSHOT_CACHE].snapshot()
        )
    }

    @Test
    fun `explicit writes keep replacing an existing cached choice`() {
        persistStartDestinationSnapshot(context, StartDestinationSnapshot("home"))
        persistStartDestinationSnapshot(context, StartDestinationSnapshot("library"))
        persistStartDestinationSnapshot(context, StartDestinationSnapshot("settings"))

        assertEquals(StartDestinationSnapshot("settings"), readCachedStartDestinationSnapshot(context))
    }

    /** 先监听缓存再触发异步补热，最终解绑监听器以保持各用例隔离 */
    @Test
    fun `unknown cache on the main thread stays unknown until settings warm it`() = runBlocking {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = { it[SettingsKeys.DEFAULT_START_DESTINATION] = "library" }
        ) {
            val warmed = CountDownLatch(1)
            val cache = preferences[SNAPSHOT_CACHE]
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == START_DESTINATION_KEY) warmed.countDown()
            }
            cache.registerOnSharedPreferenceChangeListener(listener)
            try {
                mockStatic(Looper::class.java).use { looper ->
                    looper.`when`<Looper> { Looper.myLooper() }.thenReturn(null)
                    looper.`when`<Looper> { Looper.getMainLooper() }.thenReturn(null)
                    assertNull(readStartDestinationSnapshotOrNull(context))
                }
                assertTrue("settings must warm the missing cache", warmed.await(10, TimeUnit.SECONDS))
                assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
            } finally {
                cache.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    private companion object {
        const val SNAPSHOT_CACHE = "start_destination_snapshot_cache"
        const val START_DESTINATION_KEY = "start_destination"
    }
}
