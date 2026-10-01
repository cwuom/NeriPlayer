package moe.ouom.neriplayer.data.ltw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import moe.ouom.neriplayer.data.ltw.testing.ListenTogetherManagerTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSessionManagerControlTest : ListenTogetherManagerTest() {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        closeManagers()
        Dispatchers.resetMain()
    }

    @Test
    fun `send control event returns failure when session is missing`() = runBlocking {
        val manager = testSessionManager(
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
