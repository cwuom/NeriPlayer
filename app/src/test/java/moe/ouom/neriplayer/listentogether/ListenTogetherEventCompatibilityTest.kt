package moe.ouom.neriplayer.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherEventCompatibilityTest {

    @Test
    fun `unsupported track finished error is detected for legacy workers`() {
        assertTrue(
            isUnsupportedTrackFinishedEventError("unsupported event type: TRACK_FINISHED")
        )
        assertTrue(
            isUnsupportedTrackFinishedEventError("unsuppported event type: TRACK_FINISHED")
        )
        assertFalse(
            isUnsupportedTrackFinishedEventError("unsupported event type: SEEK")
        )
    }

    @Test
    fun `controller track finished fallback advances with legacy set track`() {
        val firstTrack = track("netease:1", "1")
        val nextTrack = track("netease:2", "2")
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-finished",
                clientTimeMs = 900L,
                positionMs = 188_000L,
                currentIndex = 1,
                nextIndex = 1,
                track = nextTrack,
                queue = listOf(firstTrack, nextTrack),
                shouldPlay = true,
                finishedTrackStableKey = firstTrack.stableKey
            ),
            isController = true,
            nowMs = 1_000L,
            eventIdFactory = { "evt-legacy" }
        )

        assertEquals("SET_TRACK", fallback?.type)
        assertEquals("evt-legacy", fallback?.eventId)
        assertEquals(1_000L, fallback?.clientTimeMs)
        assertEquals(0L, fallback?.positionMs)
        assertEquals(1, fallback?.currentIndex)
        assertNull(fallback?.nextIndex)
        assertEquals(nextTrack, fallback?.track)
        assertEquals(true, fallback?.shouldPlay)
        assertEquals("playing", fallback?.state)
        assertNull(fallback?.finishedTrackStableKey)
    }

    @Test
    fun `controller track finished fallback pauses at queue end`() {
        val lastTrack = track("netease:1", "1")
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-finished",
                positionMs = 205_000L,
                currentIndex = 0,
                nextIndex = 0,
                queue = listOf(lastTrack),
                shouldPlay = false,
                finishedTrackStableKey = lastTrack.stableKey
            ),
            isController = true,
            nowMs = 1_000L,
            eventIdFactory = { "evt-pause" }
        )

        assertEquals("PAUSE", fallback?.type)
        assertEquals("evt-pause", fallback?.eventId)
        assertEquals(205_000L, fallback?.positionMs)
        assertEquals(0, fallback?.currentIndex)
        assertEquals(false, fallback?.shouldPlay)
        assertEquals("paused", fallback?.state)
        assertNull(fallback?.finishedTrackStableKey)
    }

    @Test
    fun `listener track finished does not create legacy control fallback`() {
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-listener",
                shouldPlay = true
            ),
            isController = false,
            nowMs = 1_000L,
            eventIdFactory = { "evt-legacy" }
        )

        assertNull(fallback)
    }

    @Test
    fun `playback command keeps playing intent while media is still loading`() {
        assertTrue(
            resolveListenTogetherPlaybackCommandShouldPlay(
                commandType = "PLAY_FROM_QUEUE",
                commandShouldPlay = null,
                localTransportActive = true,
                localPlaying = false
            )
        )
    }

    @Test
    fun `explicit command should play wins over transport snapshot`() {
        assertFalse(
            resolveListenTogetherPlaybackCommandShouldPlay(
                commandType = "TRACK_FINISHED",
                commandShouldPlay = false,
                localTransportActive = true,
                localPlaying = true
            )
        )
    }

    @Test
    fun `link ready does not pause a room that is already playing`() {
        assertEquals(
            "playing",
            resolveListenTogetherLinkReadyState(
                roomPlaybackState = "playing",
                localTransportActive = false,
                localPlaying = false
            )
        )
    }

    @Test
    fun `link ready keeps pending local playback intent`() {
        assertEquals(
            "playing",
            resolveListenTogetherLinkReadyState(
                roomPlaybackState = "paused",
                localTransportActive = true,
                localPlaying = false
            )
        )
    }

    private fun track(
        stableKey: String,
        audioId: String
    ): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = stableKey,
            channelId = ListenTogetherChannels.NETEASE,
            audioId = audioId,
            name = "Song $audioId",
            artist = "Artist"
        )
    }
}
