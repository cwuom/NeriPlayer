package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.setAutoSetting
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDownloadSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun followPlaybackQualityCardRevealsIndependentPlatformOptionsWhenDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val setting = AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality
        val followPlaybackTitle = context.getString(
            R.string.settings_download_follow_playback_audio_quality
        )
        val neteaseTitle = context.getString(R.string.settings_download_netease_audio_quality)
        val youtubeTitle = context.getString(R.string.settings_download_youtube_audio_quality)
        val biliTitle = context.getString(R.string.settings_download_bili_audio_quality)
        val originalValue = runBlocking { context.autoSettingFlow(setting).first() }

        try {
            runBlocking { context.setAutoSetting(setting, true) }
            composeRule.setContent {
                MaterialTheme {
                    Column {
                        SettingsDownloadQualityFollowPlaybackCard(
                            highlightTargetId = null,
                            highlightPulse = 0,
                            onHighlightFinished = null
                        )
                        MiuixSettingsSectionCard(modifier = Modifier) {
                            SettingsDownloadSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                onNavigateToDownloadManager = {}
                            )
                        }
                    }
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag(
                DOWNLOAD_QUALITY_FOLLOW_PLAYBACK_CARD_TEST_TAG
            ).assertExists()
            composeRule.onAllNodesWithText(neteaseTitle).assertCountEquals(0)
            composeRule.onAllNodesWithText(youtubeTitle).assertCountEquals(0)
            composeRule.onAllNodesWithText(biliTitle).assertCountEquals(0)
            composeRule.onNodeWithText(followPlaybackTitle).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(neteaseTitle).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText(neteaseTitle).assertExists()
            composeRule.onNodeWithText(youtubeTitle).assertExists()
            composeRule.onNodeWithText(biliTitle).assertExists()
        } finally {
            runBlocking { context.setAutoSetting(setting, originalValue) }
        }
    }
}
