package moe.ouom.neriplayer.ui.screen.tab.settings.component.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mockConstruction
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsBackupSyncPreferenceRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sharedPreferences get() = context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)

    @Before
    fun clearHistoryPreferences() {
        sharedPreferences.edit().clear().commit()
    }

    @Test
    fun `unavailable legacy preferences are returned as an unavailable load instead of escaping`() {
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            doAnswer { throw SyncStorageUnavailableException() }.`when`(store).getLegacyPlayHistoryUpdateModeName()
        }.use {
            val loaded = load(needsHistory = true, needsDataSaver = false)

            assertTrue(loaded is BackupSyncPreferenceLoad.Unavailable)
            assertNull(loaded.snapshot.playHistoryUpdateMode)
            assertFalse(sharedPreferences.contains("play_history_update_mode"))
        }
    }

    @Test
    fun `unavailable data saver preferences are returned as an unavailable load instead of escaping`() {
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            doAnswer { throw SyncStorageUnavailableException() }.`when`(store).isDataSaverMode()
        }.use {
            val loaded = load(needsHistory = false, needsDataSaver = true)

            assertTrue(loaded is BackupSyncPreferenceLoad.Unavailable)
            assertNull(loaded.snapshot.dataSaverMode)
        }
    }

    @Test
    fun `an explicit history mode loads without opening secure legacy storage`() {
        sharedPreferences.edit().putString("play_history_update_mode", "EVERY_15_MINUTES").commit()
        mockConstruction(SecureTokenStorage::class.java) { _, _ -> error("secure storage must not be opened") }.use { opened ->
            val loaded = load(needsHistory = true, needsDataSaver = false)

            assertTrue(loaded is BackupSyncPreferenceLoad.Loaded)
            assertEquals(PlayHistoryUpdateMode.EVERY_15_MINUTES, loaded.snapshot.playHistoryUpdateMode)
            assertTrue(opened.constructed().isEmpty())
        }
    }

    @Test
    fun `unknown legacy history remains eligible for migration after storage recovers`() {
        var available = false
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            doAnswer {
                if (!available) throw SyncStorageUnavailableException()
                "BATCHED_30"
            }.`when`(store).getLegacyPlayHistoryUpdateModeName()
        }.use {
            assertTrue(load(needsHistory = true, needsDataSaver = false) is BackupSyncPreferenceLoad.Unavailable)
            assertFalse(sharedPreferences.contains("play_history_update_mode"))

            available = true
            val recovered = load(needsHistory = true, needsDataSaver = false)

            assertTrue(recovered is BackupSyncPreferenceLoad.Loaded)
            assertEquals(PlayHistoryUpdateMode.EVERY_30_MINUTES, recovered.snapshot.playHistoryUpdateMode)
            assertEquals("EVERY_30_MINUTES", sharedPreferences.getString("play_history_update_mode", null))
        }
    }

    @Test
    fun `an unavailable data saver read retains a separately known history mode`() {
        sharedPreferences.edit().putString("play_history_update_mode", "EVERY_15_MINUTES").commit()
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            doAnswer { throw SyncStorageUnavailableException() }.`when`(store).isDataSaverMode()
        }.use {
            val loaded = load(needsHistory = true, needsDataSaver = true)

            assertTrue(loaded is BackupSyncPreferenceLoad.Unavailable)
            assertEquals(PlayHistoryUpdateMode.EVERY_15_MINUTES, loaded.snapshot.playHistoryUpdateMode)
            assertNull(loaded.snapshot.dataSaverMode)
        }
    }

    @Test
    fun `a readable empty legacy store provides the normal default rather than unknown`() {
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            `when`(store.isDataSaverMode()).thenReturn(false)
        }.use {
            val loaded = load(needsHistory = true, needsDataSaver = true)

            assertTrue(loaded is BackupSyncPreferenceLoad.Loaded)
            assertEquals(PlayHistoryUpdateMode.IMMEDIATE, loaded.snapshot.playHistoryUpdateMode)
            assertEquals(false, loaded.snapshot.dataSaverMode)
        }
    }

    @Test
    fun `loading preferences propagates cancellation`() {
        mockConstruction(SecureTokenStorage::class.java) { store, _ ->
            doAnswer { throw CancellationException("cancelled") }.`when`(store).isDataSaverMode()
        }.use {
            assertThrows(CancellationException::class.java) { load(needsHistory = false, needsDataSaver = true) }
        }
    }

    private fun load(needsHistory: Boolean, needsDataSaver: Boolean): BackupSyncPreferenceLoad =
        loadBackupSyncPreferenceSnapshot(context, needsHistory, needsDataSaver)
}
