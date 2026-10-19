package moe.ouom.neriplayer.ui.viewmodel.tab.bili

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableBiliVideoItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliMusicHomeRepositoryTest {

    @Test
    fun `official mv aid alone still requires its own playback metadata`() {
        assertTrue(needsEnrichment(completeSong().copy(mvDurationSec = 0, mvUploader = "", mvCoverUrl = "")))
    }

    @Test
    fun `missing official mv duration still requires enrichment`() {
        assertTrue(needsEnrichment(completeSong().copy(mvDurationSec = 0)))
    }

    @Test
    fun `missing official mv uploader still requires enrichment`() {
        assertTrue(needsEnrichment(completeSong().copy(mvUploader = "")))
    }

    @Test
    fun `missing official mv cover still requires enrichment`() {
        assertTrue(needsEnrichment(completeSong().copy(mvCoverUrl = "")))
    }

    @Test
    fun `complete official mv metadata does not request enrichment`() {
        assertFalse(needsEnrichment(completeSong()))
    }

    @Test
    fun `song without official mv bvid cannot request enrichment`() {
        assertFalse(needsEnrichment(completeSong().copy(mvBvid = "", mvAid = 0L)))
    }

    @Test
    fun `known mv ids receive their own metadata across multiple batches`() = runTest {
        val songs = incompleteSongs()

        val resolved = BiliMusicHomeRepository.resolveOfficialMvIds(songs) { bvid -> videoInfo(bvid) }
        val playback = requireNotNull(resolved.last().toPlayableBiliVideoItem())

        assertEquals(5, resolved.size)
        assertEquals(5L, playback.id)
        assertEquals("BV-official-5", playback.bvid)
        assertEquals(180, playback.durationSec)
        assertEquals("官方账号", playback.uploader)
        assertEquals("https://example.com/official.jpg", playback.coverUrl)
    }

    @Test
    fun `successful requests with an unavailable field do not stop later batches`() = runTest {
        val resolved = BiliMusicHomeRepository.resolveOfficialMvIds(incompleteSongs()) { bvid ->
            videoInfo(bvid).let { info ->
                if (info.aid < 5L) info.copy(durationSec = 0) else info
            }
        }

        assertEquals(0, resolved.first().mvDurationSec)
        assertEquals("官方账号", resolved.first().mvUploader)
        assertEquals(180, resolved.last().mvDurationSec)
    }

    @Test
    fun `a failed batch preserves known mv identities and stops later requests`() = runTest {
        val songs = incompleteSongs()
        val requests = ConcurrentLinkedQueue<String>()

        val resolved = BiliMusicHomeRepository.resolveOfficialMvIds(songs) { bvid ->
            requests.add(bvid)
            throw IOException("blocked")
        }

        assertEquals(songs, resolved)
        assertEquals(4, requests.size)
    }

    @Test
    fun `mv enrichment cancellation propagates to the caller`() = runTest {
        val failure = runCatching {
            BiliMusicHomeRepository.resolveOfficialMvIds(incompleteSongs()) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun needsEnrichment(song: BiliMusicRankSong): Boolean {
        val getter = BiliMusicHomeRepository::class.java.getDeclaredMethod(
            "getNeedsMvEnrichment",
            BiliMusicRankSong::class.java
        )
        getter.isAccessible = true
        return getter.invoke(BiliMusicHomeRepository, song) as Boolean
    }

    private fun incompleteSongs(): List<BiliMusicRankSong> = (1L..5L).map { aid ->
        completeSong().copy(
            mvAid = aid,
            mvBvid = "BV-official-$aid",
            mvDurationSec = 0,
            mvUploader = "",
            mvCoverUrl = ""
        )
    }

    private fun videoInfo(bvid: String) = VideoBasicInfo(
        aid = bvid.substringAfterLast('-').toLong(),
        bvid = bvid,
        title = "官方 MV",
        coverUrl = "http://example.com/official.jpg",
        desc = "MV 简介",
        durationSec = 180,
        ownerMid = 6L,
        ownerName = "官方账号",
        ownerFace = "https://example.com/avatar.jpg",
        stats = VideoStats(1L, 2L, 3L, 4L, 5L, 6L, 7L),
        pages = emptyList()
    )

    private fun completeSong() = BiliMusicRankSong(
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
        mvCoverUrl = "https://example.com/official.jpg"
    )
}
