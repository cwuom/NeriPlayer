package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试: cookie 收编作用于每一条 YouTube 响应, 无条件执行 Set-Cookie 删除指令时
 * 一次 SID=EXPIRED 就会把登录身份永久抹掉并落盘, 用户被无故登出
 */
class YouTubeAuthCookieLogoutGuardTest {

    private fun loggedInBundle(): YouTubeAuthBundle {
        val cookies = linkedMapOf(
            "SID" to "sid-value",
            "HSID" to "hsid-value",
            "SSID" to "ssid-value",
            "APISID" to "apisid-value",
            "SAPISID" to "sapisid-value",
            "__Secure-1PSID" to "secure-1psid",
            "__Secure-3PSID" to "secure-3psid",
            "VISITOR_INFO1_LIVE" to "visitor-value"
        )
        return YouTubeAuthBundle(
            cookies = cookies,
            cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        )
    }

    @Test
    fun `expired SID from a response must not log the user out`() {
        val base = loggedInBundle()
        assertTrue(base.hasLoginCookies())

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "SID=EXPIRED; Max-Age=0; Path=/; Domain=.youtube.com",
                "__Secure-1PSID=EXPIRED; Max-Age=0; Path=/",
                "__Secure-3PSID=EXPIRED; Max-Age=0; Path=/"
            )
        )

        // 要么整体拒绝合并, 要么合并后仍是登录态
        if (merged != null) {
            assertTrue("登录身份不得被响应删除", merged.hasLoginCookies())
        }
    }

    @Test
    fun `blanking login cookies must not log the user out`() {
        val base = loggedInBundle()

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "SID=; Path=/; Domain=.youtube.com",
                "SAPISID=; Path=/"
            )
        )

        if (merged != null) {
            assertTrue("空值覆盖等价删除，同样不得掉登录", merged.hasLoginCookies())
        }
    }

    @Test
    fun `anonymous response cookies are still adopted without dropping login`() {
        val base = loggedInBundle()

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "VISITOR_INFO1_LIVE=new-visitor; Path=/; Domain=.youtube.com",
                "YSC=new-ysc; Path=/"
            )
        )

        assertNotNull("非身份 cookie 应正常收编", merged)
        assertTrue(merged!!.hasLoginCookies())
        assertEquals("new-visitor", merged.cookies["VISITOR_INFO1_LIVE"])
        assertEquals("new-ysc", merged.cookies["YSC"])
    }

    @Test
    fun `login cookie rotation is still adopted`() {
        val base = loggedInBundle()

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "SID=rotated-sid; Path=/; Domain=.youtube.com"
            )
        )

        assertNotNull("正常轮换必须被收编，否则会重放已作废令牌", merged)
        assertEquals("rotated-sid", merged!!.cookies["SID"])
        assertTrue(merged.hasLoginCookies())
    }

    @Test
    fun `anonymous session can still drop its own cookies`() {
        // 未登录时不设保护
        val anonymous = YouTubeAuthBundle(
            cookies = linkedMapOf("VISITOR_INFO1_LIVE" to "v", "YSC" to "y"),
            cookieHeader = "VISITOR_INFO1_LIVE=v; YSC=y"
        )

        val merged = mergeYouTubeAuthCookieUpdates(
            base = anonymous,
            setCookieHeaders = listOf("YSC=EXPIRED; Max-Age=0; Path=/")
        )

        assertNotNull(merged)
        assertNull(merged!!.cookies["YSC"])
    }

    @Test
    fun `no changes returns null`() {
        val base = loggedInBundle()
        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf("SID=sid-value; Path=/")
        )
        assertNull("无变化时不应触发落盘", merged)
    }

    @Test
    fun `expired HSID and LOGIN_INFO from a response must not be dropped`() {
        val base = loggedInBundle().let { bundle ->
            val cookies = LinkedHashMap(bundle.cookies).apply { put("LOGIN_INFO", "login-info-value") }
            bundle.copy(
                cookies = cookies,
                cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            )
        }

        // 匿名响应会带上这几项的过期指令, 旧保护只覆盖 SID 家族, 于是它们被删掉并落盘,
        // 而 youtube.com 正是靠 HSID 和 LOGIN_INFO 判定登录
        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "HSID=EXPIRED; Max-Age=0; Path=/; Domain=.youtube.com",
                "LOGIN_INFO=; Max-Age=0; Path=/; Domain=.youtube.com",
                "LSID=EXPIRED; Max-Age=0; Path=/"
            )
        )

        val result = merged ?: base
        assertEquals("hsid-value", result.cookies["HSID"])
        assertEquals("login-info-value", result.cookies["LOGIN_INFO"])
        assertTrue(result.hasLoginCookies())
    }

    @Test
    fun `a blank HSID update must not erase the stored one`() {
        val base = loggedInBundle()

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf("HSID=; Path=/; Domain=.youtube.com")
        )

        val result = merged ?: base
        assertEquals("hsid-value", result.cookies["HSID"])
    }

    @Test
    fun `an anonymous session still lets HSID expire`() {
        val cookies = linkedMapOf("HSID" to "hsid-value", "YSC" to "ysc-value")
        val anonymous = YouTubeAuthBundle(
            cookies = cookies,
            cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        )

        // 本来就没登录时没有身份可保, 过期指令就该照办
        val merged = mergeYouTubeAuthCookieUpdates(
            base = anonymous,
            setCookieHeaders = listOf("HSID=EXPIRED; Max-Age=0; Path=/")
        )

        assertNotNull(merged)
        assertNull(merged!!.cookies["HSID"])
    }
}
