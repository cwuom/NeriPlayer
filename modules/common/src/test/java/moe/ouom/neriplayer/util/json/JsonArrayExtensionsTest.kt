package moe.ouom.neriplayer.util.json

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonArrayExtensionsTest {
    @Test
    fun `mixed arrays skip non objects while preserving object order and duplicates`() {
        val array = JSONArray("""[null, 7, "text", false, [], {"id":2}, {"id":1}, {"id":2}]""")

        val ids = array.mapObjectsNotNull { it.getInt("id") }

        assertEquals(listOf(2, 1, 2), ids)
    }

    @Test
    fun `mapper decides which business records to omit`() {
        val array = JSONArray("""[{"id":1}, {"id":0}, {}, {"id":3}]""")

        val ids = array.mapObjectsNotNull { it.optInt("id").takeIf { id -> id > 0 } }

        assertEquals(listOf(1, 3), ids)
    }

    @Test
    fun `empty array never invokes the mapper`() {
        val result = JSONArray().mapObjectsNotNull<Int> { error("unexpected record") }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `mapper failure propagates unchanged without processing later records`() {
        val failure = IllegalStateException("invalid record")
        val visited = mutableListOf<Int>()

        try {
            JSONArray("""[{"id":1}, {"id":2}, {"id":3}]""").mapObjectsNotNull { item ->
                val id = item.getInt("id")
                visited += id
                if (id == 2) throw failure
                id
            }
            fail("Expected mapper failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf(1, 2), visited)
    }
}
