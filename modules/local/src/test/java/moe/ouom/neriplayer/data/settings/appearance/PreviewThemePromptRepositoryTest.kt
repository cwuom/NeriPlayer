package moe.ouom.neriplayer.data.settings.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class PreviewThemePromptRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `legacy settings without a style still need a preview choice`() = runTest {
        withStore {
            assertTrue(PreviewThemePromptRepository(it).shouldPromptFlow.first())
            assertNull(it.data.first()[SettingsKeys.UI_STYLE])
        }
    }

    @Test
    fun `an explicit material style still needs a preview choice`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }

            assertTrue(PreviewThemePromptRepository(store).shouldPromptFlow.first())
        }
    }

    @Test
    fun `declining survives reopening storage and keeps the existing theme`() = runTest {
        val file = temporary.newFile("settings.preferences_pb")
        withStore(file) { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }

            PreviewThemePromptRepository(store).answer(enable = false)

            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
        withStore(file) { store ->
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
    }

    @Test
    fun `accepting publishes clean and updates the startup cache after one commit`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.DYNAMIC_COLOR] = false }
            val writes = ControlledWritesStore(store)
            val preferences = InMemorySharedPreferencesRegistry()
            val context = preferences.install(mock(Context::class.java))
            val repository = PreviewThemePromptRepository(writes) { style ->
                assertEquals(1, writes.successfulWrites)
                persistThemeUiStyle(context, style)
            }

            repository.answer(enable = true)

            val styles = store.data.map { UiStyle.fromStorage(it[SettingsKeys.UI_STYLE]) }
            assertEquals(UiStyle.Clean, styles.first())
            assertFalse(repository.shouldPromptFlow.first())
            assertFalse(store.data.first()[SettingsKeys.DYNAMIC_COLOR] ?: true)
            assertEquals("clean", preferences["theme_snapshot_cache"].snapshot()["ui_style"])
            assertEquals(1, writes.successfulWrites)
        }
    }

    @Test
    fun `a failed answer keeps the choice pending without changing theme or cache`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }
            val failure = IOException("settings write failed")
            val writes = ControlledWritesStore(store).apply { writeFailure = failure }
            val preferences = InMemorySharedPreferencesRegistry()
            val context = preferences.install(mock(Context::class.java))
            val repository = PreviewThemePromptRepository(writes) { persistThemeUiStyle(context, it) }

            val result = runCatching { repository.answer(enable = true) }

            assertSame(failure, result.exceptionOrNull())
            assertTrue(repository.shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
            assertTrue(preferences["theme_snapshot_cache"].snapshot().isEmpty())
            assertEquals(0, writes.successfulWrites)
        }
    }

    @Test
    fun `already enabled clean counts as answered after switching back to material`() = runTest {
        val file = temporary.newFile("settings.preferences_pb")
        withStore(file) { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "clean" }

            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }
        }
        withStore(file) { store ->
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
    }

    private suspend fun TestScope.withStore(
        file: File = temporary.newFile("settings.preferences_pb"),
        action: suspend (DataStore<Preferences>) -> Unit
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
            produceFile = { file }
        )
        try {
            action(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    private class ControlledWritesStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var writeFailure: IOException? = null
        var successfulWrites = 0
            private set
        override val data = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeFailure?.let { throw it }
            return delegate.updateData(transform).also { successfulWrites++ }
        }
    }
}
