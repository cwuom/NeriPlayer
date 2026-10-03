package moe.ouom.neriplayer.data.model.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云内置表情映射 (NeteaseEmoteCatalog) 的单元测试。
 *
 * 映射表取自网易云 web 端 core.js 的 `i5n` 表, 只内置「名称 -> 官方 CDN id」,
 * 表情图片仍由官方 CDN 按需加载, 因此这里同时校验 URL 模板拼装。
 */
class NeteaseEmoteCatalogTest {

    @Test
    fun `known emote name maps to official cdn url`() {
        assertEquals(
            "https://s1.music.126.net/style/web2/emt/emoji_86.png",
            neteaseEmoteUrl("大笑")
        )
    }

    @Test
    fun `emote id zero and one are preserved`() {
        assertEquals("https://s1.music.126.net/style/web2/emt/emoji_0.png", neteaseEmoteUrl("男孩"))
        assertEquals("https://s1.music.126.net/style/web2/emt/emoji_1.png", neteaseEmoteUrl("女孩"))
    }

    @Test
    fun `unknown emote name returns null`() {
        assertNull(neteaseEmoteUrl("这个表情不存在"))
        assertNull(neteaseEmoteUrl(""))
    }

    @Test
    fun `catalog key excludes the bracket marker form`() {
        assertNull(neteaseEmoteUrl("[大笑]"))
    }

    @Test
    fun `url map covers every built in emote with official host`() {
        val map = neteaseEmoteUrlMap()
        assertEquals(59, map.size)
        assertTrue(map.values.all { it.startsWith("https://s1.music.126.net/style/web2/emt/emoji_") })
        assertTrue(map.values.all { it.endsWith(".png") })
        assertEquals(neteaseEmoteUrl("爱心"), map["爱心"])
    }
}
