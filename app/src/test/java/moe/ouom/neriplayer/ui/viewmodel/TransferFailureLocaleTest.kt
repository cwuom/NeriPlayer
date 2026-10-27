package moe.ouom.neriplayer.ui.viewmodel

import android.content.res.Resources
import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.transfer.TransferFailureKind
import moe.ouom.neriplayer.data.model.transfer.TransferIOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class TransferFailureLocaleTest {
    @Test
    fun `internal output failure resolves in the current language instead of its diagnostic message`() {
        val failure = TransferIOException(TransferFailureKind.OUTPUT_UNAVAILABLE, "old diagnostic")
        val text = transferFailureText(failure)

        assertEquals("无法打开输出流", text.resolve(resources("无法打开输出流")))
        assertEquals("Cannot open output stream", text.resolve(resources("Cannot open output stream")))
    }

    @Test
    fun `external failure text is preserved without translation`() {
        assertEquals("HTTP 503", transferFailureText(IOException("HTTP 503")).resolve(resources("unused")))
    }

    private fun resources(value: String): Resources = mock(Resources::class.java) { invocation ->
        if (invocation.method.name == "getString" &&
            invocation.arguments[0] == CoreCommonR.string.error_cannot_open_output
        ) value else null
    }
}
