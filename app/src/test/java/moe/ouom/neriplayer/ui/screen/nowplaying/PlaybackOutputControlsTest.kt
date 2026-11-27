package moe.ouom.neriplayer.ui.screen.nowplaying

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackOutputControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manageLabel get() = context.getString(CoreR.string.lan_manage)

    @Test
    fun localVolumeReflectsRoomOrHardwareVolumeChangesWhileOpen() {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        compose.setContent { MaterialTheme { VolumeControlSheetContent() } }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        fun shownVolume() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(max.toFloat(), shownVolume(), 0f)
        compose.runOnIdle {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
            context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION").setPackage(context.packageName))
        }
        compose.waitUntil { shownVolume() == (max / 2).toFloat() }
    }

    @Test
    fun canOpenRoomControlsWhileKeepingVolumeAdjustable() {
        val volume = mutableFloatStateOf(0.3f)
        compose.setContent {
            MaterialTheme {
                PlaybackOutputSheetContent(
                    roomActive = false,
                    volumeContent = {
                        Slider(
                            value = volume.floatValue,
                            onValueChange = { volume.floatValue = it },
                            modifier = Modifier.testTag("volume")
                        )
                    },
                    roomContent = { Text("Join a room") }
                )
            }
        }

        val slider = compose.onNodeWithTag("volume")
        slider.assertIsDisplayed()
        compose.onNodeWithText("Join a room").assertDoesNotExist()
        compose.onNodeWithText(manageLabel).performClick()
        compose.onNodeWithText("Join a room").assertIsDisplayed()
        slider.assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        compose.runOnIdle { assertEquals(0.7f, volume.floatValue, 0.001f) }
    }

    @Test
    fun longManagementPageAnimatesToItsVisibleHeightAndStaysScrollable() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.heightIn(max = 240.dp).testTag("output")) {
                    PlaybackOutputSheetContent(
                        roomActive = false,
                        volumeContent = { Text("Local volume") },
                        roomContent = {
                            Column {
                                Spacer(Modifier.height(2400.dp))
                                Text("Last room control")
                            }
                        }
                    )
                }
            }
        }
        fun height() = compose.onNodeWithTag("output").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val initial = height()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(manageLabel).performClick()
        compose.mainClock.advanceTimeBy(112)
        val midway = height()
        compose.onNodeWithText("Local volume").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        assertTrue("Expected the sheet height to pass through an intermediate size", midway > initial && midway < height())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Last room control").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun roomAlreadyActiveShowsItsControlsAndLocalVolumeWithoutATap() {
        compose.setContent {
            MaterialTheme {
                PlaybackOutputSheetContent(
                    roomActive = true,
                    volumeContent = { Text("Local volume") },
                    roomContent = { Text("Leave room") }
                )
            }
        }

        compose.onNodeWithText("Local volume").assertIsDisplayed()
        compose.onNodeWithText("Leave room").assertIsDisplayed()
    }

    @Test
    fun openingAndLeavingARoomUpdatesTheExistingSheet() {
        val active = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PlaybackOutputSheetContent(
                    roomActive = active.value,
                    volumeContent = { Text("Local volume") },
                    roomContent = { Text("Room controls") }
                )
            }
        }

        compose.onNodeWithText("Room controls").assertDoesNotExist()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("Room controls").assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithText("Room controls").assertDoesNotExist()
        compose.onNodeWithText(manageLabel).assertIsDisplayed()
        compose.onNodeWithText("Local volume").assertIsDisplayed()
    }

    @Test
    fun roomActionsRemainReachableFromAShortSheet() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(144.dp)) {
                    PlaybackOutputSheetContent(
                        roomActive = true,
                        volumeContent = { Text("Local volume") },
                        roomContent = {
                            Column {
                                Spacer(Modifier.height(320.dp))
                                Button(onClick = {}) { Text("Leave room") }
                            }
                        }
                    )
                }
            }
        }

        compose.onNodeWithText("Leave room").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun iconAnnouncesTheDeviceAndListenTogetherWhenIdleAndRoomManagementWhenActive() {
        val active = mutableStateOf(false)
        val deviceName = "Test speakers"
        val listenTogetherLabel = context.getString(CoreR.string.listen_together_title)
        compose.setContent {
            MaterialTheme {
                PlaybackOutputIcon(roomActive = active.value, device = deviceName to Icons.Default.SpeakerGroup)
            }
        }

        compose.onNode(
            hasContentDescription(deviceName, substring = true) and
                hasContentDescription(listenTogetherLabel, substring = true)
        ).assertIsDisplayed()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithContentDescription(manageLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(deviceName, substring = true).assertDoesNotExist()
    }
}
