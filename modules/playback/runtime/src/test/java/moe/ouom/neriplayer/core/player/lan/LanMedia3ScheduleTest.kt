package moe.ouom.neriplayer.core.player.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMedia3ScheduleTest {
    @Test fun onTimeScheduleUsesLocalMonotonicDeadline() {
        val plan = planLanMedia3Start(1_000, 2_000.0, 200.0, 30, 5.0, 90_000)

        assertEquals(LanMedia3StartPlan(1_770, 5_000), plan)
    }

    @Test fun lateStartSeeksAheadToItsNewDeadline() {
        val plan = planLanMedia3Start(2_200, 2_000.0, 0.0, 0, 5.0, 90_000)

        assertEquals(LanMedia3StartPlan(2_450, 5_450), plan)
    }

    @Test fun latePlanDoesNotStartBeyondTheRealStreamDuration() {
        assertNull(planLanMedia3Start(2_200, 2_000.0, 0.0, 0, 119.9, 120_000))
    }

    @Test fun explicitEndSeekKeepsAPlayableTailWhenCatalogDurationIsLonger() {
        val plan = planLanMedia3Start(1_000, 2_000.0, 0.0, 0, 125.0, 120_000)

        assertEquals(LanMedia3StartPlan(2_000, 119_900), plan)
        assertEquals(119_900L, clampLanMediaPosition(120_000, 120_000))
    }

    @Test fun planDoesNotRequireDeclaredOrExtractedDuration() {
        val plan = planLanMedia3Start(1_000, 2_000.0, 200.0, 30, 5.0, null)

        assertEquals(LanMedia3StartPlan(1_770, 5_000), plan)
    }

    @Test fun normalEncodingDurationVariationDoesNotLookLikeAPreview() {
        assertFalse(isLanDefinitePreview("netease", catalogMs = 263_000, seekableStreamMs = 258_000))
        assertFalse(isLanDefinitePreview("netease", catalogMs = 263_000, seekableStreamMs = null))
        assertFalse(isLanDefinitePreview("local", catalogMs = 263_000, seekableStreamMs = 30_000))
    }

    @Test fun stablePreviewLengthsForLongOnlineTracksAreRejected() {
        assertTrue(isLanDefinitePreview("netease", catalogMs = 263_000, seekableStreamMs = 30_000))
        assertTrue(isLanDefinitePreview("netease", catalogMs = 263_000, seekableStreamMs = 60_200))
        assertFalse(isLanDefinitePreview("netease", catalogMs = 72_000, seekableStreamMs = 60_000))
    }
}
