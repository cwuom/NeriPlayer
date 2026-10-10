package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerCoverFrameComparisonTest {

    private val frame = MiniPlayerCoverFrame(
        coverUrl = "content://tree/Covers/song.jpg",
        identityKey = "local:song",
        requestToken = "request-1"
    )

    @Test
    fun `missing frames are only equal to each other`() {
        assertTrue(sameMiniPlayerCoverRequest(null, null))
        assertFalse(sameMiniPlayerCoverRequest(frame, null))
        assertFalse(sameMiniPlayerCoverRequest(null, frame))

        assertTrue(sameMiniPlayerCoverFrame(null, null))
        assertFalse(sameMiniPlayerCoverFrame(frame, null))
        assertFalse(sameMiniPlayerCoverFrame(null, frame))
    }

    @Test
    fun `cover request identity includes source identity and request token`() {
        assertTrue(sameMiniPlayerCoverRequest(frame, frame.copy()))
        assertFalse(
            sameMiniPlayerCoverRequest(frame, frame.copy(coverUrl = "content://tree/Covers/other.jpg"))
        )
        assertFalse(sameMiniPlayerCoverRequest(frame, frame.copy(identityKey = "local:other")))
        assertFalse(sameMiniPlayerCoverRequest(frame, frame.copy(requestToken = "request-2")))
    }

    @Test
    fun `cover frame identity ignores the request token`() {
        assertTrue(sameMiniPlayerCoverFrame(frame, frame.copy(requestToken = "request-2")))
        assertFalse(
            sameMiniPlayerCoverFrame(frame, frame.copy(coverUrl = "content://tree/Covers/other.jpg"))
        )
        assertFalse(sameMiniPlayerCoverFrame(frame, frame.copy(identityKey = "local:other")))
    }

    @Test
    fun `retained cover survives until the grace delay elapses without a current song`() {
        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = frame,
                failedFrame = null,
                clearDelayElapsed = false,
                hasCurrentSong = false
            )
        )
        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = frame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `nothing is cleared when no cover is retained`() {
        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = null,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun `retained cover is cleared after grace when no replacement is requested`() {
        assertTrue(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = frame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun `pending replacement keeps the retained cover unless that request failed`() {
        val requested = frame.copy(coverUrl = "content://tree/Covers/next.jpg", requestToken = "request-2")

        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = requested,
                retainedFrame = frame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = requested,
                retainedFrame = frame,
                failedFrame = frame,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
        assertTrue(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = requested,
                retainedFrame = frame,
                failedFrame = requested.copy(),
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
    }
}
