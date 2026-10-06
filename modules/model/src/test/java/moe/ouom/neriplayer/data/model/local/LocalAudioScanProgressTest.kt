package moe.ouom.neriplayer.data.model.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioScanProgressTest {

    @Test
    fun `scan fraction is unknown without a total and clamped otherwise`() {
        assertNull(LocalAudioScanProgress(processed = 3, total = 0).fraction)
        assertEquals(0.5f, requireNotNull(LocalAudioScanProgress(processed = 5, total = 10).fraction), 0f)
        assertEquals(1f, requireNotNull(LocalAudioScanProgress(processed = 15, total = 10).fraction), 0f)
    }
}
