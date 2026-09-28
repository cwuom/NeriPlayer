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

    @Test
    fun stillCachesAnAnonymousParseWhenNothingIsCachedYet() {
        // 出口被判游客时服务端会一直回 loggedIn=false, 一律不缓存会让缓存永远建不起来,
        // 每次播放现拉现解析的那几秒会一比一落在首播上
        assertFalse(
            demotesCachedYouTubeLogin(
                cachedLoggedIn = null,
                parsedLoggedIn = false,
                holdsLoginCookies = true
            )
        )
        assertFalse(
            demotesCachedYouTubeLogin(
                cachedLoggedIn = false,
                parsedLoggedIn = false,
                holdsLoginCookies = true
            )
        )
    }

    @Test
    fun refusesToOverwriteACachedLoginWithAnAnonymousParse() {
        assertTrue(
            demotesCachedYouTubeLogin(
                cachedLoggedIn = true,
                parsedLoggedIn = false,
                holdsLoginCookies = true
            )
        )
    }

    @Test
    fun letsALoggedInParseThrough() {
        assertFalse(
            demotesCachedYouTubeLogin(
                cachedLoggedIn = true,
                parsedLoggedIn = true,
                holdsLoginCookies = true
            )
        )
    }

    @Test
    fun caresOnlyAboutLoginCookiesWhenGuardingTheCache() {
        // 本来就没登录时, 游客态解析就是事实
        assertFalse(
            demotesCachedYouTubeLogin(
                cachedLoggedIn = true,
                parsedLoggedIn = false,
                holdsLoginCookies = false
            )
        )
    }
}
