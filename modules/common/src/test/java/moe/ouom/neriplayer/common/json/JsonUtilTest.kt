package moe.ouom.neriplayer.common.json

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonUtilTest {

    @Test
    fun `maps serialise every supported value type in insertion order`() {
        val json = JsonUtil.toJson(
            linkedMapOf(
                "title" to "Neri",
                "count" to 3,
                "ratio" to 0.5,
                "explicit" to false,
                "nested" to linkedMapOf("id" to 7L, "missing" to null),
                "tags" to listOf("a", 1, null),
                "uri" to StringBuilder("content://media/1")
            )
        )

        assertEquals(
            """{"title":"Neri","count":3,"ratio":0.5,"explicit":false,""" +
                """"nested":{"id":7,"missing":null},"tags":["a", 1, null],"uri":"content://media/1"}""",
            json
        )
        assertTrue(JSONObject(json).getJSONObject("nested").isNull("missing"))
    }

    @Test
    fun `empty maps serialise to an empty object`() {
        assertEquals("{}", JsonUtil.toJson(emptyMap()))
    }

    @Test
    fun `strings escape quotes, backslashes and control characters`() {
        val raw = "a\"b\\c\b\u000C\n\r\t\u0001é"

        val quoted = JsonUtil.jsonQuote(raw)

        assertEquals("\"a\\\"b\\\\c\\b\\f\\n\\r\\t\\u0001é\"", quoted)
        assertEquals(raw, JSONObject("{\"v\":$quoted}").getString("v"))
        assertEquals("null", JsonUtil.jsonQuote(null))
    }
}
