package moe.ouom.neriplayer.ui.onboarding

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_FONT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2600dp")
class StartupOnboardingExtrasComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var hostView: View

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    // The playback previews run an endless waveform animation, so they need a manually driven clock.
    private fun setStep(hasEndlessAnimation: Boolean = false, content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = !hasEndlessAnimation
        composeRule.setContent {
            hostView = LocalView.current
            Column {
                content()
            }
        }
        settle()
    }

    private fun clickText(text: String) {
        composeRule.onNodeWithText(text).performClick()
        settle()
    }

    private fun toggleSwitch() {
        composeRule.onNode(isToggleable()).performClick()
        settle()
    }

    private fun clickDialogButton(text: String) {
        composeRule.onNodeWithText(text).performClick()
        settle()
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).getBoundsInRoot().top.value

    private fun setLyricScaleSlider(to: Float) {
        composeRule.onNode(
            SemanticsMatcher("lyric scale slider") { node ->
                val range = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range
                range?.start == MIN_LYRIC_FONT_SCALE && range.endInclusive == MAX_LYRIC_FONT_SCALE
            },
            useUnmergedTree = true
        ).performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(to) }
        settle()
    }

    private fun drawHost() {
        val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
        composeRule.runOnIdle { hostView.draw(Canvas(bitmap)) }
    }

    @Test
    fun `permission step requests only missing permissions`() {
        val requests = mutableListOf<String>()
        setStep {
            StartupPermissionContent(
                notificationPermissionSupported = true,
                notificationPermissionGranted = false,
                localMediaPermissionGranted = true,
                onRequestNotificationPermission = { requests += "notification" },
                onRequestLocalMediaPermission = { requests += "media" }
            )
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_notification_desc))
            .assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_granted), useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_allow))
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("notification"), requests)
    }

    @Test
    fun `legacy notification permission is shown as already granted`() {
        setStep {
            StartupPermissionContent(
                notificationPermissionSupported = false,
                notificationPermissionGranted = false,
                localMediaPermissionGranted = false,
                onRequestNotificationPermission = {},
                onRequestLocalMediaPermission = {}
            )
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_notification_legacy_desc))
            .assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_granted))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_permission_allow))
            .assertIsEnabled()
    }

    @Test
    fun `enabling playback source fallback asks for confirmation first`() {
        val changes = mutableListOf<Boolean>()
        setStep {
            StartupPlaybackSourceContent(
                autoSourceSwitchEnabled = false,
                localSourceFallbackEnabled = false,
                onSetFallbackEnabled = { changes += it }
            )
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_playback_sources_partial_status))
            .assertDoesNotExist()
        composeRule.onNode(isToggleable()).assertIsOff()
        toggleSwitch()

        clickDialogButton(string(CoreCommonR.string.action_cancel))
        assertEquals(emptyList<Boolean>(), changes)
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_playback_sources_confirm_title))
            .assertDoesNotExist()

        toggleSwitch()
        clickDialogButton(string(CoreCommonR.string.onboarding_playback_sources_confirm_enable))

        assertEquals(listOf(true), changes)
    }

    @Test
    fun `partially enabled fallback shows its status and enables without confirmation`() {
        val changes = mutableListOf<Boolean>()
        setStep {
            StartupPlaybackSourceContent(
                autoSourceSwitchEnabled = true,
                localSourceFallbackEnabled = false,
                onSetFallbackEnabled = { changes += it }
            )
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_playback_sources_partial_status))
            .assertExists()
        composeRule.onNode(isToggleable()).assertIsOff()
        toggleSwitch()
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_playback_sources_confirm_title))
            .assertExists()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun `disabling fully enabled fallback applies immediately`() {
        val changes = mutableListOf<Boolean>()
        setStep {
            StartupPlaybackSourceContent(
                autoSourceSwitchEnabled = true,
                localSourceFallbackEnabled = true,
                onSetFallbackEnabled = { changes += it }
            )
        }

        composeRule.onNode(isToggleable()).assertIsOn()
        toggleSwitch()

        assertEquals(listOf(false), changes)
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_playback_sources_confirm_title))
            .assertDoesNotExist()
    }

    @Test
    fun `playback controls step links lyric size while it follows the now playing size`() {
        val changes = mutableListOf<PlaybackControlLayoutPreferences>()
        val scales = mutableListOf<Float>()
        setStep(hasEndlessAnimation = true) {
            StartupPlaybackControlsContent(
                preferences = PlaybackControlLayoutPreferences(),
                coverLyricFontScale = 1f,
                onCoverLyricFontScaleChange = { scales += it },
                onPreferencesChange = { changes += it }
            )
        }

        val previewLyric = string(CoreCommonR.string.onboarding_controls_preview_lyric_2)
        assertTrue(topOf(PROGRESS_ELAPSED) < topOf(previewLyric))

        clickText(string(CoreCommonR.string.settings_playback_control_size_large))
        clickText(string(CoreCommonR.string.settings_nowplaying_control_placement_bottom))
        assertTrue(topOf(PROGRESS_ELAPSED) < topOf(previewLyric))
        clickText(string(CoreCommonR.string.settings_nowplaying_control_placement_bottom_with_progress))
        assertTrue(topOf(PROGRESS_ELAPSED) > topOf(previewLyric))
        drawHost()

        assertEquals(
            listOf(
                PlaybackControlLayoutPreferences(
                    nowPlayingSize = PlaybackControlSize.LARGE,
                    lyricsSize = PlaybackControlSize.LARGE
                ),
                PlaybackControlLayoutPreferences(
                    nowPlayingPlacement = NowPlayingControlPlacement.BOTTOM,
                    nowPlayingSize = PlaybackControlSize.LARGE,
                    lyricsSize = PlaybackControlSize.LARGE
                ),
                PlaybackControlLayoutPreferences(
                    nowPlayingPlacement = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS,
                    nowPlayingSize = PlaybackControlSize.LARGE,
                    lyricsSize = PlaybackControlSize.LARGE
                )
            ),
            changes
        )
    }

    @Test
    fun `playback controls step keeps a custom lyric size and commits the cover lyric scale`() {
        val changes = mutableListOf<PlaybackControlLayoutPreferences>()
        val scales = mutableListOf<Float>()
        setStep(hasEndlessAnimation = true) {
            StartupPlaybackControlsContent(
                preferences = PlaybackControlLayoutPreferences(lyricsSize = PlaybackControlSize.SMALL),
                coverLyricFontScale = 0.6f,
                onCoverLyricFontScaleChange = { scales += it },
                onPreferencesChange = { changes += it }
            )
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_lyrics_size_value, 60))
            .assertExists()

        clickText(string(CoreCommonR.string.settings_playback_control_size_large))
        setLyricScaleSlider(to = 1.2f)

        assertEquals(
            listOf(
                PlaybackControlLayoutPreferences(
                    nowPlayingSize = PlaybackControlSize.LARGE,
                    lyricsSize = PlaybackControlSize.SMALL
                )
            ),
            changes
        )
        assertEquals(1, scales.size)
        assertEquals(1.2f, scales.single(), 0.001f)
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_lyrics_size_value, 120))
            .assertExists()
    }

    @Test
    fun `lyrics step warns before diverging from the now playing control size`() {
        val changes = mutableListOf<PlaybackControlLayoutPreferences>()
        val scales = mutableListOf<Float>()
        setStep(hasEndlessAnimation = true) {
            StartupLyricsContent(
                preferences = PlaybackControlLayoutPreferences(),
                lyricFontScale = 1f,
                onLyricFontScaleChange = { scales += it },
                onPreferencesChange = { changes += it }
            )
        }
        val warningTitle = string(CoreCommonR.string.onboarding_lyrics_control_size_warning_title)

        clickText(string(CoreCommonR.string.settings_playback_control_size_medium))
        composeRule.onNodeWithText(warningTitle).assertDoesNotExist()

        clickText(string(CoreCommonR.string.settings_playback_control_size_small))
        composeRule.onNodeWithText(warningTitle).assertExists()
        clickDialogButton(string(CoreCommonR.string.action_cancel))
        assertEquals(emptyList<PlaybackControlLayoutPreferences>(), changes)

        clickText(string(CoreCommonR.string.settings_playback_control_size_small))
        clickDialogButton(string(CoreCommonR.string.onboarding_lyrics_control_size_warning_confirm))
        clickText(string(CoreCommonR.string.settings_playback_control_size_medium))

        assertEquals(
            listOf(
                PlaybackControlLayoutPreferences(lyricsSize = PlaybackControlSize.SMALL),
                PlaybackControlLayoutPreferences(lyricsSize = PlaybackControlSize.MEDIUM)
            ),
            changes
        )

        setLyricScaleSlider(to = 0.7f)
        assertEquals(0.7f, scales.single(), 0.001f)
        composeRule.onNodeWithText(string(CoreCommonR.string.onboarding_lyrics_size_value, 70))
            .assertExists()
    }

    private companion object {
        const val PROGRESS_ELAPSED = "1:18"
    }
}
