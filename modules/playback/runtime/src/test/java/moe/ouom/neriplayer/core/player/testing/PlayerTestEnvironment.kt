package moe.ouom.neriplayer.core.player.testing

import android.app.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal object PlayerTestEnvironment {
    // PlayerDependencies only ever accepts one Application per process.
    val application: Application = mock(Application::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
    }

    fun install(
        listenTogether: PlayerListenTogetherAccess = FakeListenTogetherAccess(),
        repositories: PlayerRepositoryDependencies = mock(PlayerRepositoryDependencies::class.java),
        downloads: PlayerDownloadAccess = mock(PlayerDownloadAccess::class.java),
        presentation: PlayerPresentationHost = mock(PlayerPresentationHost::class.java)
    ) {
        PlayerDependencies.install(
            PlayerEnvironment(
                application = application,
                repositories = repositories,
                downloads = downloads,
                listenTogether = listenTogether,
                presentation = presentation,
                isReady = { false },
                launchBackgroundIo = { Job().apply { complete() } }
            )
        )
    }

    fun reset() = install()
}

internal class FakeListenTogetherAccess : PlayerListenTogetherAccess {
    override val roomState = MutableStateFlow<ListenTogetherRoomState?>(null)
    override val sessionState = MutableStateFlow(ListenTogetherSessionState())
    val unavailableAudioLinks = mutableSetOf<Pair<String, String>>()
    var resumeRequests = 0
        private set

    override fun resumeListenerAfterSafetyPause() {
        resumeRequests += 1
    }

    override fun isControllerAudioLinkUnavailable(roomId: String, stableKey: String): Boolean =
        (roomId to stableKey) in unavailableAudioLinks
}
