package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBootstrapLoginDemotionTest {

    @Test
    fun refusesAnAnonymousParseWhileLoginCookiesAreHeld() {
        // 攥着登录 cookie 却解析出游客态, 那是服务端这次没认出来, 不是事实
        assertTrue(demotesYouTubeLogin(parsedLoggedIn = false, holdsLoginCookies = true))
    }

    @Test
    fun refusesItEvenOnAColdStartWithNothingCachedYet() {
        // 旧实现要求"缓存里已经有登录态"才拦, 于是冷启动这份会一路落盘并永久粘住
        assertTrue(demotesYouTubeLogin(parsedLoggedIn = false, holdsLoginCookies = true))
    }

    @Test
    fun acceptsAnAnonymousParseWhenThereIsNoLoginToLose() {
        assertFalse(demotesYouTubeLogin(parsedLoggedIn = false, holdsLoginCookies = false))
    }

    @Test
    fun acceptsALoggedInParse() {
        assertFalse(demotesYouTubeLogin(parsedLoggedIn = true, holdsLoginCookies = true))
        assertFalse(demotesYouTubeLogin(parsedLoggedIn = true, holdsLoginCookies = false))
    }
}
