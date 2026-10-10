package moe.ouom.neriplayer.data.local.media

import com.kyant.taglib.PropertyMap
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaWritableCommentsTest {
    @Test
    fun `duplicate and blank comments are collapsed before writing`() {
        val tags = propertyMap("COMMENT" to arrayOf("first", " ", "first", "second", ""))

        normalizeWritableComments(tags)

        assertEquals(listOf("first", "second"), tags.getValue("COMMENT").toList())
        assertEquals(listOf("Song"), tags.getValue("TITLE").toList())
    }

    @Test
    fun `comments without text are dropped instead of clearing every comment frame`() {
        val tags = propertyMap("COMMENT" to arrayOf("", "  "))

        normalizeWritableComments(tags)

        assertEquals(setOf("TITLE"), tags.keys)
    }

    @Test
    fun `maps without comments are left untouched`() {
        val tags = propertyMap()

        normalizeWritableComments(tags)

        assertEquals(setOf("TITLE"), tags.keys)
        assertEquals(listOf("Song"), tags.getValue("TITLE").toList())
    }

    private fun propertyMap(vararg values: Pair<String, Array<String>>): PropertyMap =
        hashMapOf("TITLE" to arrayOf("Song"), *values)
}
