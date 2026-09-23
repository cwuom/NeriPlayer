package moe.ouom.neriplayer.core.comment.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旧版 Bilibili 歌曲「打包播放 id -> avid」解包的单元测试。
 *
 * 规则与播放侧 `BiliSongResolver.resolveLegacy` 一致: `avid * 10000 + 分P序号`。
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
     * 普通 aid 也会得到一个「候选解」, 但只有该候选真的能取回评论时才会被采用
     * (见 BiliCommentRepository: 解包结果仅作为失败后的重试目标)。
     */
    @Test
    fun `plain aid still yields a candidate that must succeed to be adopted`() {
        assertEquals(11548383563L, legacyBiliResourceIdOrNull(115483835630912L))
    }
}
