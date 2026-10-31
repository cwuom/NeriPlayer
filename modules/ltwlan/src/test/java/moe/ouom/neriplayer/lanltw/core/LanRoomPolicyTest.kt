package moe.ouom.neriplayer.lanltw.core
import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

class LanRoomPolicyTest {
    private val track = ListenTogetherTrack("netease:1", "netease", "1", name = "Song", artist = "Artist", durationMs = 10000)
    @Test fun identityAndMetadataCannotBeSpoofedOrUnbounded() {
        assertTrue(LanRoomPolicy.validIdentity(track))
        assertFalse(LanRoomPolicy.validIdentity(track.copy(stableKey = "netease:2")))
        assertFalse(LanRoomPolicy.validIdentity(track.copy(channelId = "local")))
        assertTrue(LanRoomPolicy.validMetadata(track))
        listOf(track.copy(stableKey = ""), track.copy(stableKey = "x".repeat(513)), track.copy(audioId = ""),
            track.copy(audioId = "x".repeat(257)), track.copy(name = "x".repeat(513)), track.copy(durationMs = -1))
            .forEach { assertFalse(LanRoomPolicy.validMetadata(it)) }
    }
    @Test fun intentPermissionMatrixAndChangedSourceAreIndependent() {
        val all = LanPermissions(true, true, true)
        for (intent in listOf("NEXT", "SEEK", "PLAY_PAUSE")) {
            assertTrue(LanRoomPolicy.allowed(true, LanPermissions(), intent, false))
            assertTrue(LanRoomPolicy.allowed(false, all, intent, false))
            assertFalse(LanRoomPolicy.allowed(false, LanPermissions(), intent, false))
        }
        assertFalse(LanRoomPolicy.allowed(true, all, "UNKNOWN", false))
        assertFalse(LanRoomPolicy.allowed(true, all, "PLAY_PAUSE", true))
        assertTrue(LanRoomPolicy.allowed(false, all, "NEXT", true))
    }
    @Test fun requestPositionIsAuthoritativeExceptExplicitSeek() {
        val state = LanPlayback(track, 4.0)
        fun decide(message: LanMessage, current: LanPlayback = state) = LanRoomPolicy.play(true, LanPermissions(), current, 4.0, message)
        assertEquals("SOURCE", decide(LanMessage("PLAY"), LanPlayback()).error)
        assertEquals("SOURCE", decide(LanMessage("PLAY", track = track.copy(channelId = "local"))).error)
        assertEquals("SOURCE", decide(LanMessage("PLAY", track = track.copy(name = "x".repeat(513)))).error)
        assertEquals("DENIED", decide(LanMessage("PLAY", intent = "BAD")).error)
        assertEquals(4.0, decide(LanMessage("PLAY", trackTimeSeconds = 999.0)).position, .00001)
        assertEquals(0.0, LanRoomPolicy.play(true, LanPermissions(), state, 10.4, LanMessage("PLAY")).position, .00001)
        assertEquals(4.0, decide(LanMessage("PLAY", track = track.copy(durationMs = 0))).position, .00001)
        assertEquals(0.0, decide(LanMessage("PLAY", intent = "NEXT")).position, .00001)
        for (invalid in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("INVALID", decide(LanMessage("PLAY", intent = "SEEK", trackTimeSeconds = invalid)).error)
        }
        assertEquals(10.0, decide(LanMessage("PLAY", intent = "SEEK", trackTimeSeconds = 500.0)).position, .00001)
        assertEquals(30.0, decide(LanMessage("PLAY", intent = "SEEK", track = track.copy(durationMs = 0), trackTimeSeconds = 30.0)).position, .00001)
    }
    @Test fun volumeAndTimingRejectNonFiniteInputsAndCanReturnToZero() {
        listOf(null, -1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { assertNull(LanRoomPolicy.volume(it)) }
        assertEquals(0f, LanRoomPolicy.volume(0f)); assertEquals(1f, LanRoomPolicy.volume(1f))
        val timing = LanPeerTiming()
        timing.reportRtt(null); timing.reportRtt(Double.NaN); timing.reportRtt(-1.0); timing.reportRtt(10001.0)
        assertEquals(0.0, timing.rtt, .00001)
        timing.reportRtt(10.0); timing.reportRtt(20.0); assertEquals(12.0, timing.rtt, .00001)
        timing.reportCompensation(null); timing.reportCompensation(Double.NaN); assertEquals(0.0, timing.compensation, .00001)
        timing.reportCompensation(9000.0); assertEquals(5000.0, timing.compensation, .00001)
        timing.reportCompensation(0.0); assertEquals(0.0, timing.compensation, .00001)
    }
}
