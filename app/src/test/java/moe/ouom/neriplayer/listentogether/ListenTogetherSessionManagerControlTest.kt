package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.listentogether.network.http.ListenTogetherApi
import moe.ouom.neriplayer.listentogether.network.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSessionManagerControlTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send control event returns failure when session is missing`() = runBlocking {
        val manager = ListenTogetherSessionManager(
            api = ListenTogetherApi(OkHttpClient()),
            webSocketClient = ListenTogetherWebSocketClient(OkHttpClient())
        )

        val response = manager.sendControlEvent(
            ListenTogetherEvent(type = "PLAY")
        )

        assertFalse(response.ok)
        assertEquals("baseUrl missing", response.error)
    }
}
