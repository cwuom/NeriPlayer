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

    @Test
    fun optionalString_fallsBackToUnquotedJsObjectLiteralFields() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  INNERTUBE_API_KEY: 'literal-api-key',
                  LOGGED_IN: true,
                  SESSION_INDEX: 5,
                  WEB_PLAYER_CONTEXT_CONFIGS: {
                    WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_WATCH: {
                      jsUrl: '/s/player/unquoted/base.js'
                    }
                  }
                });
                </script>
            """.trimIndent()
        )

        assertEquals("literal-api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("true", source.optionalBoolean("LOGGED_IN"))
        assertEquals("5", source.optionalNumber("SESSION_INDEX"))
        assertEquals("/s/player/unquoted/base.js", source.optionalString("jsUrl"))
    }

    @Test
    fun optionalString_decodesDoubleEscapedQuotesForRegexFallback() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                var bootstrap = "{\\x22INNERTUBE_API_KEY\\x22:\\x22double-escaped-api-key\\x22,\\x22VISITOR_DATA\\x22:\\x22visitor-data\\x22}";
                </script>
            """.trimIndent()
        )

        assertEquals("double-escaped-api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("visitor-data", source.optionalString("VISITOR_DATA"))
    }

    @Test
    fun optionalString_decodesHexEscapedYtcfgObjectBeforeParsing() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set(\x7b\x22INNERTUBE_API_KEY\x22:\x22hex-api-key\x22,\x22VISITOR_DATA\x22:\x22hex-visitor-data\x22,\x22LOGGED_IN\x22:false\x7d);
                </script>
            """.trimIndent()
        )

        assertEquals("hex-api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("hex-visitor-data", source.optionalString("VISITOR_DATA"))
        assertEquals("false", source.optionalBoolean("LOGGED_IN"))
    }
}
