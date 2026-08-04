package moe.ouom.neriplayer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTextPolicyTest {

    @Test
    fun prepare_keepsTextAtTheLimit() {
        val source = "a".repeat(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS)

        val payload = ClipboardTextPolicy.prepare(source)

        assertEquals(source, payload.text)
        assertFalse(payload.wasTruncated)
    }

    @Test
    fun prepare_limitsOversizedText() {
        val source = "a".repeat(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS + 1)

        val payload = ClipboardTextPolicy.prepare(source)

        assertEquals(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS, payload.text.length)
        assertTrue(payload.wasTruncated)
    }

    @Test
    fun prepare_doesNotSplitSurrogatePairAtTheLimit() {
        val source = "a".repeat(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS - 1) + "\uD83D\uDE00"

        val payload = ClipboardTextPolicy.prepare(source)

        assertEquals(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS - 1, payload.text.length)
        assertFalse(Character.isHighSurrogate(payload.text.last()))
        assertTrue(payload.wasTruncated)
    }
}
