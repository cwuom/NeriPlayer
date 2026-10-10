package moe.ouom.neriplayer.platform.youtube.api.bootstrap

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeBootstrapHtmlSourceLocalFieldTest {
    @Test
    fun `unindexed fields accept escaped single and unquoted keys`() {
        val source = YouTubeBootstrapHtmlSource(
            """
                <script>
                var cfg = {\x22PAGE_CL\x22:\x22a\nb\x41c\x22, 'PAGE_BUILD_LABEL': 'youtube.music_20260101', XSRF_FIELD_NAME: "session_token", INNERTUBE_CONTEXT_CLIENT_NAME: 67};
                </script>
            """.trimIndent()
        )

        assertEquals("a\\nbAc", source.optionalString("PAGE_CL"))
        assertEquals("youtube.music_20260101", source.optionalString("PAGE_BUILD_LABEL"))
        assertEquals("session_token", source.optionalString("XSRF_FIELD_NAME"))
        assertEquals("67", source.optionalNumber("INNERTUBE_CONTEXT_CLIENT_NAME"))
    }

    @Test
    fun `fields at the very start of the page are still matched`() {
        assertEquals("start", YouTubeBootstrapHtmlSource("PAGE_CL: \"start\"").optionalString("PAGE_CL"))
        assertEquals("visitor", YouTubeBootstrapHtmlSource("VISITOR_DATA: 'visitor'").optionalString("VISITOR_DATA"))
    }

    @Test
    fun `unindexed lookup skips blank mistyped and non scalar mentions`() {
        val source = YouTubeBootstrapHtmlSource(
            """
                var notes = "PAGE_CL is documented below";
                var cfg = {
                  MY_PAGE_CL: "prefixed", ${'$'}PAGE_CL: "dollar", PAGE_CLX: "suffixed",
                  "PAGE_CL": "", "PAGE_CL": {"id": 1}, "PAGE_CL": "build-label", "PAGE_CL": 900
                };
                PAGE_CL
            """.trimIndent()
        )

        assertEquals("build-label", source.optionalString("PAGE_CL"))
        assertEquals("900", source.optionalNumber("PAGE_CL"))
        assertEquals("", source.optionalBoolean("PAGE_CL"))
    }

    @Test
    fun `blank unindexed values also fail the literal quote fallback`() {
        val source = YouTubeBootstrapHtmlSource("""{"PAGE_CL": "", "INNERTUBE_API_KEY": "key"}""")

        assertEquals("", source.optionalString("PAGE_CL"))
        val error = assertThrows(IOException::class.java) {
            source.requireString("YouTube bootstrap parse failed", "PAGE_CL")
        }
        assertEquals("YouTube bootstrap parse failed: PAGE_CL", error.message)
        assertEquals("key", source.requireString("YouTube bootstrap parse failed", "PAGE_CL", "INNERTUBE_API_KEY"))
    }

    @Test
    fun `indexed fields ignore embedded names and mentions without a value`() {
        val source = YouTubeBootstrapHtmlSource(
            """
                var MY_VISITOR_DATA = "wrong";
                var description = "VISITOR_DATA comes later";
                cfg.VISITOR_DATA = "assigned";
                var cfg = {"VISITOR_DATA": "visitor"};
                "VISITOR_DATA"
            """.trimIndent()
        )

        assertEquals("visitor", source.optionalString("VISITOR_DATA"))
    }

    @Test
    fun `string values decode javascript hex and unicode escapes`() {
        val source = YouTubeBootstrapHtmlSource(
            """
                {"LOWER_HEX": "\x41b", "UPPER_HEX": "\X41b", "LOWER_UNICODE": "caf\u00e9",
                 "UPPER_UNICODE": "\U0041b", "PLAIN_ESCAPE": "a\tb", "NESTED_ESCAPE": "\x5cx41BC"}
            """.trimIndent()
        )

        assertEquals("Ab", source.optionalString("LOWER_HEX"))
        assertEquals("Ab", source.optionalString("UPPER_HEX"))
        assertEquals("café", source.optionalString("LOWER_UNICODE"))
        assertEquals("Ab", source.optionalString("UPPER_UNICODE"))
        assertEquals("a\\tb", source.optionalString("PLAIN_ESCAPE"))
        assertEquals("ABC", source.optionalString("NESTED_ESCAPE"))
    }

    @Test
    fun `unterminated strings are not returned`() {
        assertEquals("", YouTubeBootstrapHtmlSource("""{"PAGE_CL": "never closed""").optionalString("PAGE_CL"))
        assertEquals(
            "",
            YouTubeBootstrapHtmlSource("""{\x22PAGE_CL\x22: \x22never \x41closed \q""").optionalString("PAGE_CL")
        )
    }
}
