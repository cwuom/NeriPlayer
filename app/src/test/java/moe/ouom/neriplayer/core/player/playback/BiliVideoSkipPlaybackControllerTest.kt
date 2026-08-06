package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliVideoSkipPlaybackControllerTest {
    @Test
    fun `resolved Bili target notifies the paused interval editor`() {
        val song = song(id = 8_010_001L)
        val otherSong = song(id = 8_010_002L)
        val target = BiliVideoSkipTarget(bvid = "BV1skiptest", cid = 42L)
        val generationBeforeResolve = BiliVideoSkipPlaybackController.activeTrackGeneration.value

        try {
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
