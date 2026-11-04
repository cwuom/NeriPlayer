package moe.ouom.neriplayer.data.settings.navigation

import android.os.Looper
import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

/**
 * 覆盖启动页快照的缓存语义: 命中即读、部分缓存视为缺失、未知态不被当成默认值,
 * 以及 DataStore 偏好到快照的投影与写透.
 *
 * 刻意不触发 `dataStore.edit`: 共享的 settings DataStore 在宿主测试里可能因临时文件
 * 重命名冲突而失败, 而快照逻辑本身不需要写入 DataStore 即可验证.
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
    fun `unknown cache is not reported as a default choice`() {
        assertNull(
            mockStatic(Looper::class.java).use { looper ->
                looper.`when`<Looper> { Looper.myLooper() }.thenReturn(null)
                looper.`when`<Looper> { Looper.getMainLooper() }.thenReturn(null)
                readCachedStartDestinationSnapshot(context)
            }
        )
    }

    private companion object {
        const val SNAPSHOT_CACHE = "start_destination_snapshot_cache"
        const val START_DESTINATION_KEY = "start_destination"
    }
}
