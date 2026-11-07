package moe.ouom.neriplayer.lanltw.core
import org.junit.Assert.*
import org.junit.Test
class LanAudioDurationTest {
    @Test fun rejectsThirtySecondPreviewAndWrongVersion() {
        assertFalse(LanAudioDuration.matches(279000, 30000))
        assertFalse(LanAudioDuration.matches(279000, 193097))
        assertTrue(LanAudioDuration.matches(193000, 193097))
        assertTrue(LanAudioDuration.matches(0, 193097))
        assertFalse(LanAudioDuration.matches(193000, 0))
        assertTrue(LanAudioDuration.matches(10000, 10500))
    }
}
