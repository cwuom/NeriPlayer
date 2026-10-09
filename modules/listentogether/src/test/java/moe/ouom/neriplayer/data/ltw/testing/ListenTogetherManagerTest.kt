package moe.ouom.neriplayer.data.ltw.testing

import android.content.Context
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkListener
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkMonitor
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherPlatformHost
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError
import org.mockito.Mockito.mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.`when`

internal class FakeListenTogetherNetworkMonitor : ListenTogetherNetworkMonitor {
    var listener: ListenTogetherNetworkListener? = null
        private set
    var starts = 0
        private set
    var stops = 0
        private set

    override fun start(listener: ListenTogetherNetworkListener) {
        starts++
        this.listener = listener
    }

    override fun stop() {
        stops++
        listener = null
    }
}

internal class FakeListenTogetherPlatformHost : ListenTogetherPlatformHost {
    override val applicationContext: Context = mock(Context::class.java).apply {
        `when`(getString(anyInt())).thenAnswer { "resource:${it.arguments[0]}" }
    }
    override val networkMonitor = FakeListenTogetherNetworkMonitor()
    override fun isInitialized() = false
    override fun isPlaybackServiceReady() = true
    override fun startForegroundSync(reason: String) = Unit
    override fun message(resourceId: Int) = "resource:$resourceId"
    override fun validationMessage(error: ListenTogetherValidationError) = "validation:${error.messageResId}"
}

abstract class ListenTogetherManagerTest {
    private val managers = mutableListOf<ListenTogetherSessionManager>()

    protected fun testSessionManager(api: ListenTogetherApi, webSocketClient: ListenTogetherWebSocketClient): ListenTogetherSessionManager =
        ListenTogetherSessionManager(api, webSocketClient, FakeListenTogetherPlaybackHost(), FakeListenTogetherPlatformHost(), TestSongMapper)
            .also { managers += it }

    protected fun closeManagers() {
        managers.forEach(ListenTogetherSessionManager::close)
        managers.clear()
    }
}
