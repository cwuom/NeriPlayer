package moe.ouom.neriplayer.ui.dialog

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLocalMetadataWriteBackConfirmDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.audio.AudioEffectsAdvancedCard
import moe.ouom.neriplayer.ui.screen.tab.settings.audio.AudioEffectsEditor
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectoryPendingChangeDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.PendingDownloadDirectoryDialogPort
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogActionLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<String>()

    private fun showDialog(fontScale: Float = 1.3f, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false,
                    content = content
                )
            }
        }
        settle()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun clickAction(labelRes: Int) {
        val node = composeRule.onNodeWithText(context.getString(labelRes))
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performClick()
        settle()
    }

    @Test
    fun downloadDirectoryChoicesRemainIndependentlyReachable() {
        showDialog {
            DownloadDirectoryPendingChangeDialogContent(
                PendingDownloadDirectoryDialogPort(
                    targetSummary = "Music/archive",
                    showTargetConflictWarning = true,
                    confirmEnabled = true,
                    onCancel = { actions += "cancel" },
                    onSkip = { actions += "skip" },
                    onConfirm = { actions += "move" }
                )
            )
        }

        clickAction(CoreCommonR.string.settings_download_directory_migrate_cancel)
        clickAction(CoreCommonR.string.settings_download_directory_migrate_skip)
        clickAction(CoreCommonR.string.settings_download_directory_migrate_confirm)

        composeRule.runOnIdle { assertEquals(listOf("cancel", "skip", "move"), actions) }
    }

    @Test
    fun localMetadataChoicesRemainIndependentlyReachable() {
        showDialog {
            EditSongLocalMetadataWriteBackConfirmDialog(
                isSaving = false,
                onWriteToLocal = { actions += "write" },
                onSaveInAppOnly = { actions += "app" },
                onCancel = { actions += "cancel" }
            )
        }

        clickAction(CoreCommonR.string.local_song_metadata_write_confirm_app_only)
        clickAction(CoreCommonR.string.action_cancel)
        clickAction(CoreCommonR.string.local_song_metadata_write_confirm_write)

        composeRule.runOnIdle { assertEquals(listOf("app", "cancel", "write"), actions) }
    }

    @Test
    fun backgroundPermissionChoicesRemainReachableWithLargeText() {
        showDialog(fontScale = 2f) {
            UsbExclusiveBackgroundPermissionDialog(
                batteryOptimizationAllowed = false,
                onRequestBatteryOptimization = { actions += "battery" },
                onOpenAppSettings = { actions += "settings" },
                onNeverShowAgain = { actions += "never" },
                onDismiss = { actions += "later" }
            )
        }

        clickAction(CoreCommonR.string.settings_usb_exclusive_background_permission_battery)
        clickAction(CoreCommonR.string.settings_usb_exclusive_background_permission_app_settings)
        clickAction(CoreCommonR.string.settings_usb_exclusive_background_permission_never)
        clickAction(CoreCommonR.string.settings_usb_exclusive_background_permission_later)

        composeRule.runOnIdle {
            assertEquals(listOf("battery", "settings", "never", "later"), actions)
        }
    }

    @Test
    fun audioResetConfirmationClosesWhenOutputChanges() {
        var route by mutableStateOf(AudioOutputRoute.SPEAKER)
        showDialog {
            val editor = remember(route) { AudioEffectsEditor(route) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AudioEffectsAdvancedCard(
                    settings = AudioEffectsSettings(perOutputEnabled = true),
                    route = route,
                    editor = editor
                )
            }
        }
        clickAction(CoreCommonR.string.audio_effects_reset_all)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.audio_effects_reset_all_confirm))
            .assertIsDisplayed()

        composeRule.runOnIdle { route = AudioOutputRoute.BLUETOOTH }
        settle()

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.audio_effects_reset_all_confirm))
            .assertDoesNotExist()
    }
}
