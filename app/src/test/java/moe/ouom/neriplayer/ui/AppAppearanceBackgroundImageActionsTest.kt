package moe.ouom.neriplayer.ui

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.ui.settings.route.AppAppearanceSettingsActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 32, 33, 35], application = Application::class)
class AppAppearanceBackgroundImageActionsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repository = SettingsRepository(context)
    private var originalSettings: Preferences? = null

    @Before
    fun rememberSettings() = runBlocking {
        originalSettings = context.dataStore.data.first()
    }

    @After
    fun restoreSettings() = runBlocking {
        originalSettings?.let { snapshot -> context.dataStore.updateData { snapshot } }
        Unit
    }

    @Test
    fun clearingBackgroundKeepsAnExplicitlyEnabledBlurPreference() = runBlocking {
        assertClearingPreservesBlurPreference(enhanced = true)
    }

    @Test
    fun clearingBackgroundKeepsAnExplicitlyDisabledBlurPreference() = runBlocking {
        assertClearingPreservesBlurPreference(enhanced = false)
    }

    @Test
    @Config(sdk = [28, 32])
    fun selectingBackgroundBelowApi33PreservesIndependentBlurPreferences() = runBlocking {
        val preferences = listOf(false to false, false to true, true to false, true to true)
        for ((index, preference) in preferences.withIndex()) {
            val (advanced, enhanced) = preference
            repository.setAdvancedBlurEnabled(advanced)
            repository.setEnhancedAdvancedBlurEnabled(enhanced)
            val image = Uri.parse("content://neriplayer.test/background/selected-$index")

            changeBackground(repository, image)

            assertAppearance(repository, image.toString(), advanced, enhanced)
        }
    }

    @Test
    @Config(sdk = [33, 35])
    fun selectingBackgroundFromApi33AutomaticallyEnablesBothBlurPreferences() = runBlocking {
        repository.setAdvancedBlurEnabled(false)
        repository.setEnhancedAdvancedBlurEnabled(false)
        val image = Uri.parse("content://neriplayer.test/background/selected")

        changeBackground(repository, image)

        assertAppearance(repository, image.toString(), advanced = true, enhanced = true)
    }

    private suspend fun assertClearingPreservesBlurPreference(enhanced: Boolean) {
        for (advanced in listOf(false, true)) {
            repository.setBackgroundImageUri("content://neriplayer.test/background/original")
            repository.setAdvancedBlurEnabled(advanced)
            repository.setEnhancedAdvancedBlurEnabled(enhanced)
            assertEquals(enhanced, repository.enhancedAdvancedBlurEnabledFlow.first())

            changeBackground(repository, null)

            assertAppearance(repository, image = null, advanced = advanced, enhanced = enhanced)
        }
    }

    private suspend fun changeBackground(repository: SettingsRepository, image: Uri?) {
        withTimeout(10_000L) {
            coroutineScope {
                AppAppearanceSettingsActions(repository, this) {}.onBackgroundImageChange(image)
            }
        }
    }

    private suspend fun assertAppearance(
        repository: SettingsRepository,
        image: String?,
        advanced: Boolean,
        enhanced: Boolean
    ) {
        assertEquals("the background image URI must be persisted", image, repository.backgroundImageUriFlow.first())
        assertEquals("the independent primary blur preference must be retained", advanced,
            repository.advancedBlurEnabledFlow.first())
        assertEquals("the independent enhanced blur preference must be retained", enhanced,
            repository.enhancedAdvancedBlurEnabledFlow.first())
    }
}
