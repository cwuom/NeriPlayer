package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.policy.skip.BiliSkipSegmentSource
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackAutoSkipPolicyTest {
    @Test
    fun `custom interval takes precedence over sponsor segment`() {
        val targets = RecordingTargets(custom = 8_000L, sponsor = 12_000L)
        val decision = PlaybackAutoSkipPolicy(targets).resolve(song(), 2_000L, 30_000L, false)

        assertEquals(8_000L, decision?.positionMs)
        assertEquals(BiliSkipSegmentSource.CUSTOM_INTERVAL, decision?.source)
        assertEquals(0, targets.sponsorCalls)
    }

    @Test
    fun `sponsor segment is used when no custom interval applies`() {
        val targets = RecordingTargets(custom = null, sponsor = 12_000L)
        val decision = PlaybackAutoSkipPolicy(targets).resolve(song(), 2_000L, 30_000L, false)

        assertEquals(12_000L, decision?.positionMs)
        assertEquals(BiliSkipSegmentSource.SPONSOR_BLOCK, decision?.source)
        assertEquals("bili_sponsor_block_auto_skip", decision?.widgetReason)
    }

    @Test
    fun `missing song and listen together never query skip targets`() {
        val targets = RecordingTargets(custom = 8_000L, sponsor = 12_000L)
        val policy = PlaybackAutoSkipPolicy(targets)

        assertNull(policy.resolve(null, 2_000L, 30_000L, false))
        assertNull(policy.resolve(song(), 2_000L, 30_000L, true))
        assertEquals(0, targets.customCalls)
    }

    private fun song() = SongItem(
        id = 1L,
        name = "song",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 30_000L,
        coverUrl = null
    )

    private class RecordingTargets(private val custom: Long?, private val sponsor: Long?) :
        PlaybackAutoSkipTargets {
        var customCalls = 0
        var sponsorCalls = 0
        override fun nextCustomPosition(song: SongItem, positionMs: Long, durationMs: Long): Long? {
            customCalls++
            return custom
        }
        override fun nextSponsorPosition(song: SongItem, positionMs: Long, durationMs: Long): Long? {
            sponsorCalls++
            return sponsor
        }
    }
}
