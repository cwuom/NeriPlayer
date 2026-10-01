package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.playback.authoritativeStreamUrlForCurrentTrack
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.ltw.testing.toSongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherAuthoritativeStreamIntegrationTest {

    @Test
    fun `authoritative stream comes from current queue track after mapping hides session url`() {
        val current = track(
            stableKey = "netease:current",
            streamUrl = "https://m701.music.126.net/current.mp3"
        )
        val state = ListenTogetherRoomState(
            roomId = "room",
            version = 2L,
            queue = listOf(current),
            currentIndex = 0,
            track = current
        )

        assertNull(current.toSongItem().streamUrl)
        assertEquals(
            "https://m701.music.126.net/current.mp3",
            state.authoritativeStreamUrlForCurrentTrack()
        )
    }

    private fun track(stableKey: String, streamUrl: String): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = stableKey,
            channelId = "netease",
            audioId = stableKey,
            name = stableKey,
            artist = "artist",
            durationMs = 180_000L,
            streamUrl = streamUrl,
            streamUrls = listOf(streamUrl)
        )
    }
}
