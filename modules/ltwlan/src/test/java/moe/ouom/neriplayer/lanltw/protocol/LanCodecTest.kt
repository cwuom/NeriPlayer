package moe.ouom.neriplayer.lanltw.protocol
import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.LanMessage
class LanCodecTest {
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
