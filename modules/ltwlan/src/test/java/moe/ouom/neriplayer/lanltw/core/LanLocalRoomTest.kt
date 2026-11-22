package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.*
import org.junit.Test

class LanLocalRoomTest {
    private val media = LanStreamResource("12345678-1234-1234-1234-123456789012", "application/octet-stream",
        byteLength = 1_100_000_000)
    private val track = ListenTogetherTrack("local:local-song", "local", "local-song", name = "Local",
        artist = "Artist", durationMs = 7_200_000)

    @Test fun longLocalTrackStartsWhenBothDevicesCanPlayTheSameRelayStream() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, m -> sent += m }
        room.join("h", "Host", true)
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = track.copy(mediaUri = "/private/audio")))
        room.request("h", LanMessage("LAN_STREAM_PUBLISHED", loadGeneration = sent.last().loadGeneration, streamResource = media))
        val original = sent.last { it.event?.type == "LOAD_AUDIO_SOURCE" }.event!!
        assertNull(original.track!!.mediaUri)
        assertEquals(media, original.streamResource)
        room.join("m", "Member", false)
        room.request("m", LanMessage("SYNC"))
        assertEquals(media, sent.last().event!!.streamResource)
        for (peer in listOf("h", "m")) {
            room.request(peer, LanMessage("AUDIO_SOURCE_READY", loadGeneration = 1, mediaId = media.mediaId))
        }
        assertTrue(room.snapshot().playback.playing)
        room.request("m", LanMessage("SYNC"))
        assertEquals(media, sent.last().streamResource)
    }

    @Test fun localTrackWithInconsistentStableIdentityNeverStartsPreparation() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, m -> sent += m }
        room.join("h", "Host", true)
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = track.copy(stableKey = "local:other")))
        assertEquals("SOURCE", sent.last().code)
        assertFalse(room.snapshot().playback.preparing)
    }
}
