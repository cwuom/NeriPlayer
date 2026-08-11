package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseRemotePlaylistPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun loadingPickerCanBeDismissed() {
        var dismissCount = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                NeteaseRemotePlaylistPickerDialog(
                    playlists = emptyList(),
                    loading = true,
                    errorMessage = null,
                    onPlaylistClick = {},
                    onDismissRequest = { dismissCount += 1 }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_cancel))
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, dismissCount)
        }
    }
}
