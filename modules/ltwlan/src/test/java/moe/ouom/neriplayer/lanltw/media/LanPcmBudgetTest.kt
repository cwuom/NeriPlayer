package moe.ouom.neriplayer.lanltw.media

import moe.ouom.neriplayer.data.model.lan.lanPcmFits
import org.junit.Assert.*
import org.junit.Test

class LanPcmBudgetTest {
    @Test fun permits512MiBAndRejectsOneMicrosecondBeyondIt() {
        // 65536 Hz * stereo * float32 gives an exact 1024-second boundary.
        assertTrue(lanPcmFits(1024_000_000L, 65536, 2))
        assertFalse(lanPcmFits(1024_000_001L, 65536, 2))
        assertTrue(lanPcmFits(600_000_000L, 48000, 2))
    }
    @Test fun rejectsInvalidFormatAndOverflowSizedDuration() {
        assertFalse(lanPcmFits(Long.MAX_VALUE, 192000, 2))
        assertFalse(lanPcmFits(0, 48000, 2))
        assertFalse(lanPcmFits(1000000, 48000, 3))
        assertFalse(lanPcmFits(1000000, 0, 2))
    }
}
