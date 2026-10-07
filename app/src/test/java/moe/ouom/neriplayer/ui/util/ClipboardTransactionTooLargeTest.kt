package moe.ouom.neriplayer.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.os.TransactionTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify

class ClipboardTransactionTooLargeTest {

    private val clipboard = mock(ClipboardManager::class.java)

    @Test
    fun `a successful copy publishes the prepared text and reports truncation`() {
        val oversized = "x".repeat(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS + 1)
        val shortClip = mock(ClipData::class.java)
        val truncatedClip = mock(ClipData::class.java)

        mockStatic(ClipData::class.java).use { clips ->
            clips.`when`<ClipData> { ClipData.newPlainText("lyrics", "short") }.thenReturn(shortClip)
            clips.`when`<ClipData> {
                ClipData.newPlainText("lyrics", oversized.take(ClipboardTextPolicy.MAX_TEXT_CODE_UNITS))
            }.thenReturn(truncatedClip)

            assertEquals(ClipboardCopyResult.Copied(wasTruncated = false), clipboard.copyPlainTextSafely("lyrics", "short"))
            assertEquals(ClipboardCopyResult.Copied(wasTruncated = true), clipboard.copyPlainTextSafely("lyrics", oversized))
        }
        verify(clipboard).setPrimaryClip(shortClip)
        verify(clipboard).setPrimaryClip(truncatedClip)
    }

    @Test
    fun `a transaction too large failure deep in the cause chain is reported`() {
        doThrow(
            RuntimeException("binder failed", IllegalStateException("wrapped", TransactionTooLargeException()))
        ).`when`(clipboard).setPrimaryClip(any())

        assertEquals(ClipboardCopyResult.TransactionTooLarge, clipboard.copyPlainTextSafely("lyrics", "text"))
    }

    @Test
    fun `other clipboard failures are rethrown`() {
        val failure = IllegalStateException("clipboard unavailable", IllegalArgumentException("bad clip"))
        doThrow(failure).`when`(clipboard).setPrimaryClip(any())

        val thrown = assertThrows(IllegalStateException::class.java) {
            clipboard.copyPlainTextSafely("lyrics", "text")
        }

        assertSame(failure, thrown)
    }
}
