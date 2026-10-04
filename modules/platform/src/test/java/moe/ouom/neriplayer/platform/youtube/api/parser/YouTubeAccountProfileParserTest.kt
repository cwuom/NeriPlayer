package moe.ouom.neriplayer.platform.youtube.api.parser

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeAccountProfileParserTest {
    @Test
    fun `current account header wins over another account menu item`() {
        val root = JSONObject("""{
          "items":[{"accountItemRenderer":{"accountName":{"simpleText":"Other account"}}}],
          "actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{
            "activeAccountHeaderRenderer":{
              "accountName":{"runs":[{"text":" Neri "},{"text":"User "}]},
              "accountPhoto":{"thumbnails":[{"url":"//yt3.ggpht.com/small=s32"},{"url":"//yt3.ggpht.com/large=s88"}]}
            }
          }}}}}]
        }""")

        assertEquals(YouTubeAccountProfile("Neri User", "https://yt3.ggpht.com/large=s88"), parseYouTubeAccountProfile(root))
    }

    @Test
    fun `selected account fallback never picks the first unselected item`() {
        val root = JSONObject("""{"items":[
          {"accountItemRenderer":{"accountName":{"simpleText":"Other account"},"isSelected":false}},
          {"accountItem":{"displayName":{"text":"Current account"},"isSelected":true,"avatar":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a.png"}]}}}}
        ]}""")

        assertEquals(YouTubeAccountProfile("Current account", "https://yt3.ggpht.com/a.png"), parseYouTubeAccountProfile(root))
    }

    @Test
    fun `unnamed account still makes an unselected multi account response ambiguous`() {
        val root = JSONObject("""{"contents":[
          {"accountItemRenderer":{"accountName":{"simpleText":""}}},
          {"accountItemRenderer":{"channelName":"Music Account","thumbnail":"//yt3.ggpht.com/avatar=s64"}}
        ]}""")

        assertNull(parseYouTubeAccountProfile(root))
    }

    @Test
    fun `exactly one account renderer allows the single account fallback`() {
        val root = JSONObject("""{"contents":[
          {"accountItemRenderer":{"channelName":"Music Account","thumbnail":"//yt3.ggpht.com/avatar=s64"}}
        ]}""")

        assertEquals(YouTubeAccountProfile("Music Account", "https://yt3.ggpht.com/avatar=s64"), parseYouTubeAccountProfile(root))
    }

    @Test
    fun `ambiguous multi account response has no current profile`() {
        val root = JSONObject("""{"items":[
          {"accountItemRenderer":{"accountName":"First"}},
          {"accountItemRenderer":{"accountName":"Second"}}
        ]}""")

        assertNull(parseYouTubeAccountProfile(root))
    }

    @Test
    fun `failed empty and generic menu responses cannot fabricate an account`() {
        for (raw in listOf(
            "{}",
            """{"error":{"code":401,"message":"signed out"}}""",
            """{"items":[{"title":"Your channel","thumbnail":"https://yt3.ggpht.com/a.png"}]}""",
            """{"activeAccountHeaderRenderer":{"accountName":null,"accountPhoto":"https://yt3.ggpht.com/a.png"}}"""
        )) assertNull(parseYouTubeAccountProfile(JSONObject(raw)))
    }

    @Test
    fun `invalid avatar keeps the nickname and selects a safe thumbnail fallback`() {
        assertEquals(
            YouTubeAccountProfile("Fixture", null),
            parseYouTubeAccountProfile(JSONObject("""{"activeAccountHeaderRenderer":{"title":"Fixture","avatar":"file:///private/a.png"}}"""))
        )
        assertEquals(
            YouTubeAccountProfile("Fixture", "https://yt3.ggpht.com/a.png"),
            parseYouTubeAccountProfile(JSONObject("""{"activeAccountHeaderRenderer":{"title":"Fixture","avatar":{"thumbnails":[{"url":"http://yt3.ggpht.com/a.png"},{"url":"javascript:invalid"}]}}}"""))
        )
    }
}
