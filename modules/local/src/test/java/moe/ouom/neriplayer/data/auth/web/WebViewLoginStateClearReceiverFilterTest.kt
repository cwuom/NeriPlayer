package moe.ouom.neriplayer.data.auth.web

import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class WebViewLoginStateClearReceiverFilterTest {
    private val receivers = listOf(
        NeteaseWebViewLoginStateClearReceiver(),
        BiliWebViewLoginStateClearReceiver(),
        YouTubeWebViewLoginStateClearReceiver()
    )

    @Test
    fun `unrelated broadcasts are ignored before reading any extra`() {
        receivers.forEach { receiver ->
            val context = mock(Context::class.java)
            val intent = mock(Intent::class.java)
            doReturn("android.intent.action.BOOT_COMPLETED").`when`(intent).action

            receiver.onReceive(context, intent)

            verify(intent, never()).getStringExtra(anyString())
            verifyNoInteractions(context)
        }
    }

    @Test
    fun `clear requests without a request id are dropped without acknowledging`() {
        receivers.forEach { receiver ->
            val context = mock(Context::class.java)
            val intent = mock(Intent::class.java)
            doReturn(ACTION_CLEAR_WEBVIEW_LOGIN_STATE).`when`(intent).action

            receiver.onReceive(context, intent)

            verify(intent).getStringExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID)
            verifyNoInteractions(context)
        }
    }
}
