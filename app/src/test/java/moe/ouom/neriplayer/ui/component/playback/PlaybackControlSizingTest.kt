package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.settings.PlaybackControlSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackControlSizingTest {
    @Test
    fun `small controls retain a usable button size and large controls scale up`() {
        assertEquals(40.dp, PlaybackControlSize.SMALL.scaleButtonSize(42.dp))
        assertEquals(
            60f,
            PlaybackControlSize.LARGE.scaleButtonSize(50.dp).value,
            0.001f
        )
        assertEquals(
            24f,
            PlaybackControlSize.LARGE.scaleIconSize(20.dp).value,
            0.001f
        )
    }
}
