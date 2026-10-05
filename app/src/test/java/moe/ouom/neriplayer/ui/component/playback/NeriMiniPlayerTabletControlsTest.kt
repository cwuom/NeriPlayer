package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriMiniPlayerTabletControlsTest {
    @Test
    fun widePhoneDoesNotGainTabletControls() {
        assertFalse(shouldShowMiniPlayerSkipControls(360, 1280.dp))
        assertFalse(shouldShowMiniPlayerSkipControls(599, 800.dp))
    }

    @Test
    fun tabletPortraitAndLandscapeBothShowSkipControls() {
        assertTrue(shouldShowMiniPlayerSkipControls(600, 784.dp))
        assertTrue(shouldShowMiniPlayerSkipControls(800, 1264.dp))
    }

    @Test
    fun narrowTabletReservesMetadataBeforeShowingThreeControls() {
        assertFalse(shouldShowMiniPlayerSkipControls(800, 344.dp))
        assertFalse(shouldShowMiniPlayerSkipControls(800, 375.dp))
        assertTrue(shouldShowMiniPlayerSkipControls(800, 376.dp))
    }
}
