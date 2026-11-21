package moe.ouom.neriplayer.lanltw.protocol
import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanPermissions
class LanCodecTest {
    @Test fun olderPermissionFramesDoNotGrantVolumeAndExplicitGrantsRoundTrip() {
        val old = LanCodec.decode("""{"type":"LAN_PERMISSIONS","permissions":{"seek":false}}""")
        assertEquals(LanPermissions(seek = false, volume = false), old?.permissions)
        val granted = LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(volume = true))
        val encoded = LanCodec.encode(granted)
        assertTrue(encoded.contains("\"volume\":true"))
        assertEquals(granted, LanCodec.decode(encoded))
    }

    @Test fun boundedRoundTrip() {
        val message = LanMessage("NTP_REQUEST", t0 = 1.25, probeGroupId = 3, probeGroupIndex = 1)
        assertEquals(message, LanCodec.decode(LanCodec.encode(message)))
        assertTrue(LanCodec.validProbe(message))
        assertFalse(LanCodec.validProbe(message.copy(probeGroupIndex = 2)))
        assertNull(LanCodec.decode("{"))
        assertNull(LanCodec.decode("x".repeat(LanCodec.MAX_BYTES + 1)))
        assertNull(LanCodec.decode("{\"type\":\"HELLO\",\"name\":\"" + "中".repeat(30000) + "\"}"))
    }
    @Test fun unknownFieldsAreToleratedButNonFiniteNumbersAreRejected() {
        assertEquals("NEW", LanCodec.decode("""{"type":"NEW","future":3}""")?.type)
        assertNull(LanCodec.decode("""{"type":"PLAY","trackTimeSeconds":1e999}"""))
    }
}
