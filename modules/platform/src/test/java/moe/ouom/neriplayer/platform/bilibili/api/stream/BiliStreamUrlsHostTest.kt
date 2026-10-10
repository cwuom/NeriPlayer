package moe.ouom.neriplayer.platform.bilibili.api.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliStreamUrlsHostTest {

    @Test
    fun `stream hosts are normalized before matching`() {
        assertTrue(isBiliStreamHost(" UPOS-SZ-MIRRORCOS.BILIVIDEO.COM "))
        assertTrue(isBiliStreamHost("b-demo.edge.mountaintoys.cn"))
        assertFalse(isBiliStreamHost("   "))
        assertFalse(isBiliStreamHost("mountaintoys.cn.example.com"))
    }

    @Test
    fun `stream urls need a parseable bilibili host`() {
        assertTrue(isBiliStreamUrl("https://cn-gd-ct-01-01.bilivideo.com/upgcxcode/demo.m4s"))
        assertFalse(isBiliStreamUrl("https://example.com/demo.m4s"))
        assertFalse(isBiliStreamUrl("mailto:someone@bilivideo.com"))
        assertFalse(isBiliStreamUrl("https://bad host/demo.m4s"))
    }

    @Test
    fun `stream urls are ordered by host tier then original position`() {
        val upos = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"
        val mcdn = "https://xy1x2x3x4xy.mcdn.bilivideo.cn:8082/b.m4s"
        val edge = "https://b-demo.edge.mountaintoys.cn/c.m4s"
        val lookalike = "https://upos-sz.example.com/d.m4s"
        val unparsable = "https://bad host/e.m4s"

        assertEquals(
            listOf(upos, mcdn, edge, lookalike, unparsable),
            prioritizeBiliStreamUrls(
                primaryUrl = " $lookalike ",
                backupUrls = listOf(edge, "  ", unparsable, mcdn, lookalike, upos)
            )
        )
    }
}
