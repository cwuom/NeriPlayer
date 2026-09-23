package moe.ouom.neriplayer.core.comment.repository

import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.comment.model.CommentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版 Bilibili 歌曲「打包播放 id -> avid」解包的单元测试。
 *
 * 规则与播放侧 `BiliSongResolver.resolveLegacy` 一致: `avid * 10000 + 分P序号`;
 * 并且只有拿到「解包候选的 aid 与 bvid 都与本歌曲一致」的证据后才允许采用。
 */
class BiliCommentLegacyIdTest {

    /**
     * 打包播放 id 解出真实 avid: 170001 * 10000 + 3 = 1700010003 -> 170001。
     */
    @Test
    fun `decodes packed legacy playback id back to avid`() {
        assertEquals(170001L, legacyBiliResourceIdOrNull(1700010003L))
        assertEquals(115483835630912L, legacyBiliResourceIdOrNull(1154838356309120001L))
    }

    /**
     * 小于 10000 的 id 不可能是打包值, 直接返回 null。
     */
    @Test
    fun `returns null for ids below the pack factor`() {
        assertNull(legacyBiliResourceIdOrNull(0L))
        assertNull(legacyBiliResourceIdOrNull(-1L))
        assertNull(legacyBiliResourceIdOrNull(9999L))
        assertNull(legacyBiliResourceIdOrNull(10_000L))
    }

    /**
     * 余数为 0 (分P序号为 0) 不符合打包格式, 返回 null。
     */
    @Test
    fun `returns null when the packed id has no page remainder`() {
        assertNull(legacyBiliResourceIdOrNull(1700010000L))
    }

    /**
     * 普通 aid 也会得到一个「候选解」, 但只有候选视频的 aid 与 bvid 都与本歌曲一致时才会被采用
     * (见 BiliCommentRepository: 解包结果仅作为失败后的重试目标)。
     */
    @Test
    fun `plain aid still yields a candidate that must be verified to be adopted`() {
        assertEquals(11548383563L, legacyBiliResourceIdOrNull(115483835630912L))
    }

    /**
     * 只有平台「确定性的业务拒绝」才允许尝试解包;
     * 网络 / 服务端 / 未知故障一律不猜 id, 以免把别的视频的评论展示给用户。
     */
    @Test
    fun `only deterministic business rejections are eligible for the legacy fallback`() {
        assertTrue(isLegacyFallbackEligible(CommentError.NOT_FOUND))
        assertTrue(isLegacyFallbackEligible(CommentError.CLOSED))
        assertTrue(isLegacyFallbackEligible(CommentError.API))

        assertFalse(isLegacyFallbackEligible(CommentError.NETWORK))
        assertFalse(isLegacyFallbackEligible(CommentError.PERMISSION))
        assertFalse(isLegacyFallbackEligible(CommentError.SERVER))
        assertFalse(isLegacyFallbackEligible(CommentError.UNKNOWN))
    }

    /**
     * 解包候选必须同时命中 aid 与 album 里记录的 bvid, 否则视为「另一条视频」, 不允许采用。
     */
    @Test
    fun `legacy candidate is verified only when aid and bvid both match`() {
        val info = biliVideoInfo(aid = 170001L, bvid = "BV1xx411c7mD")

        assertTrue(hasVerifiedLegacyVideo(info, 170001L, "BV1xx411c7mD"))
        // bvid 大小写不敏感
        assertTrue(hasVerifiedLegacyVideo(info, 170001L, "bv1xx411c7md"))

        // aid 不匹配 -> 另一条视频的候选解
        assertFalse(hasVerifiedLegacyVideo(info, 170002L, "BV1xx411c7mD"))
        // bvid 不匹配 -> 另一条视频
        assertFalse(hasVerifiedLegacyVideo(info, 170001L, "BV1another"))
        // 没有 bvid 证据时不允许猜测
        assertFalse(hasVerifiedLegacyVideo(info, 170001L, null))
        assertFalse(hasVerifiedLegacyVideo(info, 170001L, "   "))
        // 视频信息缺失 (接口失败 / 视频不存在)
        assertFalse(hasVerifiedLegacyVideo(null, 170001L, "BV1xx411c7mD"))
    }

    /**
     * 构造一个只关心 aid / bvid 的 [BiliClient.VideoBasicInfo] 测试替身。
     */
    private fun biliVideoInfo(aid: Long, bvid: String): BiliClient.VideoBasicInfo =
        BiliClient.VideoBasicInfo(
            aid = aid,
            bvid = bvid,
            title = "title",
            coverUrl = "",
            desc = "",
            durationSec = 0,
            ownerMid = 0L,
            ownerName = "",
            ownerFace = "",
            stats = BiliClient.VideoStats(
                view = 0L,
                danmaku = 0L,
                reply = 0L,
                favorite = 0L,
                coin = 0L,
                share = 0L,
                like = 0L
            ),
            pages = emptyList()
        )
}
