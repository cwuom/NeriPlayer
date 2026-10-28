package moe.ouom.neriplayer.ui.viewmodel

import android.content.res.Resources
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncResultMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class SyncFeedbackLocaleTest {
    @Test
    fun `completed sync feedback resolves its semantic result after a language switch`() {
        val result = SyncResult(true, "上次语言的消息", messageKind = SyncResultMessage.NO_CHANGE)
        val text = syncResultText(result, webDav = false)

        assertEquals("同步成功", text.resolve(resources("同步成功")))
        assertEquals("Sync successful", text.resolve(resources("Sync successful")))
    }

    private fun resources(value: String): Resources = mock(Resources::class.java) { invocation ->
        if (invocation.method.name == "getString" &&
            invocation.arguments[0] == CoreCommonR.string.github_sync_no_change
        ) value else null
    }
}
