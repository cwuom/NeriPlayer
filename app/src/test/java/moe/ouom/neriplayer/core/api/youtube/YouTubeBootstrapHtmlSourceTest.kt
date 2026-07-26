package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun optionalString_supportsWhitespaceBetweenSetAndOpeningParenthesis() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set ({
                  "INNERTUBE_API_KEY":"spaced-api-key",
                  "VISITOR_DATA":"visitor-data",
                  "INNERTUBE_CLIENT_VERSION":"1.20260408.01.00"
                });
                </script>
            """.trimIndent()
        )

        assertEquals("spaced-api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("visitor-data", source.optionalString("VISITOR_DATA"))
        assertEquals("1.20260408.01.00", source.optionalString("INNERTUBE_CLIENT_VERSION"))
    }

    @Test
    fun optionalNumber_findsSignatureTimestampWithoutDecodingWholeBootstrapHtml() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <html>
                <script>
                ytcfg.set({
                  "WEB_PLAYER_CONTEXT_CONFIGS": {
                    "WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_WATCH": {
                      "signatureTimestamp": 20550
                    }
                  },
                  "jsUrl": "/s/player/test-player/base.js"
                });
                </script>
                </html>
            """.trimIndent()
        )

        assertEquals("20550", source.optionalNumber("STS", "signatureTimestamp"))
    }

    @Test
    fun optionalString_prefersShallowFieldOverSameNameNestedDeeper() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  "INNERTUBE_API_KEY": "top-level",
                  "WEB_PLAYER_CONTEXT_CONFIGS": {
                    "WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_WATCH": {
                      "INNERTUBE_API_KEY": "nested"
                    }
                  }
                });
                </script>
            """.trimIndent()
        )

        assertEquals("top-level", source.optionalString("INNERTUBE_API_KEY"))
    }

    @Test
    fun optionalString_skipsBlankScalarsAndKeepsSearchingDeeper() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  "INNERTUBE_API_KEY": "anchor",
                  "VISITOR_DATA": "",
                  "NESTED": { "VISITOR_DATA": "real-visitor-data" }
                });
                </script>
            """.trimIndent()
        )

        assertEquals("real-visitor-data", source.optionalString("VISITOR_DATA"))
    }

    @Test
    fun optionalString_resolvesAbsentOptionalFieldsWithoutScanningWholeDocument() {
        // 首页正文按 MB 计，缺失的可选字段过去每个都要全文正则两遍
        val filler = "<div>x</div>\\x20".repeat(60_000)
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                ytcfg.set({
                  "INNERTUBE_API_KEY": "api-key",
                  "VISITOR_DATA": "visitor-data"
                });
                </script>
                $filler
            """.trimIndent()
        )

        val startedAtMs = System.currentTimeMillis()
        assertEquals("api-key", source.optionalString("INNERTUBE_API_KEY"))
        assertEquals("", source.optionalString("appInstallData"))
        assertEquals("", source.optionalString("coldConfigData"))
        assertEquals("", source.optionalString("rolloutToken"))
        assertEquals("", source.optionalString("deviceExperimentId"))
        assertEquals("", source.optionalNumber("SESSION_INDEX"))
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertTrue("bootstrap 字段解析耗时 ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun optionalString_stillMatchesWhenAnEarlierMentionOfTheFieldDoesNotMatch() {
        // 字段名先在别处出现且不构成匹配, 起点前移不能把后面真正的赋值漏掉
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                var notes = "signatureTimestamp is described here";
                var padding = "${"x".repeat(4096)}";
                ytcfg.set({"INNERTUBE_API_KEY": "k"});
                var cfg = {signatureTimestamp: "20655"};
                </script>
            """.trimIndent()
        )

        assertEquals("20655", source.optionalNumber("signatureTimestamp"))
    }

    @Test
    fun optionalString_findsAQuotedFieldSittingAtTheVeryStartOfTheDocument() {
        // 起点回退一个字符, 字段名在文档最开头也不能越界
        val source = YouTubeBootstrapHtmlSource("\"jsUrl\": \"/s/player/edge/base.js\"")

        assertEquals("/s/player/edge/base.js", source.optionalString("jsUrl"))
    }

    @Test
    fun optionalString_returnsBlankWhenTheFieldIsAbsentEntirely() {
        val source = YouTubeBootstrapHtmlSource("<script>var unrelated = 1;</script>")

        assertTrue(source.optionalString("jsUrl").isEmpty())
    }
}
