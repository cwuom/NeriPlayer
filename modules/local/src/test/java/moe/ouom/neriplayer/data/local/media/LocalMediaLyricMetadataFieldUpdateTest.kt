package moe.ouom.neriplayer.data.local.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalMediaLyricMetadataFieldUpdateTest {
    @Test
    fun `present values are written to their keys`() {
        val root = JSONObject().put("title", "Song")

        update(root, matched = "[00:01]matched", original = "[00:01]original", clearMissing = false)

        assertEquals("[00:01]matched", root.getString(MATCHED_KEY))
        assertEquals("[00:01]original", root.getString(ORIGINAL_KEY))
        assertEquals("Song", root.getString("title"))
    }

    @Test
    fun `missing values clear both keys only when clearing is requested`() {
        val kept = stored()
        val cleared = stored()

        update(kept, matched = null, original = null, clearMissing = false)
        update(cleared, matched = null, original = null, clearMissing = true)

        assertEquals("old matched", kept.getString(MATCHED_KEY))
        assertEquals("old original", kept.getString(ORIGINAL_KEY))
        assertFalse(cleared.has(MATCHED_KEY))
        assertFalse(cleared.has(ORIGINAL_KEY))
        assertEquals("Song", cleared.getString("title"))
    }

    @Test
    fun `a single present value keeps the other stored key while clearing`() {
        val originalOnly = stored()
        val matchedOnly = stored()

        update(originalOnly, matched = null, original = "new original", clearMissing = true)
        update(matchedOnly, matched = "new matched", original = null, clearMissing = true)

        assertEquals("old matched", originalOnly.getString(MATCHED_KEY))
        assertEquals("new original", originalOnly.getString(ORIGINAL_KEY))
        assertEquals("new matched", matchedOnly.getString(MATCHED_KEY))
        assertEquals("old original", matchedOnly.getString(ORIGINAL_KEY))
    }

    private fun stored() = JSONObject()
        .put("title", "Song")
        .put(MATCHED_KEY, "old matched")
        .put(ORIGINAL_KEY, "old original")

    private fun update(root: JSONObject, matched: String?, original: String?, clearMissing: Boolean) {
        LocalMediaSupport.updateLyricMetadataField(
            root = root,
            matchedKey = MATCHED_KEY,
            originalKey = ORIGINAL_KEY,
            matchedValue = matched,
            originalValue = original,
            clearMissing = clearMissing
        )
    }

    private companion object {
        const val MATCHED_KEY = "translatedLyrics"
        const val ORIGINAL_KEY = "originalTranslatedLyrics"
    }
}
