package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.lanltw.core.LanPeerLiveness.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class LanPeerLivenessTest {
    @Test fun onlyAnUnansweredProbeDuringRegularChecksExpires() {
        val peer = LanPeerLiveness(15_000.0)
        assertEquals(Status.HEALTHY, peer.check(2_500.0, 0.0))
        assertEquals(Status.PROBE, peer.check(90_000.0, 0.0))
        assertEquals(Status.HEALTHY, peer.check(92_500.0, 0.0))
        assertEquals(Status.EXPIRED, peer.check(95_000.0, 0.0))
    }

    @Test fun sleepDuringAnOutstandingProbeStartsAFreshResponseWindow() {
        val peer = LanPeerLiveness(15_000.0)
        assertEquals(Status.PROBE, peer.check(20_000.0, 0.0))
        assertEquals(Status.PROBE, peer.check(90_000.0, 0.0))
        assertEquals(Status.HEALTHY, peer.check(92_500.0, 90_010.0))
        assertEquals(Status.PROBE, peer.check(110_000.0, 90_010.0))
        assertEquals(Status.HEALTHY, peer.check(112_500.0, 110_500.0))
    }
}
