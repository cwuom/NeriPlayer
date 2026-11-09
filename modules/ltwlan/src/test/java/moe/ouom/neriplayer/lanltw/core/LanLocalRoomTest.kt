package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.*
import org.junit.Test

class LanLocalRoomTest {
    private val media = LanLocalMedia("12345678-1234-1234-1234-123456789012", "a".repeat(64), 1024, 60000, "audio/mpeg")
    private val track get() = ListenTogetherTrack(media.stableKey, "local", media.sha256, name = "Local", artist = "Artist", durationMs = media.durationMs)
    @Test fun localLoadAndLateSyncCarryCanonicalDescriptorWithoutDevicePaths() {
        val sent = mutableListOf<Pair<String, LanMessage>>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { peer, message -> sent += peer to message }
        room.join("h", "Host", true)
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = track.copy(mediaUri = "/private/audio"), localMedia = media))
        val load = sent.last().second.event!!
        assertEquals(media, load.localMedia)
        assertNull(load.track!!.mediaUri)
        assertTrue(load.sourceUrls.isEmpty())
        room.join("m", "Member", false)
        room.request("m", LanMessage("SYNC"))
        assertEquals(media, sent.last().second.event!!.localMedia)
        for (peer in listOf("h", "m")) room.request(peer, LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(track.stableKey), loadGeneration = 1, contentDigest = media.sha256))
        assertTrue(room.snapshot().playback.playing)
        room.request("m", LanMessage("SYNC"))
        assertEquals(media, sent.last().second.localMedia)
    }
    @Test fun rejectsMissingInvalidAndMixedMediaDescriptors() {
        val invalid = listOf(
            LanMessage("PLAY", intent = "NEXT", track = track),
            LanMessage("PLAY", intent = "NEXT", track = track, localMedia = media.copy(byteLength = 0)),
            LanMessage("PLAY", intent = "NEXT", track = track, localMedia = media.copy(durationMs = 1000)),
            LanMessage("PLAY", intent = "NEXT", track = track, localMedia = media, sourceUrls = listOf("https://example.com/audio")),
            LanMessage("PLAY", intent = "NEXT", track = track.copy(channelId = "netease", audioId = "1", stableKey = "netease:1"),
                localMedia = media, sourceUrls = listOf("https://m.music.126.net/audio")))
        for (message in invalid) {
            val sent = mutableListOf<LanMessage>()
            val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, m -> sent += m }
            room.join("h", "Host", true)
            room.request("h", message)
            assertEquals("FULL_AUDIO_REQUIRED", sent.last().code)
            assertFalse(room.snapshot().playback.playing)
        }
    }
    @Test fun malformedDigestCannotAcknowledgeAnOnlineLoad() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, m -> sent += m }
        room.join("h", "Host", true)
        val online = track.copy(channelId = "netease", audioId = "1", stableKey = "netease:1")
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = online, sourceUrls = listOf("https://m.music.126.net/a")))
        room.request("h", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(online.stableKey), loadGeneration = 1, contentDigest = "bad"))
        assertFalse(room.snapshot().playback.playing)
        room.request("h", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(online.stableKey), loadGeneration = 1, contentDigest = "a".repeat(64)))
        assertTrue(room.snapshot().playback.playing)
    }
    @Test fun forgedIdentityAndWrongFileAreRejected() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, message -> sent += message }
        room.join("h", "Host", true)
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = track, localMedia = media.copy(sha256 = "b".repeat(64))))
        assertEquals("FULL_AUDIO_REQUIRED", sent.last().code)
        room.request("h", LanMessage("PLAY", intent = "NEXT", track = track, localMedia = media))
        room.request("h", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(track.stableKey), loadGeneration = 1, contentDigest = "b".repeat(64)))
        assertTrue(sent.any { it.code == "AUDIO_CONTENT" })
        assertFalse(room.snapshot().playback.playing)
    }
}
