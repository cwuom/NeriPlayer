package moe.ouom.neriplayer.ui.screen.nowplaying.dlna

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.CastConnected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DlnaOutputControlSheetTest {

    @Test
    fun localVolumeIcon_followsVolumeLevel() {
        assertEquals(Icons.AutoMirrored.Outlined.VolumeOff, localVolumeIcon(volume = 0, maxVolume = 15))
        assertEquals(Icons.AutoMirrored.Outlined.VolumeDown, localVolumeIcon(volume = 5, maxVolume = 15))
        assertEquals(Icons.AutoMirrored.Outlined.VolumeUp, localVolumeIcon(volume = 8, maxVolume = 15))
        assertEquals(Icons.AutoMirrored.Outlined.VolumeUp, localVolumeIcon(volume = 15, maxVolume = 15))
    }

    @Test
    fun localVolumeIcon_neverRepeatsTheOutputDeviceIconShownInTheHeader() {
        // 标题行显示的是输出设备图标（rememberAudioDeviceInfo），滑条前不能再放同一个
        val deviceIcons = setOf(Icons.Default.SpeakerGroup, Icons.Default.Headset, Icons.Outlined.CastConnected)
        for (volume in 0..15) {
            assertFalse(localVolumeIcon(volume, maxVolume = 15) in deviceIcons)
        }
    }
}
