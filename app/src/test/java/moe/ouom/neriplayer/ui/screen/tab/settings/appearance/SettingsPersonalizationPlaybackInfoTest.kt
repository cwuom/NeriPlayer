package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPersonalizationPlaybackInfoTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val repository = AutoSettingsRepository(application)
    private var savedPreferences: Preferences? = null
    private var visible by mutableStateOf(true)

    @Before
    fun prepareSettings() {
        savedPreferences = runBlocking { application.dataStore.data.first() }
        runBlocking {
            repository.setNowPlayingCoverLyricsEnabled(true)
            repository.setNowPlayingImmersiveMode(false)
        }
    }

    @After
    fun restoreSettings() {
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        savedPreferences?.let { preferences ->
            runBlocking { application.dataStore.updateData { preferences } }
        }
    }

    @Test
    fun immersiveModeLocksCoverLyricsWithoutOverwritingTheSavedPreference() {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = UiStyle.Material,
                animateColors = false
            ) {
                if (visible) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SettingsPersonalizationPlaybackInfoCard(
                            autoSettingsRepository = repository,
                            scope = rememberCoroutineScope(),
                            highlightTargetId = null,
                            highlightPulse = 0,
                            onHighlightFinished = null
                        )
                    }
                }
            }
        }

        coverLyricsSwitch().assertIsOn().assertIsEnabled()
        setImmersiveMode(true)
        coverLyricsSwitch().assertIsOff().assertIsNotEnabled()
        assertEquals(true, runBlocking { repository.nowPlayingCoverLyricsEnabledFlow.first() })

        setImmersiveMode(false)
        coverLyricsSwitch().assertIsOn().assertIsEnabled()
        coverLyricsSwitch().performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            coverLyricsSwitch().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                ToggleableState.Off
        }
        assertEquals(false, runBlocking { repository.nowPlayingCoverLyricsEnabledFlow.first() })

        setImmersiveMode(true)
        coverLyricsSwitch().assertIsOff().assertIsNotEnabled()
        setImmersiveMode(false)
        coverLyricsSwitch().assertIsOff().assertIsEnabled()
        assertEquals(false, runBlocking { repository.nowPlayingCoverLyricsEnabledFlow.first() })
    }

    private fun coverLyricsSwitch() = settingSwitch(AutoSettingsKeys.NOW_PLAYING_COVER_LYRICS_ENABLED)

    private fun settingSwitch(key: Preferences.Key<Boolean>) = composeRule.onNodeWithText(
        application.getString(AutoSettingsMetadata.requireSetting(key).titleRes)
    ).onChildren().filterToOne(isToggleable())

    private fun setImmersiveMode(enabled: Boolean) {
        val control = settingSwitch(AutoSettingsKeys.NOW_PLAYING_IMMERSIVE_MODE)
        control.performScrollTo().performClick()
        val expected = if (enabled) ToggleableState.On else ToggleableState.Off
        composeRule.waitUntil(5_000) {
            control.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expected
        }
    }
}
