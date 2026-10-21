package moe.ouom.neriplayer.data.model.bilibili.music

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliMusicPlaybackMappingTest {

    @Test
    fun `official mv uses its own uploader identity`() {
        val playback = requireNotNull(rankSong().toPlayableBiliVideoItem())

        assertEquals(1L, playback.id)
        assertEquals("BV-official", playback.bvid)
        assertEquals("官方账号", playback.uploader)
        assertEquals(6L, playback.uploaderMid)
    }

    @Test
    fun `official mv with missing or invalid owner id keeps that identity unknown`() {
        listOf(0L, -1L).forEach { ownerMid ->
            val playback = requireNotNull(rankSong().copy(mvUploaderMid = ownerMid).toPlayableBiliVideoItem())

            assertEquals("官方账号", playback.uploader)
            assertEquals(0L, playback.uploaderMid)
        }
    }

    @Test
    fun `creation fallback keeps creation uploader instead of the unavailable mv owner`() {
        val playback = requireNotNull(rankSong().copy(mvAid = 0L).toPlayableBiliVideoItem())

        assertEquals(2L, playback.id)
        assertEquals("BV-upload", playback.bvid)
        assertEquals("投稿者", playback.uploader)
        assertEquals(4L, playback.uploaderMid)
    }

    private fun rankSong() = BiliMusicRankSong(
        musicId = "music-1",
        rank = 1,
        title = "歌曲",
        singer = "原唱",
        album = "专辑",
        coverUrl = "https://example.com/album.jpg",
        videoCoverUrl = "https://example.com/upload.jpg",
        creationAid = 2L,
        creationBvid = "BV-upload",
        creationCid = 3L,
        creationDurationSec = 30,
        uploader = "投稿者",
        uploaderMid = 4L,
        heat = 5L,
        canListen = true,
        mvAid = 1L,
        mvBvid = "BV-official",
        mvDurationSec = 180,
        mvUploader = "官方账号",
        mvCoverUrl = "https://example.com/official.jpg",
        mvUploaderMid = 6L
    )
}
