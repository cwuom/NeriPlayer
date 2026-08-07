package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliVideoSkipPlaybackControllerTest {
    @Test
    fun `target resolution retries are bounded with increasing delays`() {
        assertEquals(1_000L, resolveBiliVideoSkipTargetLoadRetryDelayMs(1))
        assertEquals(2_000L, resolveBiliVideoSkipTargetLoadRetryDelayMs(2))
        assertNull(resolveBiliVideoSkipTargetLoadRetryDelayMs(3))
        assertNull(resolveBiliVideoSkipTargetLoadRetryDelayMs(0))
    }

    @Test
    fun `late Bili cid replaces an unresolved track target`() {
        assertTrue(shouldReplaceBiliVideoSkipTrackForCid(activeCid = null, incomingCid = 42L))
        assertFalse(shouldReplaceBiliVideoSkipTrackForCid(activeCid = 42L, incomingCid = 42L))
        assertFalse(shouldReplaceBiliVideoSkipTrackForCid(activeCid = 42L, incomingCid = null))
    }

    @Test
    fun `resolved Bili target notifies the paused interval editor`() {
        val song = song(id = 8_010_001L)
        val otherSong = song(id = 8_010_002L)
        val target = BiliVideoSkipTarget(bvid = "BV1skiptest", cid = 42L)
        val generationBeforeResolve = BiliVideoSkipPlaybackController.activeTrackGeneration.value

        try {
            BiliVideoSkipPlaybackController.onPlaybackRequestStarted(
                song = song,
                requestToken = 8_010_001L
            )
            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = song,
                target = target,
                requestToken = 8_010_001L
            )

            assertEquals(target, BiliVideoSkipPlaybackController.activeTargetFor(song))
            assertNull(BiliVideoSkipPlaybackController.activeTargetFor(otherSong))
            assertNotEquals(
                generationBeforeResolve,
                BiliVideoSkipPlaybackController.activeTrackGeneration.value
            )
        } finally {
            BiliVideoSkipPlaybackController.onPlaybackRequestStarted(
                song = otherSong,
                requestToken = 8_010_002L
            )
        }
    }

    @Test
    fun `saved BVID and cid form an immediate skip target`() {
        val song = song(id = 8_010_009L).copy(
            album = "Bilibili|99|BV1savedtest",
            subAudioId = "99"
        )

        assertEquals(
            BiliVideoSkipTarget(bvid = "BV1savedtest", cid = 99L),
            song.explicitBiliVideoSkipTargetOrNull()
        )
    }

    @Test
    fun `late resolution from a previous request cannot replace the active Bili target`() {
        val firstSong = song(id = 8_010_011L)
        val currentSong = song(id = 8_010_012L)
        val firstTarget = BiliVideoSkipTarget(bvid = "BV1first", cid = 11L)
        val currentTarget = BiliVideoSkipTarget(bvid = "BV1current", cid = 12L)

        try {
            BiliVideoSkipPlaybackController.onPlaybackRequestStarted(
                song = firstSong,
                requestToken = 101L
            )
            BiliVideoSkipPlaybackController.onPlaybackRequestStarted(
                song = currentSong,
                requestToken = 102L
            )
            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = firstSong,
                target = firstTarget,
                requestToken = 101L
            )

            assertNull(BiliVideoSkipPlaybackController.activeTargetFor(currentSong))

            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = currentSong,
                target = currentTarget,
                requestToken = 102L
            )

            assertEquals(currentTarget, BiliVideoSkipPlaybackController.activeTargetFor(currentSong))
            assertNull(BiliVideoSkipPlaybackController.activeTargetFor(firstSong))
        } finally {
            BiliVideoSkipPlaybackController.onPlaybackRequestStarted(
                song = song(id = 8_010_013L),
                requestToken = 103L
            )
        }
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Bili test $id",
        artist = "NeriPlayer",
        album = "Bilibili",
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null
    )
}
