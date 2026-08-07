package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockTarget
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliSponsorBlockPlaybackControllerTest {
    @Test
    fun `selected Bili part exposes its exact SponsorBlock target before metadata loading`() {
        val song = SongItem(
            id = 115_481_721_705_067L,
            name = "RapTure",
            artist = "Kadeza",
            album = "Bilibili|33638122342|BV1Ha1cBJExg",
            albumId = 0L,
            durationMs = 725_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "115481721705067",
            subAudioId = "33638122342"
        )

        assertEquals(
            BiliSponsorBlockTarget(
                bvid = "BV1Ha1cBJExg",
                cid = 33_638_122_342L,
                durationMs = 725_000L
            ),
            song.explicitBiliSponsorBlockTargetOrNull()
        )
    }

    @Test
    fun `Bili video without a selected page still requires metadata resolution`() {
        val song = SongItem(
            id = 115_481_721_705_067L,
            name = "RapTure",
            artist = "Kadeza",
            album = "Bilibili||BV1Ha1cBJExg",
            albumId = 0L,
            durationMs = 831_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "115481721705067"
        )

        assertNull(song.explicitBiliSponsorBlockTargetOrNull())
    }
}
