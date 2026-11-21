package moe.ouom.neriplayer.lanltw.protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.model.lan.LanRoom
import moe.ouom.neriplayer.data.model.lan.LanRoomEvent
class LanCodecTest {
    @Test fun olderPermissionFramesDoNotGrantVolumeAndExplicitGrantsRoundTrip() {
        val old = LanCodec.decode("""{"type":"LAN_PERMISSIONS","permissions":{"seek":false}}""")
        assertEquals(LanPermissions(seek = false, volume = false), old?.permissions)
        val granted = LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(volume = true))
        val encoded = LanCodec.encode(granted)
        assertTrue(encoded.contains("\"volume\":true"))
        assertEquals(granted, LanCodec.decode(encoded))
    }

    @Test fun joiningAnOlderHostRequiresBothLegacyProgressGrants() {
        val legacyValues = listOf(null, false, true)
        for (next in legacyValues) for (seek in legacyValues) {
            val flags = buildJsonObject {
                next?.let { put("next", it) }
                seek?.let { put("seek", it) }
            }
            val room = """{"id":"room","name":"Room","permissions":$flags}"""
            val welcome = LanCodec.decode("""{"type":"WELCOME","room":$room}""")
            assertEquals("legacy flags $flags", LanPermissions(seek = next != false && seek != false), welcome?.room?.permissions)
        }
    }

    @Test fun oldMembersSeeMatchingTrackAndSeekPermissionsOnRoomUpdates() {
        for (progress in listOf(false, true)) {
            val permissions = LanPermissions(playPause = false, seek = progress, volume = true)
            val room = LanRoom("room", "Room", permissions = permissions)
            val message = LanMessage("ROOM_EVENT", event = LanRoomEvent("CLIENT_CHANGE", room = room))
            val encoded = LanCodec.encode(message)
            val flags = Json.parseToJsonElement(encoded).jsonObject.getValue("event").jsonObject
                .getValue("room").jsonObject.getValue("permissions").jsonObject
            assertEquals(progress, flags["next"]?.jsonPrimitive?.boolean ?: true)
            assertEquals(progress, flags["seek"]?.jsonPrimitive?.boolean ?: true)
            assertEquals(message, LanCodec.decode(encoded))
        }
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
