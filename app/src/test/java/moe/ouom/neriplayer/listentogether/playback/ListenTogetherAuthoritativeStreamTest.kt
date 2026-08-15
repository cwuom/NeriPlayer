package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherAuthoritativeStreamTest {

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

    @Test
    fun `authoritative stream ignores a stale track from another queue position`() {
        val current = track(
            stableKey = "netease:current",
            streamUrl = "https://m701.music.126.net/current.mp3"
        )
        val stale = track(
            stableKey = "netease:stale",
            streamUrl = "https://m701.music.126.net/stale.mp3"
        )
        val state = ListenTogetherRoomState(
            roomId = "room",
            version = 3L,
            queue = listOf(stale, current),
            currentIndex = 1,
            track = stale
        )

        assertEquals(
            "https://m701.music.126.net/current.mp3",
            state.authoritativeStreamUrlForCurrentTrack()
        )
    }

    @Test
    fun `stale track cannot replace the current queue entry`() {
        val current = track(
            stableKey = "netease:current",
            streamUrl = "https://m701.music.126.net/current.mp3"
        )
        val stale = track(
            stableKey = "netease:stale",
            streamUrl = "https://m701.music.126.net/stale.mp3"
        )

        assertEquals(
            listOf(stale, current),
            listOf(stale, current).mergeCurrentTrack(
                currentIndex = 1,
                currentTrack = stale
            )
        )
    }

    @Test
    fun `unavailable link only reloads when the listener lacks a complete stream`() {
        assertFalse(
            shouldReloadForListenTogetherLinkUnavailable(
                isController = false,
                localPlaybackRequiresAuthoritativeStream = false
            )
        )
        assertTrue(
            shouldReloadForListenTogetherLinkUnavailable(
                isController = false,
                localPlaybackRequiresAuthoritativeStream = true
            )
        )
        assertFalse(
            shouldReloadForListenTogetherLinkUnavailable(
                isController = true,
                localPlaybackRequiresAuthoritativeStream = true
            )
        )
    }

    @Test
    fun `confirmed unavailable link suppresses ordinary requests but allows forced recovery`() {
        assertFalse(
            shouldRequestListenTogetherControllerLink(
                force = false,
                controllerLinkUnavailable = true
            )
        )
        assertTrue(
            shouldRequestListenTogetherControllerLink(
                force = true,
                controllerLinkUnavailable = true
            )
        )
        assertTrue(
            shouldRequestListenTogetherControllerLink(
                force = false,
                controllerLinkUnavailable = false
            )
        )
    }

    @Test
    fun `unavailable link reload is only issued once for a target`() {
        assertTrue(
            shouldReloadForListenTogetherLinkUnavailable(
                isController = false,
                localPlaybackRequiresAuthoritativeStream = true,
                alreadyReloadedForStableKey = false
            )
        )
        assertFalse(
            shouldReloadForListenTogetherLinkUnavailable(
                isController = false,
                localPlaybackRequiresAuthoritativeStream = true,
                alreadyReloadedForStableKey = true
            )
        )
    }

    @Test
    fun `controller does not declare link unavailable while matching playback resolves`() {
        assertTrue(
            shouldDeferControllerLinkResolution(
                playbackResolutionPending = true,
                currentTrackStableKey = "netease:current",
                requestedStableKey = "netease:current"
            )
        )
        assertFalse(
            shouldDeferControllerLinkResolution(
                playbackResolutionPending = true,
                currentTrackStableKey = "netease:other",
                requestedStableKey = "netease:current"
            )
        )
        assertFalse(
            shouldDeferControllerLinkResolution(
                playbackResolutionPending = false,
                currentTrackStableKey = "netease:current",
                requestedStableKey = "netease:current"
            )
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
