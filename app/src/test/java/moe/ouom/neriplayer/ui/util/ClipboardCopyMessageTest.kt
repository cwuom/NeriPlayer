package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardCopyMessageTest {
    @Test
    fun `copy results map to copied truncated and failed messages`() {
        assertEquals(
            CoreCommonR.string.toast_copied,
            ClipboardCopyResult.Copied(wasTruncated = false).copyMessageRes()
        )
        assertEquals(
            CoreCommonR.string.toast_copy_truncated,
            ClipboardCopyResult.Copied(wasTruncated = true).copyMessageRes()
        )
        assertEquals(
            CoreCommonR.string.toast_copy_failed,
            ClipboardCopyResult.TransactionTooLarge.copyMessageRes()
        )
    }
}
