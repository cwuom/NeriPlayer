package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeBootstrapHtmlSourceTest {

    @Test
    fun optionalString_findsNestedPlayerConfigValuesFromYtcfgJson() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  "WEB_PLAYER_CONTEXT_CONFIGS": {
                    "WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_WATCH": {
                      "jsUrl": "/s/player/test-player/base.js",
                      "innertubeApiKey": "nested-api-key"
                    }
                  },
                  "VISITOR_DATA": "visitor-data"
                });
                </script>
            """.trimIndent()
        )

        assertEquals("/s/player/test-player/base.js", source.optionalString("jsUrl"))
        assertEquals("nested-api-key", source.optionalString("innertubeApiKey"))
        assertEquals("visitor-data", source.optionalString("VISITOR_DATA"))
    }

    @Test
    fun optionalString_decodesEscapedQuotesBeforeParsingYtcfgJson() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  \x22INNERTUBE_API_KEY\x22 : \x22escaped-api-key\x22,
                  \x22LOGGED_IN\x22 : true,
                  \x22SESSION_INDEX\x22 : \x223\x22
                });
                </script>
            """.trimIndent()
        )

        assertEquals("escaped-api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("true", source.optionalBoolean("LOGGED_IN"))
        assertEquals("3", source.optionalNumber("SESSION_INDEX"))
    }
}
